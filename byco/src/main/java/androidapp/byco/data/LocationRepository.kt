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
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidapp.byco.BycoApplication
import androidapp.byco.util.*
import com.google.android.gms.location.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import lib.gpx.BasicLocation
import lib.gpx.toBasicLocation
import java.util.concurrent.TimeUnit.*
import kotlin.math.abs

/** Location related state */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class LocationRepository internal constructor(
    private val app: BycoApplication,
    private val LOCATION_UPDATE_INTERVAL: Long = 1000L, // ms
    private val MOVING_ACCURACY_REQUIRED: Float = 20F, // m
    private val SPEED_TO_STOP_MOVING: Float = 0.9F, // m/s (== 3 km/h)
    private val SPEED_TO_START_MOVING: Float = 1.5F, // m/s  (== 5 km/h)
    COUNTRY_CODE_UPDATE_INTERVAL: Long = SECONDS.toMillis(30),
    private val PASSIVE_LOCATION_ACCURACY_PREFERRED: Float = 100F, // m
    private val PASSIVE_LOCATION_INACCURATE_DELAY: Long = MINUTES.toMillis(10),
    private val PASSIVE_LOCATION_INACCURATE_INTERVAL: Long = MINUTES.toMillis(5)
) : Repository(app) {
    val updateHasLocationPermissionTrigger = Trigger(repositoryScope)

    /**
     * Does the current app have the location permission.
     *
     * Trigger [updateHasLocationPermissionTrigger] when changed.
     */
    val hasLocationPermission = updateHasLocationPermissionTrigger.flow.map {
        app.checkSelfPermission(ACCESS_FINE_LOCATION) == PERMISSION_GRANTED
    }.stateIn(app.checkSelfPermission(ACCESS_FINE_LOCATION) == PERMISSION_GRANTED)

    private data class LocationUpdate(val accuracy: Float, val time: Long)

    /**
     * [Passive location](https://developer.android.com/reference/android/location/LocationManager#PASSIVE_PROVIDER)
     *
     * Returns value only if [hasLocationPermission] is `true`.
     */
    @SuppressLint("MissingPermission")
    val passiveLocation = hasLocationPermission.flatMapLatest { hasLocationPermission ->
        if (!hasLocationPermission) {
            flowOf(null)
        } else {
            callbackFlow<Location?> {
                val recentUpdates = mutableMapOf<String, LocationUpdate>()

                val callback = LocationListener { location ->
                    if (location.provider == null || !location.hasAccuracy()) {
                        return@LocationListener
                    }

                    val now = SystemClock.elapsedRealtime()

                    if (location.accuracy > PASSIVE_LOCATION_ACCURACY_PREFERRED) {
                        if (recentUpdates.values.find { locationUpdate ->
                                locationUpdate.accuracy <= PASSIVE_LOCATION_ACCURACY_PREFERRED
                                        && now - locationUpdate.time < PASSIVE_LOCATION_INACCURATE_DELAY
                            } != null) {
                            // Ignore this update, we recently had a more precise one
                            return@LocationListener
                        }

                        if (recentUpdates.values.find { locationUpdate ->
                                now - locationUpdate.time < PASSIVE_LOCATION_INACCURATE_INTERVAL
                            } != null) {
                            // Ignore this update, we recently posted a similarly imprecise one
                            return@LocationListener
                        }
                    }

                    recentUpdates[location.provider!!] = LocationUpdate(location.accuracy, now)

                    launch {
                        send(location)
                    }
                }

                app.getSystemService(LocationManager::class.java).requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER,
                    LOCATION_UPDATE_INTERVAL,
                    PASSIVE_LOCATION_ACCURACY_PREFERRED,
                    callback,
                    Looper.getMainLooper()
                )

                awaitClose {
                    app.getSystemService(LocationManager::class.java).removeUpdates(callback)
                }
            }
        }
    }.stateIn(null)

    /**
     * Current location
     *
     * Returns value only if [hasLocationPermission] is `true`.
     *
     * You probably want to use [smoothedLocation] to avoid jitter, spinning compasses, etc...
     */
    @SuppressLint("MissingPermission")
    val rawLocation = hasLocationPermission.flatMapLatest { hasLocationPermission ->
        if (!hasLocationPermission) {
            flowOf(null)
        } else {
            callbackFlow<Location> {
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(app)

                val callback = object : LocationCallback() {
                    override fun onLocationResult(location: LocationResult) {
                        val lastLocation = location.lastLocation ?: return

                        if (abs(
                                SystemClock.elapsedRealtimeNanos()
                                        - lastLocation.elapsedRealtimeNanos
                            ) > MILLISECONDS.toNanos(LOCATION_UPDATE_INTERVAL * 4)
                        ) {
                            return
                        }

                        launch {
                            send(lastLocation)
                        }
                    }
                }

                // Post one location without any restrictions to init live-data. Also don't update
                // isMoving as this might be quite old or inaccurate
                fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                    location?.let { launch { send(it) } }
                }

                fusedLocationClient.requestLocationUpdates(
                    LocationRequest.Builder(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        LOCATION_UPDATE_INTERVAL
                    )
                        .setMinUpdateIntervalMillis(LOCATION_UPDATE_INTERVAL)
                        .setWaitForAccurateLocation(true)
                        .build(), callback, Looper.getMainLooper()
                )

                awaitClose {
                    fusedLocationClient.removeLocationUpdates(callback)
                }
            }
        }
    }.stateIn(null)

    /**
     * Country code of current [passiveLocation]
     */
    val countryCode = passiveLocation.filterNotNull()
        .sample(COUNTRY_CODE_UPDATE_INTERVAL)
        .flatMapLatest {
            getCountryCode(app, passiveLocation.value)
        }.stateIn(getCountryCodeFromLocale(app))

    /**
     * Whether the current country (not current locale) uses miles (instead of kilometers).
     *
     * This returns what is used commonly in the current country. The user might have set a locale
     * that uses different units.
     *
     * @see isLocaleUsingMiles
     */
    val isUsingMiles = countryCode.map { isCountryUsingMiles(it) }.stateIn(false)

    data class SmoothedLocation(
        val location: BasicLocation,
        val accuracy: Float,
        val isMoving: Boolean,
        val speed: Float,
        val smoothedBearing: Float
    ) {
        fun toLocation() = Location("from smoothed").apply {
            latitude = location.latitude
            longitude = location.longitude
            accuracy = this@SmoothedLocation.accuracy
            speed = this@SmoothedLocation.speed
            bearing = smoothedBearing
        }
    }

    /**
     * Current location. This filters out jitters during initialization and standing still.
     */
    val smoothedLocation = flow {
        val SMOOTH_DISTANCE = 15f // m

        /** Recent locations with most recent location first */
        val recentLocations = mutableListOf<BasicLocation>()

        var isMoving = false
        var smoothedBearing: Float? = null

        rawLocation.collect { newLocation ->
            if (newLocation == null) {
                emit(null)
                return@collect
            }

            val basicNewLocation = newLocation.toBasicLocation()

            val wasMoving = isMoving
            isMoving =
                if (newLocation.speed < SPEED_TO_STOP_MOVING) {
                    false
                } else if ((wasMoving || newLocation.accuracy < MOVING_ACCURACY_REQUIRED)
                    && newLocation.speed > SPEED_TO_START_MOVING
                ) {
                    true
                } else {
                    wasMoving
                }

            fun recentDistance(): Float {
                var recentDistance = 0f

                ((recentLocations.size - 1) downTo 1).forEach { i ->
                    recentDistance += recentLocations[i - 1].distanceTo(recentLocations[i])
                }

                return recentDistance
            }

            // Keep recent locations with a path length (aka. recentDistance) of just over
            // SMOOTH_DISTANCE, but not more.
            if (isMoving && recentLocations.lastOrNull()?.distanceTo(newLocation)
                    ?.let { it > SMOOTH_DISTANCE / 10 } != false
            ) {
                recentLocations.add(basicNewLocation)

                var recentDistanceLeft = recentDistance()

                while (recentLocations.size >= 2) {
                    val removeDistance = recentLocations[0].distanceTo(recentLocations[1])
                    if (recentDistanceLeft - removeDistance < SMOOTH_DISTANCE) {
                        break
                    }

                    recentLocations.removeAt(0)
                    recentDistanceLeft -= removeDistance
                }
            }

            smoothedBearing = if (recentDistance() >= SMOOTH_DISTANCE) {
                // Don't use the reported bearing. It tends to just spin around, hence rather
                // just use the bearing from the recent path.
                recentLocations.first().bearingTo(recentLocations.last())
            } else if (smoothedBearing != null) {
                // No recent path, at least avoid changing the bearing.
                smoothedBearing!!
            } else {
                newLocation.bearing
            }

            emit(
                SmoothedLocation(
                    basicNewLocation,
                    newLocation.accuracy,
                    isMoving,
                    newLocation.speed,
                    smoothedBearing!!
                )
            )
        }
    }.stateIn(null)

    /** Speed */
    val speed = smoothedLocation.map { location ->
        if (location != null && !location.speed.isNaN()) {
            Speed(location.speed)
        } else {
            null
        }
    }.stateIn(Speed(0f))

    companion object :
        SingleParameterSingletonOf<BycoApplication, LocationRepository>({ LocationRepository(it) })
}