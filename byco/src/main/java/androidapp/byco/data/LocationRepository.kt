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

import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidapp.byco.util.*
import androidx.annotation.RequiresPermission
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.math.abs

/** Location related state */
class LocationRepository internal constructor(
    private val app: Application,
    private val LOCATION_UPDATE_INTERVAL: Long = 1000L, // ms
    private val MOVING_ACCURACY_REQUIRED: Float = 20F, // m
    private val SPEED_TO_STOP_MOVING: Float = 0.9F, // m/s (== 3 km/h)
    private val SPEED_TO_START_MOVING: Float = 1.5F, // m/s  (== 5 km/h)
    private val COUNTRY_CODE_UPDATE_INTERVAL: Long = SECONDS.toMillis(30)
) {
    /**
     * Does the current app have the location permission.
     *
     * Needs to be set when changed as there is no listener.
     */
    val hasLocationPermission =
        MutableLiveData(app.checkSelfPermission(ACCESS_FINE_LOCATION) == PERMISSION_GRANTED)

    /**
     * [Passive location](https://developer.android.com/reference/android/location/LocationManager#PASSIVE_PROVIDER)
     *
     * Returns value only if [hasLocationPermission] is `true`.
     */
    val passiveLocation = object : MediatorLiveData<Location>() {
        private var isLiveDataActive = false

        init {
            addSource(hasLocationPermission) { hasPermission ->
                if (hasPermission && isLiveDataActive) {
                    startUpdates()
                }
            }
        }

        val callback = LocationListener { location ->
            postValue(location)
        }

        @RequiresPermission(ACCESS_FINE_LOCATION)
        private fun startUpdates() {
            app.getSystemService(LocationManager::class.java).requestLocationUpdates(
                LocationManager.PASSIVE_PROVIDER,
                LOCATION_UPDATE_INTERVAL,
                100F,
                callback
            )
        }

        override fun onActive() {
            super.onActive()
            isLiveDataActive = true

            @SuppressLint("MissingPermission")
            if (hasLocationPermission.value == true) {
                startUpdates()
            }
        }

        override fun onInactive() {
            super.onInactive()
            isLiveDataActive = false

            app.getSystemService(LocationManager::class.java).removeUpdates(callback)
        }
    }

    /**
     * Current location
     *
     * Returns value only if [hasLocationPermission] is `true`.
     */
    val location = object : CoroutineAwareLiveData<Location>() {
        private var isLiveDataActive = false

        @SuppressLint("StaticFieldLeak")
        private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(app)

        init {
            addSource(hasLocationPermission) { hasPermission ->
                if (hasPermission && isLiveDataActive) {
                    startUpdates()
                }
            }
        }

        val callback = object : LocationCallback() {
            private var lastBearing = 0f

            override fun onLocationResult(location: LocationResult) {
                if (abs(
                        SystemClock.elapsedRealtimeNanos()
                                - location.lastLocation.elapsedRealtimeNanos
                    ) > MILLISECONDS.toNanos(LOCATION_UPDATE_INTERVAL * 4)
                ) {
                    return
                }

                liveDataScope.launch(Main.immediate) {
                    // Update isMoving here instead making isMoving a MediatorLiveData to guarantee
                    // it being updated before any observer is called
                    isMoving.update(location.lastLocation)

                    value = location.lastLocation.apply {
                        // Don't change bearing if not moving
                        if (isMoving.value != true) {
                            bearing = lastBearing
                        } else {
                            lastBearing = bearing
                        }
                    }
                }
            }
        }

        @RequiresPermission(ACCESS_FINE_LOCATION)
        private fun startUpdates() {
            // Post one location without any restrictions to init live-data. Also don't update
            // isMoving as this might be quite old or inaccurate
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                location?.let { postValue(it) }
            }

            fusedLocationClient.requestLocationUpdates(
                LocationRequest.create().apply {
                    interval = LOCATION_UPDATE_INTERVAL
                    fastestInterval = LOCATION_UPDATE_INTERVAL
                    isWaitForAccurateLocation = true
                    priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                }, callback, Looper.getMainLooper()
            )
        }

        override fun onActive() {
            super.onActive()
            isLiveDataActive = true

            @SuppressLint("MissingPermission")
            if (hasLocationPermission.value == true) {
                startUpdates()
            }
        }

        override fun onInactive() {
            super.onInactive()
            isLiveDataActive = false

            fusedLocationClient.removeLocationUpdates(callback)
        }
    } as LiveData<Location>

    /**
     * Country code of current [location]
     */
    val countryCode = object : AsyncLiveData<CountryCode>(IO) {
        private var lastUpdate = 0L

        init {
            value = getCountryCode(app, null)

            requestUpdateIfChanged(passiveLocation)
        }

        override suspend fun update(): CountryCode {
            val now = System.currentTimeMillis()

            return if (now - lastUpdate > COUNTRY_CODE_UPDATE_INTERVAL) {
                lastUpdate = now
                val newCC = getCountryCode(app, passiveLocation.value)

                // Don't update CC each time passiveLocation changes as this might trigger unnecessary
                // downstream updates
                if (newCC != value) {
                    newCC
                } else {
                    null
                }
            } else {
                null
            }
        }
    }

    /**
     * Whether the current country (not current locale) uses miles (instead of kilometers).
     *
     * This returns what is used commonly in the current country. The user might have set a locale
     * that uses different units.
     *
     * @see isLocaleUsingMiles
     */
    val isUsingMiles = object : MediatorLiveData<Boolean>() {
        init {
            addSource(countryCode) {
                value = isCountryUsingMiles(it)
            }
        }
    }

    /**
     * Track if phone is moving to avoid weird bearing values when standing still.
     */
    inner class IsMovingLiveData : LiveData<Boolean>() {
        init {
            value = false
        }

        fun update(newLocation: Location) {
            if (newLocation.speed < SPEED_TO_STOP_MOVING) {
                value = false
            } else if ((value == true || newLocation.accuracy < MOVING_ACCURACY_REQUIRED)
                && newLocation.speed > SPEED_TO_START_MOVING
            ) {
                value = true
            }
        }
    }

    /**
     * Whether the phone is moving at the current moment. This filters out jitters and jumps during
     * initialization
     */
    val isMoving = IsMovingLiveData()

    /**
     * Bearing smoothed over the very recent movement.
     */
    val smoothedBearing = object : MediatorLiveData<Float>() {
        private val SMOOTH_DISTANCE = 15f // m

        /** Recent locations with most recent location first */
        private var recentLocations = listOf<Location>()

        init {
            value = 0f

            addSource(location + isMoving) { (location, isMoving) ->
                location ?: return@addSource
                if (isMoving != true) {
                    return@addSource
                }

                var nextLoc = location
                var recentDistance = 0f
                recentLocations = recentLocations.mapNotNullTo(mutableListOf(nextLoc)) { prevLoc ->
                    if (recentDistance < SMOOTH_DISTANCE) {
                        prevLoc
                    } else {
                        null
                    }.also {
                        if (nextLoc != null) {
                            recentDistance += prevLoc.distanceTo(nextLoc)
                        }
                        nextLoc = prevLoc
                    }
                }

                if (recentDistance >= SMOOTH_DISTANCE) {
                    value = recentLocations.last().bearingTo(recentLocations.first())
                }
            }
        }
    }

    /** Speed */
    val speed = Transformations.map(location) { Speed(location.value?.speed ?: 0F) }

    companion object :
        SingleParameterSingletonOf<Application, LocationRepository>({ LocationRepository(it) })
}