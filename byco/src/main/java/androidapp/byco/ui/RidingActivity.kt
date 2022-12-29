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

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.location.Location
import android.os.Bundle
import android.view.View.INVISIBLE
import android.view.ViewGroup
import androidapp.byco.background.Prefetcher
import androidapp.byco.background.Prefetcher.ProcessPriority.RIDING
import androidapp.byco.data.MapData
import androidapp.byco.lib.R
import androidapp.byco.lib.databinding.RidingActivityBinding
import androidapp.byco.ui.views.ElevationView
import androidapp.byco.util.CountryCode
import androidapp.byco.util.makeVisibleIf
import androidapp.byco.util.plus
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.Observer
import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.text.SimpleDateFormat
import kotlin.math.max

/**
 * Shows current speed and position
 *
 * Startup activity.
 */
class RidingActivity : AppCompatActivity() {
    private val viewModel by viewModels<RidingActivityViewModel>()
    private lateinit var binding: RidingActivityBinding

    @SuppressLint("SimpleDateFormat")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.registerActivityResultsContracts(this)

        binding = RidingActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.map.boundaryChangedListeners.add { mapArea ->
            viewModel.setMapBoundaries(mapArea)
        }

        binding.map.animatedLocationListener.add { location ->
            viewModel.setAnimatedLocation(location)
        }

        binding.recordStop.setOnClickListener {
            binding.recordCoach.neverShowAgain()

            if (viewModel.isRideBeingRecorded.value == true) {
                viewModel.stopRideRecording()
            } else {
                viewModel.startRideRecording()
            }
        }

        (viewModel.currentLocation + viewModel.smoothedBearing + viewModel.isMoving).observe(this,
            object : Observer<Triple<Location?, Float?, Boolean?>> {
                private var lastLocationUpdate = Long.MIN_VALUE
                private var lastLocation: Location? = null

                override fun onChanged(value: Triple<Location?, Float?, Boolean?>) {
                    val (currentLocation, smoothedBearing, isMoving) = value

                    currentLocation ?: return

                    val now = System.currentTimeMillis()
                    if (!(lastLocation === currentLocation)) {
                        val useSmoothBearing = smoothedBearing != null && isMoving != true

                        binding.map.animateToLocation(
                            currentLocation,
                            if (useSmoothBearing) {
                                smoothedBearing
                            } else {
                                null
                            },
                            max(0, now - lastLocationUpdate),
                            animate = true
                        )

                        lastLocation = currentLocation
                        lastLocationUpdate = now
                    }
                }
            })

        (viewModel.isUsingMiles + viewModel.speed).observe(this) { (isUsingMiles, speed) ->
            binding.speed.text =
                speed?.format(this, isUsingMiles == true, showUnits = false) ?: "0"
        }

        fun updateRecordStopMargin(moveAwayFromRide: Float) {
            binding.recordStopWrapper.layoutParams =
                (binding.recordStopWrapper.layoutParams as ViewGroup.MarginLayoutParams)
                    .apply {
                        // Set both margins: one is needed for landscape, the other for portrait
                        marginStart = (moveAwayFromRide * binding.ride.width).toInt()
                        marginEnd = (moveAwayFromRide * binding.ride.width).toInt()
                    }
        }

        fun onRideWidthChange() {
            // Width of "ride" might have changed, hence move "recordStop"
            lifecycle.coroutineScope.launch(Main) {
                // Wait for "ride" to update
                while (binding.ride.isDirty) {
                    yield()
                }

                updateRecordStopMargin(1f)
            }
        }

        onRideWidthChange()

        viewModel.rideDuration.observe(this) { rideTime ->
            // Animate showing of "ride" text and moving "recordStop" button out of the way
            val showHideRideAnim = ValueAnimator.AnimatorUpdateListener { valueAnimator ->
                val animV = valueAnimator.animatedValue as Float
                updateRecordStopMargin(animV)
                binding.ride.alpha = animV
            }

            rideTime?.let {
                binding.rideDuration.text = rideTime.format(this, false)

                if (binding.ride.alpha != 1f) {
                    ValueAnimator.ofFloat(binding.ride.alpha, 1f).apply {
                        addUpdateListener(showHideRideAnim)
                        start()
                    }
                } else {
                    onRideWidthChange()
                }
            } ?: run {
                ValueAnimator.ofFloat(binding.ride.alpha, 0f).apply {
                    addUpdateListener(showHideRideAnim)
                    start()
                }
            }
        }

        (viewModel.isMoving + viewModel.hasPreviousRide).observe(
            this
        ) { (isMoving, hasPreviousRide) ->
            binding.previousRidesCoach.makeVisibleIf(isMoving != true && hasPreviousRide == true)
        }

        (viewModel.isMoving + viewModel.isRideBeingRecorded).observe(this) { (isMoving, isRideBeingRecorded) ->
            binding.recordStop.makeVisibleIf(isMoving != true)
            binding.recordCoach.makeVisibleIf(isMoving != true)

            binding.overflowMenu.makeVisibleIf(isMoving != true)

            binding.recordStop.apply {
                if (isRideBeingRecorded == true) {
                    setImageDrawable(
                        AppCompatResources.getDrawable(
                            this@RidingActivity,
                            R.drawable.ic_baseline_stop_24
                        )
                    )
                    setColorFilter(getColor(R.color.white))
                } else {
                    setImageDrawable(
                        AppCompatResources.getDrawable(
                            this@RidingActivity,
                            R.drawable.ic_baseline_record_24
                        )
                    )
                    setColorFilter(getColor(R.color.record_icon_color))
                }
            }
        }

