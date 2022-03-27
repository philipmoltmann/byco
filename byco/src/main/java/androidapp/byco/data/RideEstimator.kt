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
import androidapp.byco.util.DontUpdateIfUnchangedLiveData
import androidapp.byco.util.addSources
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lib.gpx.BasicLocation
import lib.gpx.Duration
import lib.gpx.MapArea
import lib.gpx.toBasicLocation
import java.util.concurrent.TimeUnit.MINUTES
import kotlin.math.max
import kotlin.math.min

/**
 * Utility class that estimates if a ride is ongoing.
 *
 * A ride is considered ongoing if the rider looked at the `RidingActivity` open for a while while
 * moving for some not insignificant distance.
 */
class RideEstimator(
    app: Application,
    private val state: SavedStateHandle,
    private val coroutineScope: CoroutineScope,
    private val TIME_STARTED_BEFORE_STARTING_RIDE: Long = MINUTES.toMillis(1),
    private val TIME_STOPPED_BEFORE_STOPPING_RIDE: Long = MINUTES.toMillis(10),
    private val MIN_RIDE_DISTANCE_BEFORE_STARTING_RIDE: Float = 100f // m
) {
    private val STATE_KEY_EST_RIDE_DURATION_RIDE_START_TIME = "ride_estimator.ride_start_time"
    private val STATE_KEY_EST_ONGOING_RIDE_PAUSE_TIME = "ride_estimator.ride_pause_time"
    private val STATE_KEY_EST_ONGOING_RIDE_AREA = "ride_estimator.ride_area"
    private val STATE_KEY_EST_ONGOING_RIDE_START_LOCATION = "est_ride_duration.ride_start_location"

    private val NOT_STARTED = Long.MAX_VALUE
    private val NOT_PAUSED = Long.MAX_VALUE

    private var delayedUpdate: Job? = null

    // When the activity was started
    private var startedSince = object : DontUpdateIfUnchangedLiveData<Long>() {
        init {
            value = NOT_STARTED
        }
    }
    private val currentLocation = LocationRepository[app].location
    private val isRideBeingRecorded = RideRecordingRepository[app].isRideBeingRecorded
    private var ridePauseTime = state.getLiveData(STATE_KEY_EST_ONGOING_RIDE_PAUSE_TIME, NOT_PAUSED)
    private var rideArea = state.getLiveData<MapArea>(STATE_KEY_EST_ONGOING_RIDE_AREA)

    /** Used to signal from the riding activity that it is visible */
    private var isActivityStarted = false

    /**
     * Whether the `RidingActivity` is started is an input to the ride estimation logic, hence
     * this activity needs to call this method `onStop`.
     */
    fun onActivityStopped() {
        isActivityStarted = false
        ridePauseTime.value = min(ridePauseTime.value!!, System.currentTimeMillis())
        update()

        instance.value = null
    }

    /**
     * Whether the `RidingActivity` is started is an input to the ride estimation logic, hence
     * this activity needs to call this method `onStart`.
     */
    fun onActivityStarted() {
        isActivityStarted = true
        update()

        assert(instance.value == null)
        instance.value = this
    }

    fun update() {
        // Don't estimate rides while a user triggered ride recording is ongoing
        if (isRideBeingRecorded.value == true) {
            if (isOngoing.value == true) {
                clear()
            }
            return
        }

        // After activity was paused we might not have gotten updates, hence force one with
        // activity not started as this might cancel the current estimated ride
        if (isActivityStarted && ridePauseTime.value != NOT_PAUSED) {
            updateInt(false)
            updateInt(true)
        } else {
            updateInt(isActivityStarted)
        }
    }

    private fun updateInt(isActivityStarted: Boolean) {
        val now = System.currentTimeMillis()
        val loc = currentLocation.value ?: return

        // update state
        if (isActivityStarted) {
            startedSince.value = min(startedSince.value!!, now)
            ridePauseTime.value = NOT_PAUSED

            if (loc.accuracy > MIN_RIDE_DISTANCE_BEFORE_STARTING_RIDE / 4) {
                // no accurate location, rideArea would be too imprecise
                return
            }

            if (startLocationInt.value == null) {
                startLocationInt.value = loc.toBasicLocation()
            }

            if (rideArea.value == null) {
                rideArea.value =
                    MapArea(loc.latitude, loc.longitude, loc.latitude, loc.longitude)
            } else {
                rideArea.value = MapArea(
                    min(loc.latitude, rideArea.value!!.minLatD),
                    min(loc.longitude, rideArea.value!!.minLonD),
                    max(loc.latitude, rideArea.value!!.maxLatD),
                    max(loc.longitude, rideArea.value!!.maxLonD)
                )
            }
        } else {
            startedSince.value = NOT_STARTED
        }

        // Compute if estimated ride is ongoing
        if (isActivityStarted) {
            if (rideArea.value!!.min.distanceTo(rideArea.value!!.max)
                > MIN_RIDE_DISTANCE_BEFORE_STARTING_RIDE
            ) {
                if (now - startedSince.value!! > TIME_STARTED_BEFORE_STARTING_RIDE) {
                    isOngoing.value = true
                } else {
                    delayedUpdate?.cancel()

                    delayedUpdate = coroutineScope.launch(Main.immediate) {
                        delay(TIME_STARTED_BEFORE_STARTING_RIDE - (now - startedSince.value!!))
                        update()
                    }
                }
            }
        } else {
            if (now - ridePauseTime.value!! > TIME_STOPPED_BEFORE_STOPPING_RIDE) {
                rideArea.value = null
                startLocationInt.value = null
                isOngoing.value = false
            } else {
                delayedUpdate?.cancel()

                delayedUpdate = coroutineScope.launch(Main.immediate) {
                    delay(TIME_STOPPED_BEFORE_STOPPING_RIDE - (now - ridePauseTime.value!!))
                    update()
                }
            }
        }
    }

    /**
     * If an estimated ride is ongoing. Estimated rides are started after the moves while the riding
     * activity is open.
     */
    val isOngoing = object : MediatorLiveData<Boolean>() {
        init {
            addSources(currentLocation, PhoneStateRepository[app].date) {
                update()
            }
        }
    }

    /** @see [rideStart] */
    private val startLocationInt =
        state.getLiveData<BasicLocation>(STATE_KEY_EST_ONGOING_RIDE_START_LOCATION)

    /** Location where the estimated ride started */
    val rideStart = object : MediatorLiveData<BasicLocation>() {
        init {
            addSources(startLocationInt, isOngoing) {
                val newValue = if (isOngoing.value == true) {
                    startLocationInt.value
                } else {
                    null
                }

                if (value != newValue) {
                    value = newValue
                }
            }
        }
    }

    /** Duration of estimated ride */
    val duration = object : MediatorLiveData<Duration?>() {
        private var startTime: Long?
            get() = state[STATE_KEY_EST_RIDE_DURATION_RIDE_START_TIME]
            set(v) {
                state[STATE_KEY_EST_RIDE_DURATION_RIDE_START_TIME] = v
            }

        init {
            addSources(PhoneStateRepository[app].date, isOngoing) { update() }
        }

        private fun update() {
            if (isOngoing.value == true) {
                val now = System.currentTimeMillis()

                if (startTime == null) {
                    startTime = now
                }
                value = Duration(now - startTime!!)
            } else {
                value = null
                startTime = null
            }
        }
    }

    /** Clear currently estimate ride */
    fun clear() {
        ridePauseTime.value = NOT_PAUSED
        rideArea.value = null
        startLocationInt.value = null
        startedSince.value = NOT_STARTED
        isOngoing.value = false
    }

    companion object {
        // Instance is valid is a [RideEstimator] exists
        var instance = MutableLiveData<RideEstimator?>()
    }
}