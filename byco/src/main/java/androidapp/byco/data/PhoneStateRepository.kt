/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidapp.byco.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED
import android.net.NetworkRequest
import androidapp.byco.BycoApplication
import androidapp.byco.data.PhoneStateRepository.NetworkType.METERED
import androidapp.byco.data.PhoneStateRepository.NetworkType.NO_NETWORK
import androidapp.byco.data.PhoneStateRepository.NetworkType.UNMETERED
import androidapp.byco.util.Repository
import androidapp.byco.util.SingleParameterSingletonOf
import androidapp.byco.util.stateIn
import androidx.annotation.MainThread
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.TimeUnit.SECONDS

/** Not riding related phone state */
class PhoneStateRepository private constructor(
    private val app: BycoApplication,
    private val CONNECTION_CHECK_RETRY_DELAY: Long = SECONDS.toMillis(3)
) : Repository(app) {
    enum class NetworkType {
        UNMETERED,
        METERED,
        NO_NETWORK
    }

    /** Currently connected [NetworkType] */
    val networkType = callbackFlow {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        var networkCheckRetryDelay = CONNECTION_CHECK_RETRY_DELAY

        suspend fun update() {
            val activeNetwork = cm.activeNetwork
            val activeNetworkCapabilities = cm.getNetworkCapabilities(activeNetwork)

            val networkType = when {
                activeNetworkCapabilities == null
                        || !activeNetworkCapabilities.hasCapability(NET_CAPABILITY_INTERNET)
                        || activeNetworkCapabilities.hasCapability(NET_CAPABILITY_CAPTIVE_PORTAL) -> {
                    NO_NETWORK
                }

                activeNetworkCapabilities.hasCapability(NET_CAPABILITY_NOT_METERED)
                        && activeNetworkCapabilities.hasCapability(NET_CAPABILITY_NOT_RESTRICTED) ->
                    UNMETERED

                else -> {
                    METERED
                }
            }

            // When turning off wifi and hence switching to cell the active network becomes null but
            // there is no further callback. Hence we never realize that the active network changed
            // and never re-evaluate the connectivity. Hence force a periodic network check while
            // the network is not active.
            if (activeNetwork == null) {
                launch {
                    delay(networkCheckRetryDelay)

                    // Keep checking more slowly if previous checks failed.
                    networkCheckRetryDelay *= 2
                    ensureActive()

                    update()
                }
            } else {
                networkCheckRetryDelay = CONNECTION_CHECK_RETRY_DELAY
            }

            send(networkType)
        }

        val networkCallback = object : NetworkCallback() {
            override fun onUnavailable() {
                launch { update() }
            }

            override fun onAvailable(network: Network) {
                launch { update() }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                launch { update() }
            }

            override fun onLost(network: Network) {
                launch { update() }
            }
        }

        cm.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)
        update()

        awaitClose {
            cm.unregisterNetworkCallback(networkCallback)
        }
    }.stateIn(NO_NETWORK)

    /** Current date and time. Updates at least at beginning of every full minute */
    val date = callbackFlow {
        val tickReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                launch {
                    send(Date())
                }
            }
        }

        app.registerReceiver(tickReceiver, IntentFilter(Intent.ACTION_TIME_TICK))

        awaitClose { app.unregisterReceiver(tickReceiver) }
    }.stateIn(Date())

    /** Battery level in % */
    val batteryLevel = callbackFlow {
        val batteryLevelReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                launch {
                    send(intent.getIntExtra("level", 0))
                }
            }
        }

        app.registerReceiver(batteryLevelReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.let { batteryLevelReceiver.onReceive(app, it) }

        awaitClose {
            app.unregisterReceiver(batteryLevelReceiver)
        }
    }.stateIn(100)

    /** Signals whenever a package changed. */
    val packageChanged = callbackFlow {
        val packageChangedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                launch { send(intent) }
            }
        }

        app.registerReceiver(packageChangedReceiver,
            IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply {
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
        )

        awaitClose {
            app.unregisterReceiver(packageChangedReceiver)
        }
    }.stateIn(Intent())

    enum class ProcessPriority {
        RIDING,
        FOREGROUND,
        BACKGROUND,
        STOPPED,
    }

    /** What priority does the app-process currently have. */
    inner class ProcessPriorityState {
        /** Priorities of currently running activities, services, etc... .*/
        private val priorities = mutableListOf<ProcessPriority>()
        private val updatePriorities = MutableStateFlow(emptyList<ProcessPriority>())

        val flow = updatePriorities.map { priorities ->
            priorities.minOfOrNull { it } ?: ProcessPriority.STOPPED
        }.stateIn(ProcessPriority.STOPPED)

        /**
         * Called by active units (e.g. Activities, Services, Jobs) to adjust the priority of this
         * process.
         */
        @MainThread
        fun onStart(priority: ProcessPriority) {
            priorities.add(priority)
            repositoryScope.launch(Main.immediate) {
                updatePriorities.emit(priorities.toMutableList())
            }
        }

        /**
         * Called by active units (e.g. Activities, Services, Jobs) to adjust the priority of this
         * process.
         */
        @MainThread
        fun onStop(priority: ProcessPriority) {
            priorities.remove(priority)
            repositoryScope.launch(Main.immediate) {
                // Copy list to make sure [flow] updates.
                updatePriorities.emit(priorities.toMutableList())
            }
        }
    }

    val processPriority = ProcessPriorityState()

    companion object :
        SingleParameterSingletonOf<BycoApplication, PhoneStateRepository>({ PhoneStateRepository(it) })
}