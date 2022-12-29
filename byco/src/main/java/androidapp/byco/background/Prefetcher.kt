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

package androidapp.byco.background

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.util.Log
import androidapp.byco.FixedLowPriorityThreads
import androidapp.byco.JobIds.PREFETCHER
import androidapp.byco.background.Prefetcher.ProcessPriority.*
import androidapp.byco.data.*
import androidapp.byco.data.PhoneStateRepository.NetworkType
import androidapp.byco.data.PhoneStateRepository.NetworkType.*
import androidapp.byco.ui.views.setRequiresBatteryNotLowCompat
import androidapp.byco.ui.views.setRequiresStorageNotLowCompat
import androidapp.byco.util.*
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.channels.consume
import lib.gpx.DebugLog
import lib.gpx.MapArea
import lib.gpx.toBasicLocation
import java.math.BigDecimal
import java.math.BigDecimal.TEN

/**
 * Runs in background and prefetches data and creates thumbnails.
 *
 * Each active unit should call [start] and [stop] to control the priority of this process.
 */
class Prefetcher(
    private val app: Application,
    private val LOCATION_BASED_PREFECT_TRIGGER_DISTANCE: Float = 1000f,
    private val LOCATION_BASED_PREFETCH_DISTANCE: Float = 10000f,
    private val LOCATION_BASED_PREFETCH_DISTANCE_METERED: Float = 5000f,
    private val RIDING_STARTUP_DELAY: Long = 10000L,  //ms
    private val FOREGROUND_STARTUP_DELAY: Long = 100L  //ms
) {
    private val TAG = Prefetcher::class.java.simpleName

    enum class ProcessPriority {
        RIDING,
        FOREGROUND,
        BACKGROUND,
        STOPPED,
    }

    private val prefetcherScope = CoroutineScope(Job())
    private val finishedListeners = mutableSetOf<OnFinishedCallback>()

    private var prefetcher: Job? = null

    private fun restartPrefetcher() {
        val priority = getEffectivePriority()
        val location = LocationRepository[app].location.value
        val networkType = PhoneStateRepository[app].networkType.value ?: STOPPED
        val ridesWithoutThumbnails = ridesWithoutThumbnails.value ?: emptyList()

        val wasPrefetching = prefetcher?.isActive == true
        prefetcher?.cancel()

        if (networkType == NO_NETWORK && priority != STOPPED) {
            return
        } else if (priority == STOPPED) {
            if (wasPrefetching) {
                Log.i(TAG, "Last prefetching was not finished, schedule for background")

                app.getSystemService(JobScheduler::class.java).schedule(
                    JobInfo.Builder(
                        PREFETCHER.ordinal,
                        ComponentName(app, PrefetcherJobService::class.java)
                    ).setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                        .setRequiresBatteryNotLowCompat(true)
                        .setRequiresStorageNotLowCompat(true)
                        .build()
                )
            }

            return
        }

        prefetcher = prefetcherScope.launch(FixedLowPriorityThreads.random()) {
            delay(
                when {
                    // At riding startup the system is already under pressure, don't make it worse
                    priority <= RIDING -> RIDING_STARTUP_DELAY
                    // Often there are multiple changes at the same time
                    priority <= FOREGROUND -> FOREGROUND_STARTUP_DELAY
                    else -> 0
                }
            )

            if (priority <= FOREGROUND) {
                val distance = if (networkType == METERED) {
                    LOCATION_BASED_PREFETCH_DISTANCE_METERED
                } else {
                    LOCATION_BASED_PREFETCH_DISTANCE
                }

                location?.let { location ->
                    try {
                        listOf(
                            async(IO) {
                                MapDataRepository[app].preFetchMapData(
                                    location.toBasicLocation(),
                                    distance
                                )
                            }, async(IO) {
                                ElevationDataRepository[app].preFetchElevationData(
                                    location.toBasicLocation(),
                                    distance
                                )
                            }).awaitAll()
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not prefetch around current location", e)
                    }
                }
            }

            if (networkType == UNMETERED && priority <= BACKGROUND
                && ridesWithoutThumbnails.isNotEmpty()
            ) {
                ridesWithoutThumbnails.map { it.ride }.forEach { ride ->
                    if (!isActive) {
                        Log.v(TAG, "Not generating thumbnail as prefetcher is canceled")
                        return@forEach
                    }

                    DebugLog.v(TAG, "Generating thumbnail(s) for ${ride.file.name}")

                    val wholeThumbnailArea = ThumbnailRepository[app].getThumbnailArea(ride.area)
                    val tileWidth =
                        BigDecimal(4).divide(TEN.pow(MapDataRepository.TILE_SCALE))

                    var lightThumbnail = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                    var darkThumbnail = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

                    PreviousRidesRepository[app].getTrack(ride).observeAsChannel().consume {
                        val track = receive().restrictTo(null)

                        val countryCode = getCountryCode(
                            app,
                            Location("center").apply {
                                wholeThumbnailArea.apply {
                                    latitude = minLatD + ((maxLatD - minLatD) / 2)
                                    longitude = minLonD + ((maxLonD - minLonD) / 2)
                                }
                            },
                            isCurrentLocation = false
                        )

                        forBigDecimal(
                            wholeThumbnailArea.minLat,
                            wholeThumbnailArea.maxLat,
                            tileWidth
                        ) { lat ->
                            forBigDecimal(
                                wholeThumbnailArea.minLon,
                                wholeThumbnailArea.maxLon,
                                tileWidth
                            ) { lon ->
                                val renderArea = MapArea(
                                    lat,
                                    lon,
                                    lat + tileWidth,
                                    lon + tileWidth
                                )

                                MapDataRepository[app].getMapData(
                                    renderArea, returnPartialData = false, loadStreetNames = false,
                                    lowPriority = true
                                ).observeAsChannel().consume {
                                    val mapData = receive()

                                    val newLightThumbnail =
                                        ThumbnailRepository[app].renderThumbnail(
                                            countryCode,
                                            mapData,
                                            ride.area,
                                            track,
                                            false,
                                            renderArea,
                                            BitmapDrawable(app.resources, lightThumbnail)
                                        )
                                    lightThumbnail.recycle()
                                    lightThumbnail = newLightThumbnail

                                    val newDarkThumbnail =
                                        ThumbnailRepository[app].renderThumbnail(
                                            countryCode,
                                            mapData,
                                            ride.area,
                                            track,
                                            true,
                                            renderArea,
                                            BitmapDrawable(app.resources, darkThumbnail)
                                        )
                                    darkThumbnail.recycle()
                                    darkThumbnail = newDarkThumbnail
                                }
                            }
                        }
                    }

                    ThumbnailRepository[app].persistThumbnail(
                        lightThumbnail,
                        ride,
                        false
                    )
                    ThumbnailRepository[app].persistThumbnail(
                        darkThumbnail,
                        ride,
                        true
                    )

                    DebugLog.v(TAG, "Generated thumbnail(s) for ${ride.file.name}")
                }
            } else {
                Log.v(TAG, "Not generating thumbnails")
            }

            if (isActive) {
                val thisPrefetcherJob = coroutineContext.job
                prefetcherScope.launch {
                    thisPrefetcherJob.join()

                    // Run onFinished _after_ the prefetcher is not active anymore
                    Log.v(TAG, "Done prefetching")
                    onFinished()
                }
            }
        }
    }

    private val ridesWithoutThumbnails =
        object : AsyncLiveData<List<ThumbnailId>>() {
            private var hasThumbnailLiveData =
                mutableMapOf<PreviousRide, Pair<LiveData<Boolean>, LiveData<Boolean>>>()

            init {
                addSource(PreviousRidesRepository[app].previousRides) { rides ->
                    liveDataScope.launch {
                        hasThumbnailLiveData = hasThumbnailLiveData.filter { (ride, liveDatas) ->
                            if (!rides.contains(ride)) {
                                withContext(Main) {
                                    removeSource(liveDatas.first)
                                    removeSource(liveDatas.second)
                                }
                                false
                            } else {
                                true
                            }
                        }.toMutableMap()

                        val ridesWithoutThumbnails = rides - hasThumbnailLiveData.keys

                        withContext(Main) {
                            ridesWithoutThumbnails.forEach { ride ->
                                val lightLiveData =
                                    ThumbnailRepository[app].getHasThumbnail(ride, false)
                                val darkLiveData =
                                    ThumbnailRepository[app].getHasThumbnail(ride, true)

                                hasThumbnailLiveData[ride] = lightLiveData to darkLiveData

                                addSource(lightLiveData) { requestUpdate() }
                                addSource(darkLiveData) { requestUpdate() }
                            }
                        }
                    }
                }
            }

            override suspend fun update(): List<ThumbnailId>? {
                val newValue = hasThumbnailLiveData.flatMap { (ride, liveDatas) ->
                    mutableListOf<ThumbnailId>().apply {
                        if (liveDatas.first.value != true) {
                            add(ThumbnailId(ride, false))
                        }
                        if (liveDatas.second.value != true) {
                            add(ThumbnailId(ride, true))
                        }
                    }
                }

                return if (newValue != value) {
                    newValue
                } else {
                    null
                }
            }
        }

    private val ridesWithoutThumbnailsObserver = Observer<List<ThumbnailId>> {
        restartPrefetcher()
    }

    private val networkTypeObserver = Observer<NetworkType> {
        restartPrefetcher()
    }

    private var lastLocation = Location("empty")
    private val locationObserver = Observer<Location> { location ->
        if (location.distanceTo(lastLocation) > LOCATION_BASED_PREFECT_TRIGGER_DISTANCE) {
            restartPrefetcher()
            lastLocation = location
        }
    }

    private val priorities = mutableListOf<ProcessPriority>()
    private fun getEffectivePriority(): ProcessPriority {
        return priorities.minOfOrNull { it } ?: STOPPED
    }

    init {
        PhoneStateRepository[app].networkType.observeForever(networkTypeObserver)
    }

    /**
     * Called by active units (e.g. Activities, Services, Jobs) to adjust the priority of this
     * process.
     */
    fun start(priority: ProcessPriority) {
        val prevPriority = getEffectivePriority()
        priorities.add(priority)

        reevaluateWork(prevPriority, getEffectivePriority())
    }

    /**
     * Called by active units (e.g. Activities, Services, Jobs) to adjust the priority of this
     * process.
     */
    fun stop(priority: ProcessPriority) {
        val prevPriority = getEffectivePriority()
        priorities.remove(priority)

        reevaluateWork(prevPriority, getEffectivePriority())
    }

    private fun reevaluateWork(
        prevPriority: ProcessPriority,
        newPriority: ProcessPriority
    ) {
        if (prevPriority == newPriority) {
            return
        }

        if (prevPriority <= FOREGROUND && newPriority > FOREGROUND) {
            LocationRepository[app].location.removeObserver(locationObserver)
        }
        if (prevPriority <= BACKGROUND && newPriority > BACKGROUND) {
            ridesWithoutThumbnails.removeObserver(ridesWithoutThumbnailsObserver)
        }

        if (prevPriority > BACKGROUND && newPriority <= BACKGROUND) {
            ridesWithoutThumbnails.observeForever(ridesWithoutThumbnailsObserver)
        }
        if (prevPriority > FOREGROUND && newPriority <= FOREGROUND) {
            LocationRepository[app].passiveLocation.observeForever(locationObserver)
        }

        restartPrefetcher()
    }

    /**
     * Register callback when finished prefetching
     */
    fun registerFinishedListener(finishedCallback: OnFinishedCallback) {
        finishedListeners.add(finishedCallback)
    }

    /**
     * Unregister callback when finished prefetching
     */
    fun unregisterFinishedListener(finishedCallback: OnFinishedCallback) {
        finishedListeners.remove(finishedCallback)
    }

    private fun onFinished() {
        finishedListeners.toList().forEach { it.onFinished() }
    }

    private data class ThumbnailId(
        val ride: PreviousRide,
        val isDarkMode: Boolean
    )

    interface OnFinishedCallback {
        fun onFinished()
    }

    companion object :
        SingleParameterSingletonOf<Application, Prefetcher>({ Prefetcher(it) })
}