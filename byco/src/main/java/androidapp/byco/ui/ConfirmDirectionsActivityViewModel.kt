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

import android.app.Application
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import androidapp.byco.LowPriority
import androidapp.byco.data.LocationRepository
import androidapp.byco.data.MapDataRepository
import androidapp.byco.data.MapTileKey
import androidapp.byco.data.PhoneStateRepository
import androidapp.byco.data.RouteFinderRepository
import androidapp.byco.data.ThumbnailRepository
import androidapp.byco.data.toNode
import androidapp.byco.util.BycoViewModel
import androidapp.byco.util.compat.resolveActivityCompat
import androidapp.byco.util.mapBigDecimalSuspend
import androidapp.byco.util.plus
import androidapp.byco.util.stateIn
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.applyCanvas
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import lib.gpx.BasicLocation
import lib.gpx.DebugLog
import lib.gpx.MapArea
import java.lang.ref.WeakReference
import java.math.BigDecimal.ONE
import java.math.BigDecimal.TEN
import androidx.core.net.toUri
import androidx.core.graphics.createBitmap

/** ViewModel for [ConfirmDirectionsActivity] */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmDirectionsActivityViewModel(
    application: Application,
    private val state: SavedStateHandle
) : BycoViewModel(application) {
    private val TAG = ConfirmDirectionsActivity::class.java.simpleName

    private val START_LOCATION_KEY = "START_LOCATION_KEY"
    private val PREFETCH_PARALLEL = 3

    private val thumbnailRepo = ThumbnailRepository[app]

    /** Use to launch maps app via [gmmIntent] */
    private lateinit var mappingAppLauncher: ActivityResultLauncher<Intent>

    /** Intent to launch Google maps directions to [RouteFinderRepository.rideStart] */
    private val gmmIntent =
        (RouteFinderRepository[app].rideStart + PhoneStateRepository[app].packageChanged).map { (rideStart, _) ->
            rideStart?.let {
                val gmmIntent = Intent(
                    Intent.ACTION_VIEW,
                    ("google.navigation:q=${rideStart.latitude}," +
                            "${rideStart.longitude}&mode=b").toUri()
                )
                gmmIntent.setPackage("com.google.android.apps.maps")

                app.packageManager.resolveActivityCompat(gmmIntent, 0)?.let { gmmIntent }
            }
        }.stateIn(null)

    /** Should the activity show a button to launch the mapping app (via [openMappingApp]) ? */
    val shouldShowMappingAppButton = gmmIntent.map {
        it != null
    }.stateIn(true)

    /** Current location when this viewModel was created (persisted over life-cycle events) */
    private val persistedLocationAtInit =
        LocationRepository[app].smoothedLocation.filterNotNull().take(1).map { smoothedLocation ->
            if (state.get<BasicLocation>(START_LOCATION_KEY) == null) {
                state[START_LOCATION_KEY] = smoothedLocation.location
            }
            state.get<BasicLocation>(START_LOCATION_KEY)
        }.stateIn(state.get<BasicLocation>(START_LOCATION_KEY))

    /** Searches for [route] */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val routeFinder =
        (persistedLocationAtInit + RouteFinderRepository[app].rideStart).distinctUntilChanged()
            .flatMapLatest { (start, goal) ->
                if (start == null || goal == null) {
                    flowOf(null)
                } else {
                    // Current country code is probably valid, no need to re-resolve
                    RouteFinderRepository[app].findRoute(
                        start,
                        goal,
                        providedCountryCode = LocationRepository[app].countryCode.value!!
                    )
                }
            }.stateIn(null)

    /**
     * The directions found. Returns intermediate, partial routes while searching for the best
     * route.
     */
    private val route = routeFinder.map {
        when (it) {
            null -> {
                null
            }

            is ArrayList<BasicLocation> -> {
                it
            }

            else -> {
                ArrayList(it)
            }
        }
    }.stateIn(null)

    /** The rectangular area the [route] is in */
    private val routeAreaFlowsMutex = Mutex()
    private val routeAreaFlows = mutableMapOf<Pair<Int, Int>, WeakReference<StateFlow<MapArea?>>>()
    private suspend fun getRouteArea(width: Int, height: Int): StateFlow<MapArea?> {
        routeAreaFlowsMutex.withLock {
            routeAreaFlows.keys.toMutableSet().forEach { key ->
                if (routeAreaFlows[key]?.get() == null) {
                    routeAreaFlows.remove(key)
                }
            }

            return routeAreaFlows[width to height]?.get() ?: run {
                val newAreaFlow = flow {
                    val start = persistedLocationAtInit.first() ?: return@flow
                    val goal = RouteFinderRepository[app].rideStart.first() ?: return@flow

                    var area: MapArea? = null

                    (route + areDirectionsFound).collect { (route, areDirectionsFound) ->
                        val locsOnMap = mutableListOf(start, goal)
                        route?.let { locsOnMap += it }

                        // Extend (never shrink) map area
                        if (!areDirectionsFound) {
                            area?.let {
                                locsOnMap += BasicLocation(it.minLatD, it.minLonD)
                                locsOnMap += BasicLocation(it.maxLatD, it.maxLonD)
                            }
                        }

                        var newArea = MapArea(
                            locsOnMap.minOf { it.latitude },
                            locsOnMap.minOf { it.longitude },
                            locsOnMap.maxOf { it.latitude },
                            locsOnMap.maxOf { it.longitude }
                        )

                        // Extend to square at beginning of search to avoid too many re-loads of
                        // background
                        if (area == null) {
                            newArea =
                                ThumbnailRepository[app].getThumbnailArea(newArea, width, height)
                        }

                        if ((area == null || !area!!.contains(newArea)) && newArea != area) {
                            emit(newArea)
                            area = newArea
                        }
                    }
                }.stateIn(null)

                routeAreaFlows[width to height] = WeakReference(newAreaFlow)

                newAreaFlow
            }
        }
    }

    /** Background image of [route] */
    private suspend fun getRouteBackground(width: Int, height: Int, isDarkMode: Boolean) =
        getRouteArea(width, height).flatMapLatest { routeArea ->
            if (routeArea == null) {
                flowOf(routeArea to createBitmap(width, height))
            } else {
                val returnValueUpdate = Mutex()
                val image = createBitmap(width, height)
                val canvas = Canvas(image)

                var delayingUpdateSince = 0L
                channelFlow {
                    val TILE_WIDTH = ONE.divide(TEN.pow(MapDataRepository.TILE_SCALE))
                    var countryCode: String? = null

                    /** draw one map tile worth of data onto background */
                    suspend fun draw(routeArea: MapArea, tileKey: MapTileKey) {
                        val (lat, lon) = tileKey
                        val renderArea = MapArea(lat, lon, lat + TILE_WIDTH, lon + TILE_WIDTH)

                        val mapData = MapDataRepository[app].getMapData(
                            renderArea,
                            lowPriority = true
                        ).first()

                        val renderer = thumbnailRepo.ThumbnailRenderer(
                            routeArea,
                            isDarkMode,
                            renderArea,
                            width,
                            height
                        )
                        renderer.setMapData(countryCode, mapData)

                        returnValueUpdate.withLock {
                            renderer.draw(canvas)
                        }

                        send(Unit)

                        renderer.recycle()
                    }

                    withContext(LowPriority) {
                        val tilesArea =
                            ThumbnailRepository[app].getThumbnailArea(routeArea, width, height)

                        // Only use single country code as otherwise this would slow us down
                        if (countryCode == null) {
                            countryCode = LocationRepository[app].countryCode.first()
                        }

                        fun tiles() = flow {
                            mapBigDecimalSuspend(
                                tilesArea.minLat,
                                tilesArea.maxLat,
                                TILE_WIDTH
                            ) { lat ->
                                mapBigDecimalSuspend(
                                    tilesArea.minLon,
                                    tilesArea.maxLon,
                                    TILE_WIDTH
                                ) { lon ->
                                    emit(MapTileKey(lat, lon))
                                }
                            }
                        }

                        // Just prevent the background to hold onto every tile and thereby completely
                        // blocking the routefinding code
                        val DRAW_PARALLEL = 32
                        val drawParalellismLimiter = Semaphore(DRAW_PARALLEL)

                        // As soon as the data is prefetched, draw each map tile
                        launch {
                            tiles().flatMapMerge(1024) { tileKey ->
                                // Wait for prefetched
                                MapDataRepository[app].isPrefetched(tileKey).filter { it }.take(1)
                                    .map { tileKey }
                            }.collect { tileKey ->
                                drawParalellismLimiter.acquire()
                                launch {
                                    draw(routeArea, tileKey)
                                    drawParalellismLimiter.release()
                                }
                            }
                        }

                        repeat(PREFETCH_PARALLEL) { parallel ->
                            // Prefetch in parallel to drawing. Once a tile is prefetched the map is drawn
                            // for this tile as the draw loop above has set up flows waiting for the
                            // prefetching to be done.
                            launch(IO) {
                                var skipCounter = parallel
                                tiles().collect { tileKey ->
                                    if (skipCounter == 0) {
                                        while (!MapDataRepository[app].isPrefetched(tileKey)
                                                .first()
                                        ) {
                                            val prefetched =
                                                MapDataRepository[app].preFetchMapDataTile(
                                                    tileKey,
                                                    true
                                                )

                                            if (!prefetched) {
                                                DebugLog.i(TAG, "failed to prefetch $tileKey")
                                                delay(100)
                                            } else {
                                                break
                                            }
                                        }
                                    }

                                    skipCounter = (skipCounter + 1) % PREFETCH_PARALLEL
                                }
                            }
                        }
                    }
                }.mapLatest {
                    val now = System.currentTimeMillis()
                    // Wait some time for newer data to come and cancel this transform.
                    // This way the data is only updated with 5 FPS reducing the overhead of the
                    // image copying.
                    delay(delayingUpdateSince - now + 200)

                    withContext(NonCancellable) {
                        returnValueUpdate.withLock {
                            routeArea to image.copy(image.config!!, false)
                        }.also {
                            delayingUpdateSince = now
                        }
                    }
                }
            }
        }

    /** Get image of [route] (no background) */
    private suspend fun getRouteForeground(width: Int, height: Int, isDarkMode: Boolean) =
        (getRouteArea(width, height) + route).mapLatest { (routeArea, route) ->
            if (route == null || routeArea == null) {
                null
            } else {
                routeArea to thumbnailRepo.renderThumbnail(
                    /* does not matter as we are only rendering route */ "US",
                    /* no map data in foreground, all map data is in background */
                    emptySet(),
                    routeArea,
                    listOf(0f to Array(route.size) { i ->
                        val loc = route[i]

                        loc.toNode()
                    }),
                    isDarkMode,
                    width = width,
                    height = height,
                )
            }
        }

    /* Get preview of route with foreground and background */
    suspend fun getRoutePreview(width: Int, height: Int, isDarkMode: Boolean) =
        (getRouteBackground(width, height, isDarkMode) + getRouteForeground(
            width,
            height,
            isDarkMode
        )).mapLatest { (background, foreground) ->
            val bitmapDrawPaint = Paint()

            if (foreground == null) {
                return@mapLatest background.second
            } else {
                if (background.first == foreground.first) {
                    return@mapLatest createBitmap(width, height).applyCanvas {
                        drawBitmap(background.second, 0F, 0F, bitmapDrawPaint)
                        drawBitmap(foreground.second, 0F, 0F, bitmapDrawPaint)
                    }
                }
            }
            null
        }

    /** Have directions for a route be found? */
    val areDirectionsFound =
        (route + RouteFinderRepository[app].rideStart).map { (route, rideStart) ->
            route?.isEmpty() == true || route?.last() == rideStart
        }.stateIn(false)

    /** Is it impossible to find directions ? */
    val cannotFindDirections =
        route.map { route -> route?.let { it.size <= 1 } ?: false }.stateIn(false)

    /** Cancel the directions finding dialog */
    fun cancel(activity: AppCompatActivity) {
        activity.setResult(AppCompatActivity.RESULT_CANCELED)
        activity.finish()
    }

    /** Register an activity as using this viewmodel */
    fun registerActivityResultsContracts(activity: AppCompatActivity) {
        mappingAppLauncher =
            activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                // do nothing
            }
    }

    /** Open external mapping app to find route directions instead */
    fun openMappingApp() {
        gmmIntent.value?.let {
            mappingAppLauncher.launch(it)
        }
    }

    /** Confirm route directions */
    fun confirm(activity: AppCompatActivity) {
        activity.setResult(
            AppCompatActivity.RESULT_OK,
            Intent().putParcelableArrayListExtra(EXTRA_DIRECTIONS, route.value!!)
        )
        activity.finish()
    }

    companion object {
        const val EXTRA_DIRECTIONS = "ConfirmDirectionsActivity.EXTRA_DIRECTIONS"
    }
}