        (viewModel.isMoving + viewModel.rideStart).observe(this) { (isMoving, rideStart) ->
            binding.directionsBack.makeVisibleIf(isMoving != true && rideStart != null)
        }

        (viewModel.isMoving + viewModel.rideStart + viewModel.currentLocation).observe(this) { (isMoving, rideStart, currentLocation) ->
            binding.directionsBackCoach.makeVisibleIf(
                isMoving != true && rideStart != null && currentLocation?.let {
                    it.distanceTo(rideStart.toLocation()) > 5000
                } == true)
        }

        (viewModel.countryCode + viewModel.mapData).observe(
            this,
            object : Observer<Pair<CountryCode, MapData?>> {
                private var setMapDataJob: Job? = null

                override fun onChanged(value: Pair<CountryCode, MapData?>) {
                    setMapDataJob?.cancel()

                    val (countryCode, mapData) = value

                    if (mapData != null) {
                        setMapDataJob = lifecycle.coroutineScope.launch {
                            binding.map.setMapData(countryCode, mapData)
                        }
                    }
                }
            })

        viewModel.currentDate.observe(this) { date ->
            binding.time.text = SimpleDateFormat("h:mm").format(date)
        }

        viewModel.batteryLevel.observe(this) { batteryLevel ->
            binding.battery.text = getString(R.string.battery_pct_pattern, batteryLevel)
            if (batteryLevel <= 25) {
                binding.map.maxFps = 10
                binding.battery.setTextColor(getColor(R.color.battery_low))
            } else {
                binding.map.maxFps = Int.MAX_VALUE
                binding.battery.setTextColor(getColor(R.color.battery_ok))
            }

            binding.battery.makeVisibleIf(batteryLevel <= 50, INVISIBLE)
            binding.batteryLabel.makeVisibleIf(batteryLevel <= 50, INVISIBLE)
        }

        binding.overflowMenu.setOnClickListener {
            binding.previousRidesCoach.neverShowAgain()

            val popup = PopupMenu(this, binding.overflowMenu)
            popup.menuInflater.inflate(R.menu.riding_activity, popup.menu)

            popup.menu.findItem(R.id.stop_showing).isVisible =
                viewModel.isShowingTrack.value == true

            popup.menu.findItem(R.id.clear_directions).isVisible =
                viewModel.isShowingDirectionsBack.value == true

            popup.menu.findItem(R.id.reset_estimated_ride).isVisible =
                viewModel.isEstimatedRideOngoing.value == true

            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.rides -> viewModel.showPreviousRides(this)
                    R.id.stop_showing -> viewModel.hideTrack()
                    R.id.clear_directions -> viewModel.clearDirectionsBack()
                    R.id.reset_estimated_ride -> viewModel.resetEstimatedRide()
                    R.id.help -> viewModel.showHelp(this)
                    R.id.about -> viewModel.showAbout(this)
                }

                true
            }
            popup.show()
        }

        binding.directionsBack.setOnClickListener {
            binding.directionsBackCoach.neverShowAgain()

            viewModel.getDirectionsBack()
        }

        viewModel.isUsingMiles.observe(this) { isUsingMiles ->
            binding.speedUnit.text = getString(
                if (isUsingMiles) {
                    R.string.speed_unit_imperial
                } else {
                    R.string.speed_unit_metric
                }
            )
        }

        (viewModel.isMoving + viewModel.visibleTrack).observe(this) { (_, visibleTrack) ->
            binding.map.visibleTrack = visibleTrack

            invalidateOptionsMenu()
        }

        viewModel.shouldShowLocationPermissionPrompt.observe(this) { shouldShowPrompt ->
            binding.locationPermissionRationale.makeVisibleIf(shouldShowPrompt)
        }

        viewModel.currentClimbElevationProfile.observe(this) { (progress, elevations) ->
            val showHideElevationProfileAnim =
                ValueAnimator.AnimatorUpdateListener { valueAnimator ->
                    val animV = valueAnimator.animatedValue as Float

                    binding.elevationProfile.apply {
                        scaleY = animV
                        translationY = (1 - animV) * height / 2

                        makeVisibleIf(scaleY > 0f)
                    }
                    binding.climbData.alpha = animV
                }

            if (elevations === ElevationView.NO_DATA) {
                ValueAnimator.ofFloat(binding.elevationProfile.scaleY, 0f).apply {
                    addUpdateListener(showHideElevationProfileAnim)
                    start()
                }
            } else {
                binding.elevationProfile.elevations = elevations
                binding.elevationProfile.progress = progress

                ValueAnimator.ofFloat(binding.elevationProfile.scaleY, 1f).apply {
                    addUpdateListener(showHideElevationProfileAnim)
                    start()
                }
            }
        }

        viewModel.bearingToTrack.observe(this) { bearing ->
            binding.map.directionToCurrentIndicatorBearing = bearing
        }

        (viewModel.isUsingMiles + viewModel.climbLeft).observe(this) { (isUsingMiles, left) ->
            binding.climbLeft.text =
                left?.format(this, isUsingMiles == true, maybeUseSmallUnits = true) ?: ""
        }

        binding.locationPermissionRequestButton.setOnClickListener {
            viewModel.requestLocationPermission()
        }
    }

    override fun onStart() {
        super.onStart()

        Prefetcher[application].start(RIDING)
        viewModel.onRidingActivityStarted()
    }

    override fun onStop() {
        super.onStop()

        Prefetcher[application].stop(RIDING)
        viewModel.onRidingActivityStopped()
    }
}