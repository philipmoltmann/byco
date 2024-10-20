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

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Location
import android.util.Log
import androidapp.byco.BycoApplication
import androidapp.byco.JobIds
import androidapp.byco.LowPriority
import androidapp.byco.data.ElevationDataRepository
import androidapp.byco.data.LocationRepository
import androidapp.byco.data.MapDataRepository
import androidapp.byco.data.PhoneStateRepository
import androidapp.byco.data.PhoneStateRepository.NetworkType.METERED
import androidapp.byco.data.PhoneStateRepository.NetworkType.UNMETERED
import androidapp.byco.data.PhoneStateRepository.ProcessPriority.BACKGROUND
import androidapp.byco.data.PhoneStateRepository.ProcessPriority.FOREGROUND
import androidapp.byco.data.PhoneStateRepository.ProcessPriority.RIDING
import androidapp.byco.data.PreviousRidesRepository
import androidapp.byco.data.ThumbnailRepository
import androidapp.byco.data.restrictTo
import androidapp.byco.util.SingleParameterSingletonOf
import androidapp.byco.util.compat.setRequiresBatteryNotLowCompat
import androidapp.byco.util.compat.setRequiresStorageNotLowCompat
import androidapp.byco.util.getCountryCode
import androidapp.byco.util.mapBigDecimal
import androidapp.byco.util.nestedFlowToMapFlow
import androidapp.byco.util.plus
import androidapp.byco.util.stateIn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import lib.gpx.DebugLog
import lib.gpx.MapArea
import lib.gpx.Track
import lib.gpx.toBasicLocation
import java.io.IOException
import java.math.BigDecimal
import java.math.BigDecimal.TEN
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Runs in background and prefetches data and creates thumbnails.
 */
