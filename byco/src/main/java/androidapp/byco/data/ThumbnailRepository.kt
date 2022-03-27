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
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.location.Location
import android.os.Build
import android.os.FileObserver
import android.view.LayoutInflater
import android.view.View
import androidapp.byco.THUMBNAILS_DIRECTORY
import androidapp.byco.lib.R
import androidapp.byco.ui.views.MapView
import androidapp.byco.util.AsyncLiveData
import androidapp.byco.util.CountryCode
import androidapp.byco.util.SingleParameterSingletonOf
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import lib.gpx.BasicLocation
import lib.gpx.MapArea
import lib.gpx.RecordedLocation
import lib.gpx.Track
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.TimeUnit.DAYS

/** Data and action related to thumbnails of [PreviousRide]s */
class ThumbnailRepository private constructor(
    private val app: Application,
    private val THUMBNAIL_SIZE: Int = 1024, // px
    private val THUMBNAIL_MIN_EDGE: Int = 30, // px
    private val THUMBNAIL_MAX_EDGE: Int = 60, // px
    private val MAX_AGE: Long = DAYS.toMillis(365) // ms
) {
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

        map.animateToLocation(Location("center").apply {
            latitude = (rideArea.maxLatD + rideArea.minLatD) / 2
            longitude = (rideArea.maxLonD + rideArea.minLonD) / 2
        })

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

    /** Render the thumbnail for a [rideArea] showing the [mapData] and highlighting the [track] */
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
        mapColor: Int? = null,
        trackColor: Int? = null
    ): Bitmap {
        val map = getMapView(app.createConfigurationContext(Configuration().apply {
            uiMode =
                (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (isDarkMode) {
                    Configuration.UI_MODE_NIGHT_YES
                } else {
                    Configuration.UI_MODE_NIGHT_NO
                }
        }), rideArea, width, height)

        val bitmap = Bitmap.createBitmap(
            map.measuredWidth,
            map.measuredHeight, Bitmap.Config.ARGB_8888
        )

        background?.let { map.background = it }
        clipArea?.let { map.clipArea = it }
        map.visibleTrack = track
        mapColor?.let { map.setBackgroundColor(it) }
        trackColor?.let { map.setCurrentRideColor(it) }
        map.setMapDataSync(countryCode, mapData)
        map.draw(Canvas(bitmap))

        return bitmap
    }

    /**
     * Persist the [thumbnail] for a [ride].
     *
     * The created thumbnails will be stored on disk and can be read via [getThumbnail]
     */
    internal suspend fun persistThumbnail(
        thumbnail: Bitmap,
        ride: PreviousRide,
        isDarkMode: Boolean,
    ) {
        val tmp = File(app.cacheDir, "tmp.png")
        tmp.outputStream().buffered().use { out ->
            thumbnail.compress(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }, 90, out
            )
        }
        tmp.renameTo(getThumbnailFileFor(ride, isDarkMode))

        // File observers are unreliable, hence call the live datas manually
        withContext(NonCancellable + Main.immediate) {
            activeHasThumbnailLiveDatas.forEach { it.update() }
        }
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

    // Only modified on main thread
    private val activeHasThumbnailLiveDatas = mutableListOf<HasThumbnailLiveData>()

    private inner class HasThumbnailLiveData(val ride: PreviousRide, val isDarkMode: Boolean) :
        LiveData<Boolean>() {
        // Support API23
        @Suppress("DEPRECATION")
        private val directoryObserver =
            object : FileObserver(
                getThumbnailFileFor(
                    ride,
                    isDarkMode
                ).parentFile!!.absolutePath
            ) {
                override fun onEvent(event: Int, path: String?) {
                    if (event in setOf(CLOSE_WRITE, MOVED_TO, MOVED_FROM, DELETE)) {
                        update()
                    }
                }
            }

        override fun onActive() {
            super.onActive()

            activeHasThumbnailLiveDatas.add(this)
            directoryObserver.startWatching()
            update()
        }

        override fun onInactive() {
            super.onInactive()

            activeHasThumbnailLiveDatas.remove(this)
            directoryObserver.startWatching()
        }

        fun update() {
            val hasThumbnail = getThumbnailFileFor(ride, isDarkMode).exists()
            if (value != hasThumbnail) {
                postValue(hasThumbnail)
            }
        }
    }

    /**
     * LiveData whether there is a persisted thumbnail for this [ride].
     *
     * @see persistThumbnail
     */
    fun getHasThumbnail(ride: PreviousRide, isDarkMode: Boolean): LiveData<Boolean> {
        return HasThumbnailLiveData(ride, isDarkMode)
    }

    /**
     * Get thumbnail with [highlightStart] meters of the beginning of the track and [highlightEnd]
     * of the eng highlighed in `R.color.removed_ride`.
     */
    fun getThumbnailWithHighlightedStartAndEnd(
        ride: PreviousRide,
        isDarkMode: Boolean,
        highlightStart: Float,
        highlightEnd: Float
    ) = object : AsyncLiveData<Bitmap>() {
        private val thumbnail = getThumbnail(ride, isDarkMode)
        private val track = PreviousRidesRepository[app].getTrack(ride)

        init {
            requestUpdateIfChanged(thumbnail, track)
        }

        override suspend fun update(): Bitmap? {
            val thumbnail = thumbnail.value ?: return null
            return track.value?.let { fullTrack ->
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
                for (segment in fullTrack.segments.reversed()) {
                    var lastHighlightedNode: RecordedLocation? = null

                    highlightedTrack.add(mutableListOf())
                    for (node in segment.reversed()) {
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
    }

    /**
     * Get thumbnail for a [ride].
     *
     * This might be a temporary, lower quality one until a high quality one is created via
     * [persistThumbnail].
     */
    fun getThumbnail(ride: PreviousRide, isDarkMode: Boolean): LiveData<Bitmap> {
        return object : AsyncLiveData<Bitmap>(IO) {
            private val hasThumbnail = getHasThumbnail(ride, isDarkMode)
            private var trackLiveData: LiveData<Track>? = null

            init {
                requestUpdateIfChanged(hasThumbnail)
            }

            override suspend fun update(): Bitmap? {
                if (hasThumbnail.value == true) {
                    return BitmapFactory.decodeFile(
                        getThumbnailFileFor(
                            ride,
                            isDarkMode
                        ).absolutePath
                    )
                } else {
                    return when {
                        trackLiveData == null -> {
                            withContext(Main) {
                                trackLiveData = PreviousRidesRepository[app].getTrack(ride)
                                    .also { addSource(it) { requestUpdate() } }
                            }

                            null
                        }
                        trackLiveData?.value != null -> {
                            val thumbnail = renderThumbnail(
                                null,
                                emptySet(),
                                ride.area,
                                trackLiveData!!.value!!.restrictTo(null),
                                isDarkMode
                            )

                            withContext(Main + NonCancellable) {
                                trackLiveData?.let { oldTrackLiveData ->
                                    // don't need track anymore
                                    removeSource(oldTrackLiveData)
                                    trackLiveData = null
                                }
                            }

                            thumbnail
                        }
                        else -> {
                            null
                        }
                    }
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

        PreviousRidesRepository[app].previousRides.observeForever(
            object : Observer<List<PreviousRide>> {
                override fun onChanged(rides: List<PreviousRide>?) {
                    if (rides != null) {
                        // Remove thumbnails of rides that do not exist anymore
                        val validThumbnailFiles = rides.flatMap { ride ->
                            listOf(
                                getThumbnailFileFor(ride, true),
                                getThumbnailFileFor(ride, false)
                            )
                        }.toSet()

                        File(app.cacheDir, THUMBNAILS_DIRECTORY).listFiles()?.filter {
                            !validThumbnailFiles.contains(it)
                        }?.forEach { it.delete() }

                        // Only remove thumbnails once per instance of the app
                        PreviousRidesRepository[app].previousRides.removeObserver(this)
                    }
                }
            })
    }

    companion object :
        SingleParameterSingletonOf<Application, ThumbnailRepository>({ ThumbnailRepository(it) })
}