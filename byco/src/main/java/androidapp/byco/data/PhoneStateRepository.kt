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

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.*
import android.net.NetworkRequest
import androidapp.byco.data.PhoneStateRepository.NetworkType.*
import androidapp.byco.util.AsyncLiveData
import androidapp.byco.util.SingleParameterSingletonOf
import androidx.lifecycle.LiveData
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.TimeUnit.SECONDS

/** Not riding related phone state */
class PhoneStateRepository private constructor(
    private val app: Application,
    private val CONNECTION_CHECK_RETRY_DELAY: Long = SECONDS.toMillis(3)
) {
    enum class NetworkType {
        UNMETERED,
        METERED,
        NO_NETWORK
    }

    /** Currently connected [NetworkType] */
    val networkType = object : AsyncLiveData<NetworkType>() {
        private val cm = app.getSystemService(ConnectivityManager::class.java)
        private var networkCheckRetryDelay = CONNECTION_CHECK_RETRY_DELAY

        private val networkCallback = object : NetworkCallback() {
            override fun onUnavailable() {
                requestUpdate()
            }

            override fun onAvailable(network: Network) {
                requestUpdate()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                requestUpdate()
            }

            override fun onLost(network: Network) {
                requestUpdate()
            }
        }

        init {
            value = NO_NETWORK
        }

        override fun onActive() {
            super.onActive()

            cm.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)
            requestUpdate()
        }

        override fun onInactive() {
            super.onInactive()

            cm.unregisterNetworkCallback(networkCallback)
        }

        override suspend fun update(): NetworkType? {
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
                liveDataScope.launch {
                    delay(networkCheckRetryDelay)

                    // Keep checking more slowly if previous checks failed.
                    networkCheckRetryDelay *= 2
                    ensureActive()

                    requestUpdate()
                }
            } else {
                networkCheckRetryDelay = CONNECTION_CHECK_RETRY_DELAY
            }

            return if (networkType == value) {
                null
            } else {
                networkType
            }
        }
    }

    /** Current date and time. Updates at least at beginning of every full minute */
    val date = object : LiveData<Date>() {
        private val tickReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                value = Date()
            }
        }

        override fun onActive() {
            super.onActive()

            app.registerReceiver(tickReceiver, IntentFilter(Intent.ACTION_TIME_TICK))
            value = Date()
        }

        override fun onInactive() {
            super.onInactive()

            app.unregisterReceiver(tickReceiver)
        }
    }

    /** Battery level in % */
    val batteryLevel = object : LiveData<Int>() {
        private val batteryLevelReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                value = intent.getIntExtra("level", 0)
            }
        }

        override fun onActive() {
            super.onActive()

            app.registerReceiver(batteryLevelReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.let { batteryLevelReceiver.onReceive(app, it) }
        }

        override fun onInactive() {
            super.onInactive()

            app.unregisterReceiver(batteryLevelReceiver)
        }
    }

    companion object :
        SingleParameterSingletonOf<Application, PhoneStateRepository>({ PhoneStateRepository(it) })
}