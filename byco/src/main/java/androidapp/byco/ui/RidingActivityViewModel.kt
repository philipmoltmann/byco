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

package androidapp.byco.ui

import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.app.Activity
import android.app.Activity.RESULT_OK
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.widget.Toast
import androidapp.byco.data.ElevationDataRepository
import androidapp.byco.data.LocationRepository
import androidapp.byco.data.MapData
import androidapp.byco.data.MapDataRepository
import androidapp.byco.data.PhoneStateRepository
import androidapp.byco.data.PreviousRide
import androidapp.byco.data.PreviousRidesRepository
import androidapp.byco.data.RideEstimator
import androidapp.byco.data.RideRecordingRepository
import androidapp.byco.data.RouteFinderRepository
import androidapp.byco.data.restrictTo
import androidapp.byco.data.writePreviousRide
import androidapp.byco.lib.R
import androidapp.byco.ui.views.ElevationView
import androidapp.byco.util.BycoViewModel
import androidapp.byco.util.compat.getApplicationInfoCompat
import androidapp.byco.util.compat.getParcelableArrayListExtraCompat
import androidapp.byco.util.plus
import androidapp.byco.util.stateIn
import androidapp.byco.util.whenNotChangedFor
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lib.gpx.BasicLocation
import lib.gpx.GPX_ZIP_FILE_EXTENSION
import lib.gpx.MapArea
import lib.gpx.RecordedLocation
import lib.gpx.Track
import lib.gpx.toBasicLocation
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.time.Duration.Companion.seconds

