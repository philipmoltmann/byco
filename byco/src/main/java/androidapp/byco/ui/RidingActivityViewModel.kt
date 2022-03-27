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
import androidapp.byco.data.*
import androidapp.byco.lib.R
import androidapp.byco.util.AsyncLiveData
import androidapp.byco.util.addSources
import androidapp.byco.util.plus
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.*
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.withContext
import lib.gpx.*

/** ViewModel for [RidingActivity] */
class RidingActivityViewModel(private val app: Application, val state: SavedStateHandle) :
    AndroidViewModel(app) {
    private val KEY_DIRECTIONS_BACK = "KEY_DIRECTIONS_BACK"

    private val mapArea = object : MutableLiveData<MapArea>() {}
    private val animatedLocation = object : MutableLiveData<Location>() {}

    private lateinit var getLocationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var getDirectionsBackLauncher: ActivityResultLauncher<Intent>
    private val rideEstimator = RideEstimator(app, state, viewModelScope)

    /** The current date */
    val currentDate = PhoneStateRepository[app].date

    /** The current battery level */
    val batteryLevel = PhoneStateRepository[app].batteryLevel

    /** The current location */
    val currentLocation = LocationRepository[app].location

    /** The current speed */
    val speed = LocationRepository[app].speed

    /** Is the phone currently moving */
    val isMoving = LocationRepository[app].isMoving

    /** Bearing smoothed over the very recent movement */
    val smoothedBearing = LocationRepository[app].smoothedBearing

    /** Is there a previously recorded ride */
    val hasPreviousRide =
        Transformations.map(PreviousRidesRepository[app].previousRides) { it.isNotEmpty() }

    /** Is the current country using miles and mph? */
    val isUsingMiles = LocationRepository[app].isUsingMiles

    /** Is the current country using miles and mph? */
    val countryCode = LocationRepository[app].countryCode

    /** Is a ride currently being recorded */
    val isRideBeingRecorded = RideRecordingRepository[app].isRideBeingRecorded

    /** Duration of the currently recorded ride */
    private val recordedRideDuration = RideRecordingRepository[app].rideTime

    /** Directions back (if generated */
    private val directionsBack = state.getLiveData<ArrayList<BasicLocation>?>(KEY_DIRECTIONS_BACK)

    /** [MapData] to show on map */
    val mapData = object : MediatorLiveData<MapData>() {
        private var currentMapDataLiveData: LiveData<MapData>? =
            null

        init {
            addSource(mapArea) { newMapArea ->
                currentMapDataLiveData?.let { removeSource(it) }
                currentMapDataLiveData =
                    MapDataRepository[app].getMapData(newMapArea, true)
                        .also { newMapDataLiveData -> addSource(newMapDataLiveData) { value = it } }
            }
        }
    } as LiveData<Set<Way>>

    val hasLocationPermission = LocationRepository[app].hasLocationPermission
    private val shouldShowLocationPermissionRationale = MutableLiveData<Boolean>()

    val shouldShowLocationPermissionPrompt = object : MediatorLiveData<Boolean>() {
        init {
            addSources(hasLocationPermission, shouldShowLocationPermissionRationale) { update() }
        }

        private fun update() {
            value = hasLocationPermission.value != true
                    && shouldShowLocationPermissionRationale.value == true
        }
    }

    /** Is currently a track (from a previous ride) shown? */
    val isShowingTrack =
        Transformations.map(PreviousRidesRepository[app].visibleTrack) { it != null }

    /** Is currently the directions back shown */
    val isShowingDirectionsBack =
        Transformations.map(isShowingTrack + directionsBack) { (isShowingTrack, directionsBack) ->
            (isShowingTrack == null || isShowingTrack == false) && directionsBack != null
        }

    /**
     * Track to be shown overlaid on map
     *
     * @see hideTrack
     */
    val visibleTrack = object : AsyncLiveData<TrackAsNodes>(setNullValues = true) {
        init {
            requestUpdateIfChanged(
                isShowingDirectionsBack,
                PreviousRidesRepository[app].visibleTrack, mapArea,
                directionsBack
            )
        }

        override suspend fun update(): TrackAsNodes? {
            // Unset directions back if previous ride is set
            if (PreviousRidesRepository[app].visibleTrack.value != null) {
                withContext(Main) {
                    if (directionsBack.value != null) {
                        directionsBack.value = null
                    }
                }
            }

            val area = mapArea.value ?: return null

            return if (directionsBack.value != null) {
                Track(listOf(directionsBack.value!!.map {
                    RecordedLocation(
                        it.latitude,
                        it.longitude,
                        null,
                        null
                    )
                }))
            } else {
                PreviousRidesRepository[app].visibleTrack.value
            }?.restrictTo(area)
        }
    }

    /** The bearing to the track or `null` if it should not be shown. */
    val bearingToTrack = object : MediatorLiveData<Float>() {
        init {
            addSources(
                animatedLocation,
                PreviousRidesRepository[app].closestLocationOnTrack
            ) {
                val MIN_DISTANCE_TO_TRACK = 70f

                val location = animatedLocation.value
                val closestLocationWrapper =
                    PreviousRidesRepository[app].closestLocationOnTrack.value

                value = if (location == null || closestLocationWrapper == null) {
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
        }
    }

    /**
     * Elevation profile of current climb
     */
    val currentClimbElevationProfile = ElevationDataRepository[app].currentClimbElevationProfile

    /**
     * Meters left in current climb
     */
    val climbLeft = ElevationDataRepository[app].climbLeft

    /** Is an estimated ride ongoing */
    val isEstimatedRideOngoing = rideEstimator.isOngoing

    val rideDuration = object : MediatorLiveData<Duration?>() {
        init {
            addSources(rideEstimator.duration, recordedRideDuration) { update() }
        }

        private fun update() {
            value = if (recordedRideDuration.value != null) {
                recordedRideDuration.value
            } else {
                rideEstimator.duration.value
            }
        }
    }

    val rideStart = RouteFinderRepository[app].rideStart

    /**
     * Set [boundaries] currently visible on map
     */
    fun setMapBoundaries(boundaries: MapArea) {
        mapArea.value = boundaries
    }

    fun setAnimatedLocation(location: Location) {
        animatedLocation.value = location
    }

    /**
     * Show list of previous rides in new activity
     *
     * @param activity current activity
     */
    fun showPreviousRides(activity: Activity) {
        activity.startActivity(Intent(activity, PreviousRidesActivity::class.java))
    }

    /**
     * Show help
     */
    fun showHelp(activity: Activity) {
        activity.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                    app.packageManager.getApplicationInfo(
                        app.packageName,
                        PackageManager.GET_META_DATA
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
        PreviousRidesRepository[app].showOnMap(null)
    }

    /**
     * Clear directions back to start of ride
     *
     * @see getDirectionsBack
     */
    fun clearDirectionsBack() {
        directionsBack.value = null
    }

    /*
     * Reset the estimated ride
     */
    fun resetEstimatedRide() {
        rideEstimator.clear()
    }

    fun onRidingActivityStarted() {
        rideEstimator.onActivityStarted()
        resumeRecordingIfPaused()
    }

    fun onRidingActivityStopped() {
        rideEstimator.onActivityStopped()
    }

    /** Allow this view model to use the passed [activity] for start-with-activity-result */
    fun registerActivityResultsContracts(activity: AppCompatActivity) {
        getLocationPermissionLauncher =
            activity.registerForActivityResult(RequestPermission()) { granted ->
                shouldShowLocationPermissionRationale.value =
                    activity.shouldShowRequestPermissionRationale(ACCESS_FINE_LOCATION)

                if (granted) {
                    hasLocationPermission.value = true
                } else {
                    Toast.makeText(
                        app,
                        app.getString(R.string.location_permission_denied_warning),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

        getDirectionsBackLauncher =
            activity.registerForActivityResult(StartActivityForResult()) { result: ActivityResult ->
                if (result.resultCode == RESULT_OK) {
                    // Unload any currently shown track
                    PreviousRidesRepository[app].showOnMap(null)

                    // Set directions back as track
                    directionsBack.value =
                        result.data!!.getParcelableArrayListExtra(ConfirmDirectionsActivityViewModel.EXTRA_DIRECTIONS)
                }
            }

        shouldShowLocationPermissionRationale.value =
            activity.shouldShowRequestPermissionRationale(ACCESS_FINE_LOCATION)

        if (!activity.shouldShowRequestPermissionRationale(ACCESS_FINE_LOCATION)) {
            requestLocationPermission()
        }
    }

    fun requestLocationPermission() {
        getLocationPermissionLauncher.launch(ACCESS_FINE_LOCATION)
    }

    fun getDirectionsBack() {
        getDirectionsBackLauncher.launch(Intent(app, ConfirmDirectionsActivity::class.java))
    }
}
