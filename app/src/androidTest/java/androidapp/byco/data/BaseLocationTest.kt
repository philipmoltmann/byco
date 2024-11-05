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

import android.app.PendingIntent
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidapp.byco.ui.RidingActivity
import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.rule.GrantPermissionRule
import com.google.android.gms.common.api.Api
import com.google.android.gms.common.api.internal.ApiKey
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.DeviceOrientationListener
import com.google.android.gms.location.DeviceOrientationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LastLocationRequest
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationListener
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.tasks.CancellationToken
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import java.util.concurrent.Executor
import java.util.concurrent.Executors

open class BaseLocationTest : BaseTest() {
    @get:Rule
    val activity = activityScenarioRule<RidingActivity>()

    @get:Rule
    val grantPermission =
        GrantPermissionRule.grant(android.Manifest.permission.ACCESS_FINE_LOCATION)

    private val testLocations = MutableStateFlow<Location?>(null)
    private val testLocationListeners = mutableListOf<Pair<Looper, LocationCallback>>()
    private val testLocationClient = object : FusedLocationProviderClient {
        override fun getApiKey(): ApiKey<Api.ApiOptions.NoOptions> {
            TODO("Not yet implemented")
        }

        override fun getLastLocation(): Task<Location> {
            val taskCompletionSource = TaskCompletionSource<Location>()

            Executors.newSingleThreadExecutor().execute {
                runBlocking {
                    taskCompletionSource.setResult(testLocations.filterNotNull().first())
                }
            }

            return taskCompletionSource.task
        }

        override fun getLastLocation(p0: LastLocationRequest): Task<Location> {
            TODO("Not yet implemented")
        }

        override fun getCurrentLocation(p0: Int, p1: CancellationToken?): Task<Location> {
            TODO("Not yet implemented")
        }

        override fun getCurrentLocation(
            p0: CurrentLocationRequest,
            p1: CancellationToken?
        ): Task<Location> {
            TODO("Not yet implemented")
        }

        override fun getLocationAvailability(): Task<LocationAvailability> {
            TODO("Not yet implemented")
        }

        override fun requestLocationUpdates(
            p0: LocationRequest,
            p1: Executor,
            p2: LocationListener
        ): Task<Void> {
            TODO("Not yet implemented")
        }

        override fun requestLocationUpdates(
            p0: LocationRequest,
            p1: LocationListener,
            p2: Looper?
        ): Task<Void> {
            TODO("Not yet implemented")
        }

        override fun requestLocationUpdates(
            p0: LocationRequest,
            p1: LocationCallback,
            p2: Looper?
        ): Task<Void> {
            testLocationListeners.add(p2!! to p1)
            return Tasks.forResult(null)
        }

        override fun requestLocationUpdates(
            p0: LocationRequest,
            p1: Executor,
            p2: LocationCallback
        ): Task<Void> {
            TODO("Not yet implemented")
        }

        override fun requestLocationUpdates(p0: LocationRequest, p1: PendingIntent): Task<Void> {
            TODO("Not yet implemented")
        }

        override fun removeLocationUpdates(p0: LocationListener): Task<Void> {
            TODO("Not yet implemented")
        }

        override fun removeLocationUpdates(p0: LocationCallback): Task<Void> {
            testLocationListeners.removeIf { it.second == p0 }
            return Tasks.forResult(null)
        }

        override fun removeLocationUpdates(p0: PendingIntent): Task<Void> {
            TODO("Not yet implemented")
        }

        override fun flushLocations(): Task<Void> {
            TODO("Not yet implemented")
        }

        override fun setMockMode(p0: Boolean): Task<Void> {
            TODO("Not yet implemented")
        }

        override fun setMockLocation(p0: Location): Task<Void> {
            TODO("Not yet implemented")
        }

        @Deprecated("Deprecated in Java")
        override fun requestDeviceOrientationUpdates(
            p0: DeviceOrientationRequest,
            p1: Executor,
            p2: DeviceOrientationListener
        ): Task<Void> {
            TODO("Not yet implemented")
        }

        @Deprecated("Deprecated in Java")
        override fun requestDeviceOrientationUpdates(
            p0: DeviceOrientationRequest,
            p1: DeviceOrientationListener,
            p2: Looper?
        ): Task<Void> {
            TODO("Not yet implemented")
        }

        @Deprecated("Deprecated in Java")
        override fun removeDeviceOrientationUpdates(p0: DeviceOrientationListener): Task<Void> {
            TODO("Not yet implemented")
        }
    }
    val locationRepo = LocationRepository(app, testLocationClient)

    @Before
    fun sendTestLocationsToLocationClient() {
        testScope.launch {
            testLocations.filterNotNull().collect { loc ->
                testLocationListeners.forEach {
                    Handler(it.first).post {
                        it.second.onLocationResult(LocationResult.create(listOf(loc)))
                    }
                }
            }
        }
    }

    fun setTestLocation(
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        accuracy: Float = 1F,
        time: Long = System.currentTimeMillis(),
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
        bearing: Float = 0F,
        bearingAccuracyDegrees: Float = 0F,
        speed: Float = 0F,
        speedAccuracyMetersPerSecond: Float = 0f
    ) {
        testLocations.tryEmit(
            Location("test").also {
                it.latitude = latitude
                it.longitude = longitude
                it.accuracy = accuracy
                it.time = time
                it.elapsedRealtimeNanos = elapsedRealtimeNanos
                it.bearing = bearing
                it.bearingAccuracyDegrees = bearingAccuracyDegrees
                it.speed = speed
                it.speedAccuracyMetersPerSecond = speedAccuracyMetersPerSecond
            })
    }
}