/** ViewModel for [RidingActivity] */
@OptIn(ExperimentalCoroutinesApi::class)
class RidingActivityViewModel(application: Application, val state: SavedStateHandle) :
    BycoViewModel(application) {
    private val KEY_PREVIOUS_RIDE_SHOWN = "KEY_DIRECTIONS_BACK"

    private val mapAreaUpdate = Channel<MapArea>()
    private val mapArea = mapAreaUpdate.receiveAsFlow().stateIn(null)
    private val animatedLocation = MutableStateFlow<Location?>(null)

    private lateinit var getLocationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var getDirectionsBackLauncher: ActivityResultLauncher<Intent>
    private lateinit var getPreviousRidesLauncher: ActivityResultLauncher<Intent>

    private val rideEstimator = RideEstimator(app, state, viewModelScope)

    /** The current date */
    val currentDate = PhoneStateRepository[app].date

    /** The current battery level */
    val batteryLevel = PhoneStateRepository[app].batteryLevel

    /** The current location */
    val smoothedLocation = LocationRepository[app].smoothedLocation

    /** The current speed */
    val speed = LocationRepository[app].speed

    /** Is there a previously recorded ride */
    val hasPreviousRide =
        PreviousRidesRepository[app].previousRidesAndDirectionsBack.map { !it.isNullOrEmpty() }
            .stateIn(false)

    /** Is the current country using miles and mph? */
    val isUsingMiles = LocationRepository[app].isUsingMiles

    /** Is the current country using miles and mph? */
    val countryCode = LocationRepository[app].countryCode

    /** Is a ride currently being recorded */
    val isRideBeingRecorded = RideRecordingRepository[app].isRideBeingRecorded

    /** Duration of the currently recorded ride */
    private val recordedRideDuration = RideRecordingRepository[app].rideTime

    /** [MapData] to show on map */
    private var hadData = false
    val mapData = mapArea.flatMapLatest { newMapArea ->
        if (newMapArea == null) {
            hadData = false
            flowOf(emptySet())
        } else {
            MapDataRepository[app].getMapData(newMapArea, !hadData).onEach {
                hadData = it.isNotEmpty()
            }
        }
    }.stateIn(emptySet())

    private val hasLocationPermission = LocationRepository[app].hasLocationPermission
    private val shouldShowLocationPermissionRationale = MutableStateFlow(false)

    val shouldShowLocationPermissionPrompt = hasLocationPermission.map { !it }.stateIn(false)

    /** Is currently a track (from a previous ride) shown? */
    val isShowingTrack = PreviousRidesRepository[app].visibleTrack.map { it != null }.stateIn(false)

    /** Is currently the directions back shown */
    val isShowingDirectionsBack =
        PreviousRidesRepository[app].rideShownOnMap.map { rideShownOnMap ->
            if (rideShownOnMap == null) {
                state.remove(KEY_PREVIOUS_RIDE_SHOWN)
            } else {
                state[KEY_PREVIOUS_RIDE_SHOWN] = rideShownOnMap.file.name
            }

            rideShownOnMap?.isDirectionsHome == true
        }.stateIn(false)

    /**
     * Track to be shown overlaid on map
     *
     * @see hideTrack
     */
    val visibleTrack =
        (PreviousRidesRepository[app].visibleTrack + mapArea
            .onStart { emit(null) }).map { (visibleTrack, mapArea) ->
            if (mapArea == null) {
                null
            } else {
                visibleTrack?.restrictTo(mapArea)
            }
        }.stateIn(null)

    /** The bearing to the track or `null` if it should not be shown. */
    val bearingToTrack =
        (animatedLocation + PreviousRidesRepository[app].closestLocationOnTrack).map { (location, closestLocationWrapper) ->
            val MIN_DISTANCE_TO_TRACK = 70f

            if (location == null || closestLocationWrapper == null) {
                null
            } else {
                val (_, closestLocationOnTrack) = closestLocationWrapper
                val currentLocation = location.toBasicLocation()

                if (currentLocation.distanceTo(closestLocationOnTrack) > MIN_DISTANCE_TO_TRACK) {
                    (currentLocation.bearingTo(closestLocationOnTrack) - location.bearing).let {
                        if (it <= 180) {
                            it
                        } else {
                            -180 + (it - 180)
                        }
                    }
                } else {
                    null
                }
            }
        }

    /**
     * Elevation profile of current climb
     */
    val currentClimbElevationProfile =
        ElevationDataRepository[app].currentClimbElevationProfile
            .whenNotChangedFor(5.seconds) { a, b ->
                a.second == b.second
            }.stateIn(0f to ElevationView.NO_DATA)

    /**
     * Meters left in current climb
     */
    val climbLeft = ElevationDataRepository[app].climbLeft

    /** Is an estimated ride ongoing */
    val isEstimatedRideOngoing = rideEstimator.isOngoing

    val rideDuration =
        (rideEstimator.duration + recordedRideDuration).map { (rideEstimatorDuration, recordedRideDuration) ->
            rideEstimatorDuration ?: recordedRideDuration
        }

    val rideStart = RouteFinderRepository[app].rideStart

    init {
        viewModelScope.launch {
            PreviousRidesRepository[app].previousRidesAndDirectionsBack.filterNotNull().first()
                .forEach { previousRide ->
                    if (previousRide.file.name == state[KEY_PREVIOUS_RIDE_SHOWN]) {
                        PreviousRidesRepository[app].showOnMap(previousRide)
                    } else if (previousRide.isDirectionsHome) {
                        // Clean up unused directions home.
                        PreviousRidesRepository[app].delete(previousRide)
                    }
                }
        }
    }

    /**
     * Set [boundaries] currently visible on map
     */
    fun setMapBoundaries(boundaries: MapArea) {
        viewModelScope.launch {
            mapAreaUpdate.send(boundaries)
        }
    }

    fun setAnimatedLocation(location: Location) {
        viewModelScope.launch {
            animatedLocation.emit(location)
        }
    }

    /**
     * Show list of previous rides in new activity
     *
     * @param activity current activity
     */
    fun showPreviousRides(activity: Activity) {
        getPreviousRidesLauncher.launch(Intent(activity, PreviousRidesActivity::class.java))
    }

    /**
     * Show help
     */
    fun showHelp(activity: Activity) {
        activity.startActivity(
            Intent(
                Intent.ACTION_VIEW, Uri.parse(
                    app.packageManager.getApplicationInfoCompat(
                        app.packageName, PackageManager.GET_META_DATA
                    ).metaData.getString("help_url")
                )
            )
        )
    }

    /**
     * Show about activity
     */
    fun showAbout(activity: Activity) {
        activity.startActivity(Intent(activity, AboutActivity::class.java))
    }

    /**
     * Stop recording of ride
     */
    fun stopRideRecording() {
        RideRecordingRepository[app].stopRecording()

        // Also don't immediately start an estimated ride
        resetEstimatedRide()
    }

    /** Resume recording if there was one in progress when the activity was last stopped. */
    private fun resumeRecordingIfPaused() {
        RideRecordingRepository[app].recordingFile?.let {
            RideRecordingRepository[app].startRecording(true)
        }
    }

    /**
     * Start recording of ride
     */
    @MainThread
    fun startRideRecording() {
        RideRecordingRepository[app].startRecording(true)
    }

    /**
     * Hide the ride shown on the map
     *
     * @see visibleTrack
     */
    fun hideTrack() {
        viewModelScope.launch {
            PreviousRidesRepository[app].showOnMap(null)
        }
    }

    /**
     * Clear directions back to start of ride
     *
     * @see getDirectionsBack
     */
    suspend fun clearDirectionsBack() {
        PreviousRidesRepository[app].previousRidesAndDirectionsBack.filterNotNull().first().onEach {
            if (it.isDirectionsHome) {
                PreviousRidesRepository[app].showOnMap(null)
            }
        }
    }

    /**
     * Reset the estimated ride
     */
    fun resetEstimatedRide() {
        viewModelScope.launch {
            rideEstimator.clear()
        }
    }

    fun onRidingActivityStarted() {
        viewModelScope.launch {
            rideEstimator.onActivityStarted()
        }

        resumeRecordingIfPaused()
    }

    fun onRidingActivityStopped() {
        viewModelScope.launch {
            rideEstimator.onActivityStopped()
        }
    }

    /** Allow this view model to use the passed [activity] for start-with-activity-result */
    fun registerActivityResultsContracts(activity: AppCompatActivity) {
        getLocationPermissionLauncher =
            activity.registerForActivityResult(RequestPermission()) { granted ->
                viewModelScope.launch {
                    shouldShowLocationPermissionRationale.emit(
                        activity.shouldShowRequestPermissionRationale(ACCESS_FINE_LOCATION)
                    )
                }

                if (granted) {
                    LocationRepository[app].updateHasLocationPermissionTrigger.trigger()
                } else {
                    Toast.makeText(
                        app,
                        app.getString(R.string.location_permission_denied_warning),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

        getPreviousRidesLauncher =
            activity.registerForActivityResult(StartActivityForResult()) { result: ActivityResult ->
                result.data?.getStringExtra(PreviousRidesActivity.KEY_SELECTED_PREVIOUS_RIDE)
                    ?.let { previousRideFile ->
                        state.remove<String>(KEY_PREVIOUS_RIDE_SHOWN)

                        viewModelScope.launch {
                            PreviousRidesRepository[app].previousRidesAndDirectionsBack.filterNotNull()
                                .first().find { it.file.name == previousRideFile }?.let {
                                    PreviousRidesRepository[app].showOnMap(it)
                                }
                        }
                    }
            }

        getDirectionsBackLauncher =
            activity.registerForActivityResult(StartActivityForResult()) { result: ActivityResult ->
                if (result.resultCode == RESULT_OK) {
                    // Unload any currently shown track
                    viewModelScope.launch {
                        PreviousRidesRepository[app].showOnMap(null)
                        clearDirectionsBack()

                        val directionsBack =
                            result.data!!.getParcelableArrayListExtraCompat(
                                ConfirmDirectionsActivityViewModel.EXTRA_DIRECTIONS,
                                BasicLocation::class.java
                            ) ?: return@launch

                        val track = Track(listOf(directionsBack.map {
                            RecordedLocation(
                                it.latitude,
                                it.longitude,
                                null,
                                null
                            )
                        }))

                        val directionsBackFile = File.createTempFile(
                            PreviousRide.DIRECTIONS_HOME_FILE_PREFIX,
                            GPX_ZIP_FILE_EXTENSION,
                            app.filesDir
                        )

                        // Add the directions back as a previous track with a special file name.
                        val addedRide = withContext(IO) {
                            val ins = PipedInputStream()
                            val out = PipedOutputStream(ins)
                            val add = async {
                                PreviousRidesRepository[app].addRide(
                                    ins,
                                    directionsBackFile.name
                                ) {}
                            }
                            out.buffered().use {
                                it.writePreviousRide(track, System.currentTimeMillis(), null)
                            }

                            add.await()
                        }

                        addedRide?.let {
                            PreviousRidesRepository[app].showOnMap(it)
                        }
                    }
                }
            }

        viewModelScope.launch {
            shouldShowLocationPermissionRationale.emit(
                activity.shouldShowRequestPermissionRationale(ACCESS_FINE_LOCATION)
            )
        }

        if (!activity.shouldShowRequestPermissionRationale(ACCESS_FINE_LOCATION)) {
            requestLocationPermission()
        }
    }

    fun requestLocationPermission() {
        getLocationPermissionLauncher.launch(ACCESS_FINE_LOCATION)
    }

    fun getDirectionsBack() {
        getDirectionsBackLauncher.launch(
            Intent(
                app,
                ConfirmDirectionsActivity::class.java
            )
        )
    }
}