@ExperimentalCoroutinesApi
class Prefetcher(
    private val app: BycoApplication,
    private val LOCATION_BASED_PREFETCH_DISTANCE: Float = 10000f,
    private val LOCATION_BASED_PREFETCH_DISTANCE_METERED: Float = 5000f,
    private val RIDING_STARTUP_DELAY: Long = 10000L,  //ms
    private val FOREGROUND_STARTUP_DELAY: Long = 100L  //ms
) : LifecycleOwner {
    private val TAG = Prefetcher::class.java.simpleName

    private val prefetcherScope =
        CoroutineScope(
            app.appScope.coroutineContext + Job(app.appScope.coroutineContext.job) + CoroutineName(
                Prefetcher::class.simpleName!!
            )
        )

    override val lifecycle = LifecycleRegistry(this)

    /** Rides that are missing either the dark or light mode thumbnail. */
    private val ridesWithoutThumbnails =
        PreviousRidesRepository[app].previousRides.map { p -> p.toSet() }
            .nestedFlowToMapFlow { previousRide ->
                (ThumbnailRepository[app].getHasThumbnail(previousRide, false) +
                        ThumbnailRepository[app].getHasThumbnail(previousRide, true))
            }.map {
                it.entries.filter { (_, v) ->
                    val (hasLightModeThumbnail, hadDarkModeThumbnail) = v
                    return@filter !hasLightModeThumbnail || !hadDarkModeThumbnail
                }.map { m -> m.key }.toSet()
            }.stateIn(prefetcherScope, null)

    /** Map tiles around the current location. */
    private val localMapTileKeys =
        (LocationRepository[app].passiveLocation.filterNotNull() + PhoneStateRepository[app].networkType).map { (center, networkType) ->
            val distance = if (networkType == METERED) {
                LOCATION_BASED_PREFETCH_DISTANCE_METERED
            } else {
                LOCATION_BASED_PREFETCH_DISTANCE
            }

            MapDataRepository[app].getMapTilesAround(center.toBasicLocation(), distance).toSet()
        }.stateIn(prefetcherScope, null)

    /** Not yet prefetched [localMapTileKeys]. */
    private val unPrefetchedLocalMapTiles =
        localMapTileKeys.filterNotNull().nestedFlowToMapFlow { key ->
            MapDataRepository[app].isPrefetched(key)
        }.map { it.filter { (_, isPrefetched) -> !isPrefetched } }.stateIn(prefetcherScope, null)

    /** Is there any pending prefetch work? */
    val isPrefetchWorkPending = (
            ridesWithoutThumbnails.filterNotNull() + unPrefetchedLocalMapTiles.filterNotNull()
            ).map { (ridesWithoutThumbnails, localMapTiles) ->
            ridesWithoutThumbnails.isNotEmpty() || localMapTiles.isNotEmpty()
        }.stateIn(prefetcherScope, null)

    private val updateIsLocalMapTilePrefetcherRunning = Channel<Boolean>()
    private val isLocalMapTilePrefetcherRunning =
        updateIsLocalMapTilePrefetcherRunning.receiveAsFlow()
            .stateIn(prefetcherScope, false)

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch(LowPriority) {
                    (PhoneStateRepository[app].processPriority.flow + localMapTileKeys.filterNotNull())
                        // Prevent same prefetcher from starting again
                        .distinctUntilChanged()
                        .collectLatest { (priority, localMapTiles) ->
                            if (listOf(RIDING, FOREGROUND).contains(priority)) {
                                try {
                                    updateIsLocalMapTilePrefetcherRunning.send(true)
                                    DebugLog.i(
                                        TAG, "Started localMapTilePrefetcher:$localMapTiles"
                                    )
                                    delay(
                                        when {
                                            // At riding startup the system is already under pressure, don't make it worse
                                            priority <= RIDING -> RIDING_STARTUP_DELAY
                                            // Often there are multiple changes at the same time, hence back up a little bit when app is visible
                                            priority <= FOREGROUND -> FOREGROUND_STARTUP_DELAY
                                            else -> 0
                                        }
                                    )

                                    try {
                                        localMapTiles.map {
                                            listOf(
                                                async {
                                                    MapDataRepository[app].preFetchMapDataTile(
                                                        it
                                                    )
                                                },
                                                async {
                                                    ElevationDataRepository[app].preFetchElevationData(
                                                        it.toBasicLocation()
                                                    )
                                                })
                                        }.flatten().awaitAll()
                                    } catch (e: Exception) {
                                        // Do not restart localMapTilePrefetcher for some time.
                                        delay(1000)
                                        Log.e(TAG, "Could not prefetch around current location", e)
                                    }
                                } finally {
                                    withContext(NonCancellable) {
                                        updateIsLocalMapTilePrefetcherRunning.send(false)
                                    }
                                    DebugLog.i(
                                        TAG,
                                        "Finished localMapTilePrefetcher:$localMapTiles"
                                    )
                                }
                            }
                        }
                }

                launch(LowPriority) {
                    (isLocalMapTilePrefetcherRunning + PhoneStateRepository[app].processPriority.flow + PhoneStateRepository[app].networkType + ridesWithoutThumbnails.filterNotNull())
                        // Prevent same prefetcher from starting again
                        .distinctUntilChanged()
                        .collectLatest { (isLocalMapTilePrefetcherRunning, processPriority, networkType, ridesWithoutThumbnails) ->
                            if (!isLocalMapTilePrefetcherRunning && listOf(
                                    BACKGROUND, FOREGROUND
                                ).contains(processPriority) && networkType == UNMETERED
                                && ridesWithoutThumbnails.isNotEmpty()
                            ) {
                                try {
                                    DebugLog.i(
                                        TAG, "Started thumbnailPrefetcher:$ridesWithoutThumbnails"
                                    )

                                    ridesWithoutThumbnails.forEach { ride ->
                                        DebugLog.v(
                                            TAG,
                                            "Generating thumbnail(s) for ${ride.file.name}"
                                        )

                                        val wholeThumbnailArea =
                                            ThumbnailRepository[app].getThumbnailArea(ride.area)
                                        val tileWidth =
                                            BigDecimal(4).divide(TEN.pow(MapDataRepository.TILE_SCALE))

                                        var lightThumbnail: Bitmap? =
                                            Bitmap.createBitmap(
                                                ThumbnailRepository[app].THUMBNAIL_SIZE,
                                                ThumbnailRepository[app].THUMBNAIL_SIZE,
                                                Bitmap.Config.ARGB_8888
                                            )
                                        val lightCanvas = Canvas(lightThumbnail!!)
                                        var darkThumbnail: Bitmap? =
                                            Bitmap.createBitmap(
                                                ThumbnailRepository[app].THUMBNAIL_SIZE,
                                                ThumbnailRepository[app].THUMBNAIL_SIZE,
                                                Bitmap.Config.ARGB_8888
                                            )
                                        val darkCanvas = Canvas(darkThumbnail!!)

                                        try {
                                            val track = Track.parseFrom(ride.file).restrictTo(null)

                                            val countryCode = withTimeout(1.minutes) {
                                                getCountryCode(
                                                    app, Location("center").apply {
                                                        wholeThumbnailArea.apply {
                                                            latitude =
                                                                minLatD + ((maxLatD - minLatD) / 2)
                                                            longitude =
                                                                minLonD + ((maxLonD - minLonD) / 2)
                                                        }
                                                    }
                                                ).first()
                                            }

                                            mapBigDecimal(
                                                wholeThumbnailArea.minLat,
                                                wholeThumbnailArea.maxLat,
                                                tileWidth
                                            ) { lat ->
                                                mapBigDecimal(
                                                    wholeThumbnailArea.minLon,
                                                    wholeThumbnailArea.maxLon,
                                                    tileWidth
                                                ) { lon ->
                                                    MapArea(
                                                        lat, lon, lat + tileWidth, lon + tileWidth
                                                    )
                                                }
                                            }.flatten().asFlow().flatMapMerge(2) { renderArea ->
                                                MapDataRepository[app].getMapData(
                                                    renderArea,
                                                    returnPartialData = false,
                                                    loadStreetNames = false,
                                                    lowPriority = true
                                                ).map { renderArea to it }.take(1)
                                            }.collect { (renderArea, mapData) ->
                                                val lightRender = async {
                                                    val renderer =
                                                        ThumbnailRepository[app].ThumbnailRenderer(
                                                            ride.area,
                                                            false,
                                                            renderArea
                                                        )
                                                    renderer.setMapData(countryCode, mapData, track)
                                                    renderer.draw(lightCanvas)
                                                    renderer.recycle()
                                                }

                                                val darkRender = async {
                                                    val renderer =
                                                        ThumbnailRepository[app].ThumbnailRenderer(
                                                            ride.area,
                                                            true,
                                                            renderArea
                                                        )
                                                    renderer.setMapData(countryCode, mapData, track)
                                                    renderer.draw(darkCanvas)
                                                    renderer.recycle()
                                                }

                                                lightRender.await()
                                                darkRender.await()
                                            }
                                        } catch (e: IOException) {
                                            DebugLog.e(
                                                TAG,
                                                "Could not generated thumbnail(s) for ${ride.file.name}",
                                                e
                                            )
                                            lightThumbnail = null
                                            darkThumbnail = null
                                            delay(1.seconds)
                                        } catch (e: TimeoutCancellationException) {
                                            DebugLog.e(
                                                TAG,
                                                "Could not generated thumbnail(s) for ${ride.file.name}",
                                                e
                                            )
                                            lightThumbnail = null
                                            darkThumbnail = null
                                            delay(1.minutes)
                                        }

                                        if (lightThumbnail != null && darkThumbnail != null) {
                                            // Always persist both thumbnails, otherwise it might cancel
                                            // this collection before the second one is written.
                                            withContext(NonCancellable) {
                                                ThumbnailRepository[app].persistThumbnail(
                                                    lightThumbnail, ride, false
                                                )
                                                ThumbnailRepository[app].persistThumbnail(
                                                    darkThumbnail, ride, true
                                                )

                                                DebugLog.v(
                                                    TAG,
                                                    "Generated thumbnail(s) for ${ride.file.name}"
                                                )
                                            }
                                        }
                                    }
                                } finally {
                                    DebugLog.i(
                                        TAG,
                                        "Finished thumbnailPrefetcher:$ridesWithoutThumbnails"
                                    )
                                }
                            }
                        }
                }

                launch {
                    var isJobScheduled = false

                    (PhoneStateRepository[app].processPriority.flow + isPrefetchWorkPending.filterNotNull()).collect { (processPriority, isPrefetchWorkPending) ->
                        val jobService = app.getSystemService(JobScheduler::class.java)

                        if (isPrefetchWorkPending && !isJobScheduled && processPriority <= FOREGROUND) {
                            jobService.schedule(
                                JobInfo.Builder(
                                    JobIds.PREFETCHER.ordinal,
                                    ComponentName(app, PrefetcherJobService::class.java)
                                ).setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                                    .setRequiresBatteryNotLowCompat(true)
                                    .setRequiresStorageNotLowCompat(true)
                                    .build()
                            )

                            DebugLog.i(TAG, "scheduled prefetcher job")
                            isJobScheduled = true
                        }

                        if (!isPrefetchWorkPending && isJobScheduled) {
                            jobService.cancel(JobIds.PREFETCHER.ordinal)
                            DebugLog.i(TAG, "canceled prefetcher job")
                            isJobScheduled = false
                        }
                    }
                }
            }
        }
    }

    /**
     * Start the [Prefetcher].
     */
    fun start() {
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    companion object : SingleParameterSingletonOf<BycoApplication, Prefetcher>({ Prefetcher(it) })
}