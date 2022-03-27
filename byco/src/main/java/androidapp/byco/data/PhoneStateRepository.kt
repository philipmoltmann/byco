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
import android.net.wifi.WifiManager
import androidapp.byco.data.PhoneStateRepository.NetworkType.*
import androidapp.byco.util.SingleParameterSingletonOf
import androidx.lifecycle.LiveData
import java.util.*

/** Not riding related phone state */
class PhoneStateRepository private constructor(private val app: Application) {
    enum class NetworkType {
        UNMETERED,
        METERED,
        NO_NETWORK
    }

    /** Currently connected [NetworkType] */
    val networkType = object : LiveData<NetworkType>() {
        private val networkStateChangeIntent = IntentFilter().apply {
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }

        private val networkStateChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                update()
            }
        }

        init {
            value = NO_NETWORK
        }

        override fun onActive() {
            super.onActive()

            app.registerReceiver(networkStateChangeReceiver, networkStateChangeIntent)
            update()
        }

        override fun onInactive() {
            super.onInactive()

            app.unregisterReceiver(networkStateChangeReceiver)
        }

        private fun update() {
            val cm = app.getSystemService(ConnectivityManager::class.java)

            value = when {
                cm.activeNetworkInfo?.isConnected != true -> {
                    NO_NETWORK
                }
                cm.isActiveNetworkMetered -> {
                    METERED
                }
                else -> {
                    UNMETERED
                }
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