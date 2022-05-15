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

import androidapp.byco.BycoApplication
import androidapp.byco.util.SingleParameterSingletonOf
import androidapp.byco.util.Trigger
import androidapp.byco.util.plus
import androidapp.byco.util.stateIn
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lib.gpx.BasicLocation
import lib.gpx.Duration
import lib.gpx.MapArea
import java.util.concurrent.TimeUnit.MINUTES
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.math.max
import kotlin.math.min

/**
 * Utility class that estimates if a ride is ongoing.
 *
 * A ride is considered ongoing if the rider looked at the `RidingActivity` open for a while while
 * moving for some not insignificant distance.
 */
class RideEstimator(
    app: BycoApplication,
    private val state: SavedStateHandle,
    coroutineScope: CoroutineScope,
    private val TIME_STARTED_BEFORE_STARTING_RIDE: Long = SECONDS.toMillis(10),
    private val TIME_STOPPED_BEFORE_STOPPING_RIDE: Long = MINUTES.toMillis(10),
    private val MIN_RIDE_DISTANCE_BEFORE_STARTING_RIDE: Float = 100f // m
) {
    // All [state] is synchronized by [stateLock]
    private val STATE_KEY_EST_ACTIVITY_PAUSE_TIME = "ride_estimator.activity_pause_time"
    private val STATE_KEY_EST_IS_ONGOING = "ride_estimator.is_ongoing"
    private val STATE_KEY_EST_RIDE_AREA = "ride_estimator.ride_area"
    private val STATE_KEY_EST_RIDE_START_TIME = "ride_estimator.ride_start_time"
    private val STATE_KEY_EST_RIDE_START_LOCATION = "est_ride_duration.ride_start_location"

    private val NOT_STARTED = Long.MAX_VALUE
    private val NOT_PAUSED = Long.MAX_VALUE

    private val stateLock = Mutex()

    init {
        coroutineScope.launch {
            updateInstance.send(this@RideEstimator)
        }
    }

    /** Force an update to [isOngoing]. */
    private val isOngoingUpdateTrigger = Trigger(coroutineScope)

    private var activityPauseTimeState: Long
        get() {
            return state[STATE_KEY_EST_ACTIVITY_PAUSE_TIME] ?: NOT_PAUSED
        }
        set(v) {
            state[STATE_KEY_EST_ACTIVITY_PAUSE_TIME] = v
        }
    private var isOngoingState: Boolean
        get() {
            return state[STATE_KEY_EST_IS_ONGOING] ?: false
        }
        set(v) {
            state[STATE_KEY_EST_IS_ONGOING] = v
        }
    private var rideAreaState: MapArea?
        get() {
            return state[STATE_KEY_EST_RIDE_AREA]
        }
        set(v) {
            state[STATE_KEY_EST_RIDE_AREA] = v
        }
    private var rideStartTimeState: Long
        get() {
            return state[STATE_KEY_EST_RIDE_START_TIME] ?: NOT_STARTED
        }
        set(v) {
            state[STATE_KEY_EST_RIDE_START_TIME] = v
        }
    private var rideStartLocation: BasicLocation?
        get() {
            return state[STATE_KEY_EST_RIDE_START_LOCATION]
        }
        set(v) {
            state[STATE_KEY_EST_RIDE_START_LOCATION] = v
        }

    /**
     * Whether the `RidingActivity` is started is an input to the ride estimation logic, hence
     * this activity needs to call this method `onStop`.
     */
    suspend fun onActivityStopped() {
        stateLock.withLock {
            activityPauseTimeState = System.currentTimeMillis()
        }
    }

    /**
     * Whether the `RidingActivity` is started is an input to the ride estimation logic, hence
     * this activity needs to call this method `onStart`.
     */
    suspend fun onActivityStarted() {
        stateLock.withLock {
            // If this is a restart after being stopped, clear the ride estimation.
            if (System.currentTimeMillis() - activityPauseTimeState > TIME_STOPPED_BEFORE_STOPPING_RIDE) {
                clearLocked()
            }

            activityPauseTimeState = NOT_PAUSED
        }
    }

    /**
     * If an estimated ride is ongoing. Estimated rides are started after the moves while the riding
     * activity is open.
     */
    val isOngoing = (RideRecordingRepository[app].isRideBeingRecorded
            + state.getStateFlow(STATE_KEY_EST_ACTIVITY_PAUSE_TIME, NOT_PAUSED)
            + LocationRepository[app].smoothedLocation
            + PhoneStateRepository[app].date
            + isOngoingUpdateTrigger.flow).map { (isRideBeingRecorded, activityPausedTime, location, _, _) ->
        stateLock.withLock {
            val now = System.currentTimeMillis()

            // Should ride estimation be stopped?
            if (isRideBeingRecorded) {
                // Don't cause loops where [clearLocked] wakes up this flow again.
                if (isOngoingState) {
                    clearLocked()
                }

                return@map false
            }

            // Is ride estimation already running?
            if (isOngoingState) {
                return@map true
            }

            if (activityPausedTime == NOT_PAUSED) {
                // Don't estimate ride if location is no good.
                if (location == null || location.accuracy > MIN_RIDE_DISTANCE_BEFORE_STARTING_RIDE / 4) {
                    // no accurate location, rideArea would be too imprecise
                    return@map false
                }

                // Check how far the device moved since waiting for estimated ride to start.
                rideAreaState = rideAreaState?.let { rideArea ->
                    MapArea(
                        min(location.location.latitude, rideArea.minLatD),
                        min(location.location.longitude, rideArea.minLonD),
                        max(location.location.latitude, rideArea.maxLatD),
                        max(location.location.longitude, rideArea.maxLonD)
                    )
                } ?: MapArea(
                    location.location.latitude,
                    location.location.longitude,
                    location.location.latitude,
                    location.location.longitude
                )

                rideAreaState?.let { rideArea ->
                    if (rideArea.min.distanceTo(rideArea.max) > MIN_RIDE_DISTANCE_BEFORE_STARTING_RIDE) {
                        // How a ride probably has started. Hence remember location and time of this
                        // first time this happened.
                        if (rideStartLocation == null) {
                            rideStartLocation = location.location
                        }
                        rideStartTimeState = min(rideStartTimeState, now)

                        // Wait for the ride to probably have started before actually showing this
                        // to the user.
                        if (now - rideStartTimeState > TIME_STARTED_BEFORE_STARTING_RIDE) {
                            isOngoingState = true
                        }
                    }
                }
            }

            isOngoingState
        }
    }.stateIn(coroutineScope, false)

    /** Location where the estimated ride started */
    val rideStart = (state.getStateFlow<BasicLocation?>(
        STATE_KEY_EST_RIDE_START_LOCATION,
        null
    ) + isOngoing).map { (startLocation, isOngoing) ->
        if (isOngoing) {
            startLocation
        } else {
            null
        }
    }.stateIn(coroutineScope, null)

    /** Duration of estimated ride */
    val duration = (PhoneStateRepository[app].date + state.getStateFlow<Long?>(
        STATE_KEY_EST_RIDE_START_TIME,
        null
    ) + isOngoing).map { (_, startTime, isOngoing) ->
        startTime?.let {
            if (isOngoing && startTime != NOT_STARTED) {
                Duration(System.currentTimeMillis() - it)
            } else {
                null
            }
        }
    }.stateIn(coroutineScope, null)

    /** Clear currently estimate ride */
    suspend fun clear() {
        stateLock.withLock {
            clearLocked()
        }
    }

    private fun clearLocked() {
        assert(stateLock.isLocked)

        isOngoingState = false
        rideAreaState = null
        rideStartTimeState = NOT_STARTED
        rideStartLocation = null

        isOngoingUpdateTrigger.trigger()
    }

    companion object {
        private val updateInstance = Channel<RideEstimator?>(1, BufferOverflow.DROP_OLDEST)

        var instance = SingleParameterSingletonOf<BycoApplication, Flow<RideEstimator?>> { app ->
            updateInstance.receiveAsFlow().stateIn(app.appScope, null)
        }
    }
}