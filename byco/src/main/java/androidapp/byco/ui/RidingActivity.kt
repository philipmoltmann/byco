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
import android.os.Bundle
import android.view.View.INVISIBLE
import android.view.ViewGroup
import androidapp.byco.data.LocationRepository
import androidapp.byco.data.PhoneStateRepository.ProcessPriority
import androidapp.byco.lib.R
import androidapp.byco.lib.databinding.RidingActivityBinding
import androidapp.byco.ui.views.ElevationView
import androidapp.byco.util.BycoActivity
import androidapp.byco.util.makeVisibleIf
import androidapp.byco.util.plus
import androidx.activity.viewModels
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
class RidingActivity : BycoActivity(ProcessPriority.RIDING) {
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

            if (viewModel.isRideBeingRecorded.value) {
                viewModel.stopRideRecording()
            } else {
                viewModel.startRideRecording()
            }
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

        binding.locationPermissionRequestButton.setOnClickListener {
            viewModel.requestLocationPermission()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.shouldShowLocationPermissionPrompt.collect { shouldShowPrompt ->
                        binding.locationPermissionRationale.makeVisibleIf(shouldShowPrompt)
                    }
                }

                launch {
                    (viewModel.smoothedLocation + viewModel.isRideBeingRecorded).collect { (smoothedLocation, isRideBeingRecorded) ->
                        binding.recordStop.makeVisibleIf(smoothedLocation?.isMoving != true)
                        binding.recordCoach.makeVisibleIf(smoothedLocation?.isMoving != true)

                        binding.overflowMenu.makeVisibleIf(smoothedLocation?.isMoving != true)

                        binding.recordStop.apply {
                            if (isRideBeingRecorded) {
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
                }

                launch {
                    var lastLocationUpdate = Long.MIN_VALUE
                    var lastLocation: LocationRepository.SmoothedLocation? = null

                    viewModel.smoothedLocation.collect { location ->
                        location ?: return@collect

                        val now = System.currentTimeMillis()
                        if (lastLocation !== location) {
                            binding.map.animateToLocation(
                                location.toLocation(),
                                if (!location.isMoving) {
                                    location.smoothedBearing
                                } else {
                                    null
                                },
                                max(0, now - lastLocationUpdate),
                                animate = true
                            )

                            lastLocation = location
                            lastLocationUpdate = now
                        }
                    }
                }

                launch {
                    viewModel.rideDuration.collect { rideTime ->
                        // Animate showing of "ride" text and moving "recordStop" button out of the way
                        val showHideRideAnim =
                            ValueAnimator.AnimatorUpdateListener { valueAnimator ->
                                val animV = valueAnimator.animatedValue as Float
                                updateRecordStopMargin(animV)
                                binding.ride.alpha = animV
                            }

                        rideTime?.let {
                            binding.rideDuration.text = rideTime.format(this@RidingActivity, false)

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
                }

                launch {
                    viewModel.currentClimbElevationProfile.collect { (progress, elevations) ->
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
                }

                launch {
                    viewModel.bearingToTrack.collect { bearing ->
                        binding.map.directionToCurrentIndicatorBearing = bearing
                    }
                }

                launch {
                    (viewModel.smoothedLocation + viewModel.visibleTrack).collect { (_, visibleTrack) ->
                        binding.map.visibleTrack = visibleTrack

                        invalidateOptionsMenu()
                    }
                }

                launch {
                    (viewModel.smoothedLocation + viewModel.hasPreviousRide).collect { (smoothedLocation, hasPreviousRide) ->
                        binding.previousRidesCoach.makeVisibleIf(smoothedLocation?.isMoving != true && hasPreviousRide)
                    }
                }

                launch {
                    (viewModel.smoothedLocation + viewModel.rideStart).collect { (smoothedLocation, rideStart) ->
                        binding.directionsBack.makeVisibleIf(smoothedLocation?.isMoving != true && rideStart != null)
                        binding.directionsBackCoach.makeVisibleIf(
                            rideStart != null && smoothedLocation?.let {
                                it.location.distanceTo(rideStart.toLocation()) > 5000 && !it.isMoving
                            } == true)
                    }

                }

                launch {
                    viewModel.batteryLevel.collect { batteryLevel ->
                        binding.battery.text =
                            getString(R.string.battery_pct_pattern, batteryLevel)
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
                }

                launch {
                    var setMapDataJob: Job? = null

                    (viewModel.countryCode + viewModel.mapData).collect { (countryCode, mapData) ->
                        setMapDataJob?.cancel()

                        setMapDataJob = launch {
                            binding.map.setMapData(countryCode, mapData)
                        }
                    }
                }

                launch {
                    viewModel.isUsingMiles.collect { isUsingMiles ->
                        binding.speedUnit.text = getString(
                            if (isUsingMiles) {
                                R.string.speed_unit_imperial
                            } else {
                                R.string.speed_unit_metric
                            }
                        )
                    }
                }

                launch {
                    (viewModel.isUsingMiles + viewModel.speed).collect { (isUsingMiles, speed) ->
                        binding.speed.text =
                            speed?.format(this@RidingActivity, isUsingMiles, showUnits = false)
                                ?: "0"
                    }
                }

                launch {
                    (viewModel.isUsingMiles + viewModel.climbLeft).collect { (isUsingMiles, left) ->
                        binding.climbLeft.text =
                            left.format(
                                this@RidingActivity,
                                isUsingMiles,
                                maybeUseSmallUnits = true
                            )
                    }
                }

                launch {
                    viewModel.currentDate.collect { date ->
                        binding.time.text = SimpleDateFormat("h:mm").format(date)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()

        viewModel.onRidingActivityStarted()
    }

    override fun onStop() {
        super.onStop()

        viewModel.onRidingActivityStopped()
    }
}