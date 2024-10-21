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

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.location.Location
import android.os.FileObserver.*
import android.view.LayoutInflater
import android.view.View
import androidapp.byco.BycoApplication
import androidapp.byco.THUMBNAILS_DIRECTORY
import androidapp.byco.lib.R
import androidapp.byco.ui.views.MapView
import androidapp.byco.util.CountryCode
import androidapp.byco.util.Repository
import androidapp.byco.util.SingleParameterSingletonOf
import androidapp.byco.util.Trigger
import androidapp.byco.util.compat.WEBP_COMPAT
import androidapp.byco.util.compat.createFileObserverCompat
import androidapp.byco.util.plus
import androidapp.byco.util.stateIn
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import lib.gpx.BasicLocation
import lib.gpx.MapArea
import lib.gpx.RecordedLocation
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.TimeUnit.DAYS

/** Data and action related to thumbnails of [PreviousRide]s */
@OptIn(ExperimentalCoroutinesApi::class)
class ThumbnailRepository private constructor(
    private val app: BycoApplication,
    val THUMBNAIL_SIZE: Int = 1024, // px
    private val THUMBNAIL_MIN_EDGE: Int = 30, // px
    private val THUMBNAIL_MAX_EDGE: Int = 60, // px
    private val MAX_AGE: Long = DAYS.toMillis(365) // ms
) : Repository(app) {
    /** Create a map view showing the whole [rideArea] */
    @SuppressLint("InflateParams")
    private fun getMapView(context: Context, rideArea: MapArea, width: Int, height: Int): MapView {
        val map = context.getSystemService(LayoutInflater::class.java)
            .inflate(R.layout.thumbnail, null) as MapView

        map.measure(
            View.MeasureSpec.makeMeasureSpec(
                width,
                View.MeasureSpec.EXACTLY
            ),
            View.MeasureSpec.makeMeasureSpec(
                height,
                View.MeasureSpec.EXACTLY
            )
        )
        map.layout(0, 0, map.measuredWidth, map.measuredHeight)

        map.animateToLocation(
            Location("center").apply {
                latitude = (rideArea.maxLatD + rideArea.minLatD) / 2
                longitude = (rideArea.maxLonD + rideArea.minLonD) / 2
            }, animate = false
        )

        val topLeft = BasicLocation(rideArea.maxLatD, rideArea.minLonD)

        val bottomRight = BasicLocation(rideArea.minLatD, rideArea.maxLonD)

        map.setZoomToInclude(
            topLeft,
            bottomRight,
            THUMBNAIL_MIN_EDGE,
            THUMBNAIL_MAX_EDGE,
        )

        return map
    }

    /** Get square area containing the [rideArea] */
    internal fun getThumbnailArea(
        rideArea: MapArea,
        width: Int = THUMBNAIL_SIZE,
        height: Int = THUMBNAIL_SIZE
    ): MapArea {
        val mapView = getMapView(app, rideArea, width, height)

        val topLeft = mapView.run { MapView.MapCoordinates(0f, 0f).toLocation() }
        val bottomRight = mapView.run {
            MapView.MapCoordinates(
                width.toFloat(),
                height.toFloat()
            ).toLocation()
        }

        val tileScale = MapDataRepository.TILE_SCALE
        return MapArea(
            BigDecimal(bottomRight.latitude).setScale(tileScale, RoundingMode.FLOOR),
            BigDecimal(topLeft.longitude).setScale(tileScale, RoundingMode.FLOOR),
            BigDecimal(topLeft.latitude).setScale(tileScale, RoundingMode.CEILING),
            BigDecimal(bottomRight.longitude).setScale(tileScale, RoundingMode.CEILING)
        )
    }

    /** Render the thumbnail for a `rideArea` showing the `mapData` and highlighting the `track` */
    inner class ThumbnailRenderer(
        rideArea: MapArea,
        isDarkMode: Boolean,
        clipArea: MapArea? = null,
        width: Int = THUMBNAIL_SIZE,
        height: Int = THUMBNAIL_SIZE,
    ) {
        private val map = getMapView(app.createConfigurationContext(Configuration().apply {
            uiMode =
                (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (isDarkMode) {
                    Configuration.UI_MODE_NIGHT_YES
                } else {
                    Configuration.UI_MODE_NIGHT_NO
                }
        }), rideArea, width, height)

        init {
            clipArea?.let { map.clipArea = it }
        }

        /**
         * Set an prepare the data to be drawn.
         */
        suspend fun setMapData(
            countryCode: CountryCode = null,
            mapData: MapData = emptySet(),
            track: TrackAsNodes = emptyList(),
            trackColor: Int? = null,
        ) {
            map.visibleTrack = track
            trackColor?.let { map.setCurrentRideColor(it) }

            map.setMapDataSync(countryCode, mapData)
        }

        /**
         * Draw the thumbnail onto [dst].
         */
        fun draw(
            dst: Canvas,
            background: Drawable? = null
        ) {
            if (background != null) {
                map.background = background
                map.draw(dst)
            } else {
                map.onDrawForeground(dst)
            }
        }

        /**
         * Recycle internal data.
         */
        fun recycle() {
            map.recycle()
        }
    }

    suspend fun renderThumbnail(
        countryCode: CountryCode,
        mapData: MapData,
        rideArea: MapArea,
        track: TrackAsNodes,
        isDarkMode: Boolean,
        clipArea: MapArea? = null,
        background: Drawable? = null,
        width: Int = THUMBNAIL_SIZE,
        height: Int = THUMBNAIL_SIZE,
        trackColor: Int? = null
    ): Bitmap {
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val renderer = ThumbnailRenderer(rideArea, isDarkMode, clipArea, width, height)
        renderer.setMapData(countryCode, mapData, track, trackColor)
        renderer.draw(Canvas(image), background)
        renderer.recycle()

        return image
    }

    private val updateThumbnailTrigger = Trigger(repositoryScope)

    /**
     * Persist the [thumbnail] for a [ride].
     *
     * The created thumbnails will be stored on disk and can be read via [getThumbnail]
     */
    internal fun persistThumbnail(
        thumbnail: Bitmap,
        ride: PreviousRide,
        isDarkMode: Boolean,
    ) {
        val tmp = File(app.cacheDir, "tmp.png")
        tmp.outputStream().buffered().use { out -> thumbnail.compress(WEBP_COMPAT, 90, out) }
        tmp.renameTo(getThumbnailFileFor(ride, isDarkMode))

        // File observers are unreliable, hence force update of thumbnails
        updateThumbnailTrigger.trigger()
    }

    private fun getThumbnailFileFor(ride: PreviousRide, isDarkMode: Boolean): File {
        File(app.cacheDir, THUMBNAILS_DIRECTORY).mkdirs()

        return File(
            File(app.cacheDir, THUMBNAILS_DIRECTORY),
            "${ride.file.name.removeSuffix(".gpx.zip")}-${
                if (isDarkMode) {
                    "dark"
                } else {
                    "light"
                }
            }.png"
        )
    }

    /**
     * Flow whether there is a persisted thumbnail for this [ride].
     *
     * @see persistThumbnail
     */
    fun getHasThumbnail(ride: PreviousRide, isDarkMode: Boolean) = callbackFlow {
        suspend fun update() {
            send(getThumbnailFileFor(ride, isDarkMode).exists())
        }

        val directoryObserver = createFileObserverCompat(
            getThumbnailFileFor(
                ride,
                isDarkMode
            ).parentFile!!
        ) { event: Int, _: String? ->
            if (event in setOf(CLOSE_WRITE, MOVED_TO, MOVED_FROM, DELETE)) {
                launch {
                    update()
                }
            }
        }
        launch {
            updateThumbnailTrigger.flow.collect {
                update()
            }
        }

        directoryObserver.startWatching()

        awaitClose { directoryObserver.stopWatching() }
    }.stateIn(getThumbnailFileFor(ride, isDarkMode).exists())

    /**
     * Get thumbnail with [highlightStart] meters of the beginning of the track and [highlightEnd]
     * of the eng highlighed in `R.color.removed_ride`.
     */
    suspend fun getThumbnailWithHighlightedStartAndEnd(
        ride: PreviousRide,
        isDarkMode: Boolean,
        highlightStart: Float,
        highlightEnd: Float
    ) = (getThumbnail(
        ride,
        isDarkMode
    ) + PreviousRidesRepository[app].getTrack(ride)).mapLatest { (thumbnail, track) ->
        track?.let { fullTrack ->
            val highlightedTrack = mutableListOf<MutableList<Node>>()

            var startHighlighted = 0f
            for (segment in fullTrack.segments) {
                var lastHighlightedNode: RecordedLocation? = null

                highlightedTrack.add(mutableListOf())
                for (node in segment) {
                    if (lastHighlightedNode != null) {
                        startHighlighted += lastHighlightedNode.distanceTo(node)
                    }

                    highlightedTrack.last().add(node.toNode())
                    lastHighlightedNode = node

                    if (startHighlighted >= highlightStart) {
                        break
                    }
                }

                if (startHighlighted >= highlightStart) {
                    break
                }
            }

            var endHighlighted = 0f
            for (segment in fullTrack.segments.asReversed()) {
                var lastHighlightedNode: RecordedLocation? = null

                highlightedTrack.add(mutableListOf())
                for (node in segment.asReversed()) {
                    if (lastHighlightedNode != null) {
                        endHighlighted += lastHighlightedNode.distanceTo(node)
                    }

                    highlightedTrack.last().add(node.toNode())
                    lastHighlightedNode = node

                    if (endHighlighted >= highlightEnd) {
                        break
                    }
                }

                if (endHighlighted >= highlightEnd) {
                    break
                }
            }

            renderThumbnail(
                null,
                emptySet(),
                ride.area,
                highlightedTrack.map { 0f to it.toTypedArray() },
                isDarkMode,
                background = BitmapDrawable(app.resources, thumbnail),
                trackColor = app.getColor(R.color.removed_ride)
            )
        } ?: thumbnail
    }

    /**
     * Get thumbnail for a [ride].
     *
     * This might be a temporary, lower quality one until a high quality one is created via
     * [persistThumbnail].
     */
    fun getThumbnail(ride: PreviousRide, isDarkMode: Boolean) =
        getHasThumbnail(ride, isDarkMode).flatMapLatest { hasThumbnail ->
            if (hasThumbnail) {
                flowOf(
                    BitmapFactory.decodeFile(
                        getThumbnailFileFor(
                            ride,
                            isDarkMode
                        ).absolutePath
                    )
                ).flowOn(IO)
            } else {
                PreviousRidesRepository[app].getTrack(ride).mapLatest { track ->
                    if (track == null) {
                        Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8)
                    } else {
                        renderThumbnail(
                            null,
                            emptySet(),
                            ride.area,
                            track.restrictTo(null),
                            isDarkMode
                        )
                    }
                }
            }
        }

    init {
        File(app.cacheDir, THUMBNAILS_DIRECTORY).mkdirs()

        // Redo thumbnails occasionally as the map data might have changed
        File(app.cacheDir, THUMBNAILS_DIRECTORY).listFiles()?.forEach { thumbnail ->
            if (thumbnail.lastModified() + MAX_AGE < System.currentTimeMillis()) {
                thumbnail.delete()
            }
        }

        repositoryScope.launch {
            // Wait until we are sure previousRides was loaded
            val validRides = PreviousRidesRepository[app].previousRides.first { it.isNotEmpty() }

            // Remove thumbnails of rides that do not exist anymore
            val validThumbnailFiles = validRides.flatMap { ride ->
                listOf(
                    getThumbnailFileFor(ride, true),
                    getThumbnailFileFor(ride, false)
                )
            }.toSet()

            File(app.cacheDir, THUMBNAILS_DIRECTORY).listFiles()?.filter {
                !validThumbnailFiles.contains(it)
            }?.forEach { it.delete() }
        }
    }

    companion object :
        SingleParameterSingletonOf<BycoApplication, ThumbnailRepository>({ ThumbnailRepository(it) })
}