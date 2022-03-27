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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.net.Uri
import androidapp.byco.FixedLowPriorityThreads
import androidapp.byco.data.*
import androidapp.byco.util.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.applyCanvas
import androidx.lifecycle.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.consume
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import lib.gpx.BasicLocation
import lib.gpx.MapArea
import lib.gpx.toBasicLocation
import java.math.BigDecimal
import java.math.BigDecimal.ONE
import java.math.BigDecimal.TEN

/** ViewModel for [ConfirmDirectionsActivity] */
class ConfirmDirectionsActivityViewModel(
    private val app: Application,
    private val state: SavedStateHandle
) : AndroidViewModel(app) {
    private val START_LOCATION_KEY = "START_LOCATION_KEY"
    private val PREFETCH_PARALLEL = 3

    private val thumbnailRepo = ThumbnailRepository[app]

    /** Use to launch maps app via [gmmIntent] */
    private lateinit var mappingAppLauncher: ActivityResultLauncher<Intent>

    /** Intent to launch Google maps directions to [RouteFinderRepository.rideStart] */
    private val gmmIntent = object : MediatorLiveData<Intent>() {
        private val packageChangedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                update()
            }
        }

        init {
            addSource(RouteFinderRepository[app].rideStart) {
                update()
            }
        }

        private fun update() {
            val rideStart = RouteFinderRepository[app].rideStart.value
            if (rideStart == null) {
                value = null
                return
            }

            val gmmIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                    "google.navigation:q=${rideStart.latitude}," +
                            "${rideStart.longitude}&mode=b"
                )
            )
            gmmIntent.setPackage("com.google.android.apps.maps")

            val resolveInfo = app.packageManager.resolveActivity(gmmIntent, 0)
            value = if (resolveInfo == null) {
                null
            } else {
                gmmIntent
            }
        }

        override fun onActive() {
            super.onActive()

            app.registerReceiver(packageChangedReceiver,
                IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply {
                    addAction(Intent.ACTION_PACKAGE_CHANGED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addDataScheme("package")
                }
            )

            update()
        }

        override fun onInactive() {
            super.onInactive()

            app.unregisterReceiver(packageChangedReceiver)
        }
    }

    /** Should the activity show a button to launch the mapping app (via [openMappingApp]) ? */
    val shouldShowMappingAppButton = Transformations.map(gmmIntent) {
        it != null
    }

    /** Current location when this viewModel was created (persisted over life-cycle events) */
    private val persistedLocationAtInit = object : MediatorLiveData<BasicLocation>() {
        private val persistedState = state.getLiveData<BasicLocation>(START_LOCATION_KEY)

        init {
            if (persistedState.value != null) {
                value = persistedState.value!!
            } else {
                val locationSource = LocationRepository[app].location
                addSource(locationSource) {
                    val locBasicLoc = it.toBasicLocation()
                    persistedState.value = locBasicLoc
                    value = locBasicLoc

                    removeSource(locationSource)
                }
            }
        }
    }

    /** Searches for [route] */
    private val routeFinder =
        Transformations.switchMap(persistedLocationAtInit + RouteFinderRepository[app].rideStart) { (start, goal) ->
            if (start == null || goal == null) {
                null
            } else {
                // Current country code is probably valid, no need to re-resolve
                RouteFinderRepository[app].findRoute(
                    start,
                    goal,
                    providedCountryCode = LocationRepository[app].countryCode.value!!
                )
            }
        }

    /**
     * The directions found. Returns intermediate, partial routes while searching for the best
     * route.
     */
    private val route = Transformations.map(routeFinder) {
        if (it is ArrayList<BasicLocation>) {
            it
        } else {
            ArrayList(it)
        }
    }

    /** The rectangular area the [route] is in */
    private val routeAreaLiveDatas = mutableMapOf<Pair<Int, Int>, AsyncLiveData<MapArea>>()
    private fun getRouteArea(width: Int, height: Int) =
        routeAreaLiveDatas.getOrPut(width to height) {
            if (routeAreaLiveDatas.size > 2) {
                routeAreaLiveDatas.clear()
            }

            return object : AsyncLiveData<MapArea>() {
                init {
                    addSources(
                        route,
                        persistedLocationAtInit,
                        RouteFinderRepository[app].rideStart,
                        areDirectionsFound
                    ) {
                        requestUpdate()
                    }
                }

                override suspend fun update(): MapArea? {
                    val start = persistedLocationAtInit.value ?: return null
                    val goal = RouteFinderRepository[app].rideStart.value ?: return null

                    val locsOnMap = mutableListOf(start, goal)
                    route.value?.let { locsOnMap += it }

                    // Extend (never shrink) map area
                    if (areDirectionsFound.value != true) {
                        value?.let {
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
                    if (value == null) {
                        newArea = ThumbnailRepository[app].getThumbnailArea(newArea, width, height)
                    }

                    return if ((value == null
                                || !value!!.contains(newArea)
                                ) && newArea != value
                    ) {
                        newArea
                    } else {
                        null
                    }
                }
            }
        }

    abstract class BitmapLiveData(dispatcher: CoroutineDispatcher = Default) :
        AsyncLiveData<Pair<MapArea, Bitmap>>(dispatcher)

    /** Background image of [route] */
    private fun getRouteBackground(width: Int, height: Int, isDarkMode: Boolean) =
        object : BitmapLiveData() {
            private val TILE_WIDTH = ONE.divide(TEN.pow(MapDataRepository.TILE_SCALE))
            private val thread = FixedLowPriorityThreads.random()
            private var currentImageRouteArea: MapArea? = null
            private var image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            private val routeArea = getRouteArea(width, height)
            private var countryCode: String? = null
            private var returnValueUpdate = Semaphore(1)

            init {
                addSources(routeArea) {
                    requestUpdate()
                }
            }

            /** draw one map tile worth of data onto background */
            private suspend fun draw(routeArea: MapArea, tileKey: Pair<BigDecimal, BigDecimal>) {
                val (lat, lon) = tileKey
                val renderArea = MapArea(lat, lon, lat + TILE_WIDTH, lon + TILE_WIDTH)

                MapDataRepository[app].getMapData(
                    renderArea,
                    returnPartialData = false,
                    loadStreetNames = false,
                    lowPriority = true
                ).observeAsChannel().consume {
                    val mapData = receive()

                    returnValueUpdate.withPermit {
                        if (routeArea == currentImageRouteArea) {
                            val newBackground =
                                ThumbnailRepository[app].renderThumbnail(
                                    countryCode,
                                    mapData,
                                    routeArea,
                                    emptyList(),
                                    isDarkMode,
                                    renderArea,
                                    BitmapDrawable(app.resources, image),
                                    width,
                                    height
                                )
                            image = newBackground

                            postValue(routeArea to image)
                        }
                    }
                }
            }

            override suspend fun update(): Pair<MapArea, Bitmap>? {
                val routeArea = routeArea.value ?: return null
                postValue(null)

                // Just prevent the background to hold onto every tile and thereby completely
                // blocking the routefinding code
                val paralellismLimiter = Semaphore(32)

                withContext(thread) {
                    returnValueUpdate.withPermit {
                        currentImageRouteArea = routeArea
                    }

                    image.applyCanvas {
                        image.eraseColor(Color.TRANSPARENT)
                    }

                    val tilesArea =
                        ThumbnailRepository[app].getThumbnailArea(routeArea, width, height)

                    // Only use single country code as otherwise this would slow us down
                    if (countryCode == null) {
                        countryCode = getCountryCode(
                            app,
                            Location("center").apply {
                                latitude = (tilesArea.minLatD + tilesArea.maxLatD) / 2
                                longitude = (tilesArea.minLonD + tilesArea.maxLonD) / 2
                            },
                            isCurrentLocation = false
                        )
                    }

                    // As soon as the data is prefetched, draw each map tile
                    launch(Main) {
                        forBigDecimal(tilesArea.minLat, tilesArea.maxLat, TILE_WIDTH) { lat ->
                            forBigDecimal(tilesArea.minLon, tilesArea.maxLon, TILE_WIDTH) { lon ->
                                val tileKey = lat to lon
                                val isPrefetchedLD = MapDataRepository[app].isPrefetched(tileKey)
                                addSource(isPrefetchedLD) {
                                    if (it) {
                                        try {
                                            viewModelScope.launch(Default) {
                                                paralellismLimiter.withPermit {
                                                    draw(routeArea, tileKey)
                                                }
                                            }
                                        } finally {
                                            launch(NonCancellable + Main) {
                                                removeSource(isPrefetchedLD)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    repeat(PREFETCH_PARALLEL) {
                        // Prefetch in parallel to drawing. Once a tile is prefetched the map is drawn
                        // for this tile as the draw loop above has set up live-datas waiting for the
                        // prefetching to be done.
                        launch(IO) {
                            forBigDecimal(tilesArea.minLat, tilesArea.maxLat, TILE_WIDTH) { lat ->
                                forBigDecimal(
                                    tilesArea.minLon,
                                    tilesArea.maxLon,
                                    TILE_WIDTH
                                ) { lon ->
                                    MapDataRepository[app].preFetchMapDataTile(lat to lon, true)
                                }
                            }
                        }
                    }
                }
                return null
            }
        }

    /** Get image of [route] (no background) */
    private fun getRouteForeground(width: Int, height: Int, isDarkMode: Boolean) =
        object : BitmapLiveData() {
            private val routeArea = getRouteArea(width, height)

            init {
                addSources(routeArea, route) {
                    requestUpdate()
                }
            }

            override suspend fun update(): Pair<MapArea, Bitmap>? {
                val route = route.value ?: return null
                val routeArea = routeArea.value ?: return null

                return routeArea to thumbnailRepo.renderThumbnail(
                    /* does not matter as we are only rendering route */ "US",
                    /* no map data in foreground, all map data is in background */ emptySet(),
                    routeArea,
                    listOf(0f to Array(route.size) { i ->
                        val loc = route[i]

                        loc.toNode()
                    }),
                    isDarkMode,
                    width = width,
                    height = height,
                    // No background so that background can be seen below
                    mapColor = Color.TRANSPARENT
                )
            }
        }

    /* Get preview of route with foreground and background */
    fun getRoutePreview(width: Int, height: Int, isDarkMode: Boolean) =
        object : AsyncLiveData<Bitmap>() {
            private val background = getRouteBackground(width, height, isDarkMode)
            private val foreground = getRouteForeground(width, height, isDarkMode)
            private val bitmapDrawPaint = Paint()

            init {
                addSources(background, foreground) {
                    requestUpdate()
                }
            }

            override suspend fun update(): Bitmap? {
                val foreground = foreground.value
                val background = background.value

                if (foreground == null) {
                    if (background != null) {
                        return background.second
                    }
                } else {
                    if (background == null) {
                        return foreground.second
                    } else {
                        if (background.first == foreground.first) {
                            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                .applyCanvas {
                                    drawBitmap(background.second, 0F, 0F, bitmapDrawPaint)
                                    drawBitmap(foreground.second, 0F, 0F, bitmapDrawPaint)
                                }
                        }
                    }
                }
                return null
            }
        }

    /** Have directions for a route be found? */
    val areDirectionsFound =
        Transformations.map(route + RouteFinderRepository[app].rideStart) { (route, rideStart) ->
            route?.isEmpty() == true || route?.last() == rideStart
        }

    /** Is it impossible to find directions ? */
    val cannotFindDirections = Transformations.map(route) { it != null && it.size <= 1 }

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
