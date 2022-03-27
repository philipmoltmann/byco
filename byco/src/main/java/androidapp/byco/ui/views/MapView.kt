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

package androidapp.byco.ui.views

import android.content.Context
import android.graphics.*
import android.graphics.Color.*
import android.graphics.drawable.Drawable
import android.location.Location
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidapp.byco.data.*
import androidapp.byco.data.OsmDataProvider.BicycleType.DESIGNATED
import androidapp.byco.data.OsmDataProvider.BicycleType.DISMOUNT
import androidapp.byco.data.OsmDataProvider.HighwayType
import androidapp.byco.data.OsmDataProvider.HighwayType.*
import androidapp.byco.lib.R
import androidapp.byco.util.CountryCode
import androidapp.byco.util.areBicyclesAllowedByDefault
import androidapp.byco.util.rotationBetweenBearings
import androidx.annotation.ColorInt
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.withMatrix
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.Main
import lib.gpx.BasicLocation
import lib.gpx.DebugLog
import lib.gpx.MapArea
import java.math.BigDecimal
import java.math.BigDecimal.ZERO
import kotlin.coroutines.coroutineContext
import kotlin.math.*

// [Way] types in order of painting (first is on top)
val DISPLAYED_HIGHWAY_TYPES = listOf(
    CYCLEWAY,    // On top as these are the preferred ways
    PATH,        // |
    PEDESTRIAN,  // |
    FOOTWAY,     // |
    BRIDALWAY,   // | (may be paved and hence like a regular path)
    TRACK,       // L
    MOTORWAY,
    TRUNK,
    PRIMARY,
    SECONDARY,
    RESIDENTIAL_DESIGNATED, // above all residential ways
    TERTIARY,
    UNCLASSIFIED,
    ROAD,
    RESIDENTIAL,
    LIVING_STEET,
    SERVICE,
    MOTORWAY_LINK,  // below every street
    TRUNK_LINK,     // |
    PRIMARY_LINK,   // |
    SECONDARY_LINK, // |
    TERTIARY_LINK,  // L
    BAD_PATH, // below everything, as only usable in emergencies
).reversed()

/**
 * A view showing a map.
 *`
 * The data shown on the map set via [setMapData] and the orientation and center of the map is set
 * via [animateToLocation]. The zoom can be set via the [zoom] property or the [setZoomToInclude]
 * method.
 *
 * Styling of the roads of the map is done via the attributes of this view.
 */
class MapView(
    context: Context,
    attrs: AttributeSet?,
    defStyle: Int,
    defStyleRes: Int
) : View(context, attrs, defStyle, defStyleRes) {
    private val TAG = MapView::class.java.simpleName

    private val CAMERA_Z = 15f
    private val MAX_FPS = 30

    private val antiAliasingPaint = Paint().apply { isAntiAlias = true }

    private var currentRidePaint = Paint()
    private var pathPaint = Paint()
    private var pathDesignatedPaint = Paint()
    private var pathBadSurfacePaint = Paint()
    private var highTrafficPaint = Paint()
    private var highTrafficNoBicyclesPaint = Paint()
    private var motorWayPaint = Paint()
    private var residentialPaint = Paint()
    private var residentialDesignatedPaint = Paint()
    private var residentialNoBicyclesPaint = Paint()

    private var badSurfaceWayIndicatorColor = BLACK
    private var currentRideIndicatorColor = BLACK
    private var defaultWayIndicatorColor = BLACK
    private var designatedWayIndicatorColor = BLACK
    private var highTrafficWayIndicatorColor = BLACK
    private var noBicyclesWayIndicatorColor = BLACK

    private var onewayIndicator: Drawable? = null
    private var onewayIndicatorFrequency = 0.001

    private val styledAttrs =
        context.theme.obtainStyledAttributes(attrs, R.styleable.MapView, defStyle, defStyleRes)

    /** DP of the [center] on the view, 0==bottom */
    private val centerFromBottomDim = styledAttrs.run {
        if (getType(R.styleable.MapView_centerFromBottom) == TypedValue.TYPE_DIMENSION) {
            getDimensionPixelOffset(R.styleable.MapView_centerFromBottom, 0).toFloat()
        } else {
            null
        }
    }
    private val centerFromBottomFraction = styledAttrs.run {
        if (getType(R.styleable.MapView_centerFromBottom) == TypedValue.TYPE_FRACTION) {
            getFraction(R.styleable.MapView_centerFromBottom, 1, 1, 0f)
        } else {
            null
        }
    }

    /**
     * Precomputed transformation that tilts (==3d effect) the map by [tilt].
     *
     * @see updateTiltTransformation
     */
    private val tiltMatrix = Matrix()
    private val tilt = styledAttrs.getFloat(R.styleable.MapView_tilt, 0f)

    /** Job used to move the [center] in [animateToLocation] */
    private var centerInterpolator: Job? = null
    private var centerUpdater: Job? = null

    private class FrameBuffer(
        val frame: Bitmap,
        val transform: Matrix
    ) {
        fun recycle() {
            frame.recycle()
        }
    }

    /** Next frame to render, or {@code null} if the data has not changed */
    // @MainThread
    private var nextFrame: FrameBuffer? = null

    /**
     * Job rendering frame
     * @see requestRenderFrame
     */
    // @MainThread
    private var frameRenderer: Job? = null

    /** Frame that was last drawn */
    // @MainThread
    private var lastDrawnFrame: FrameBuffer? = null

    /** Cached value of absolute coordinates of [center] */
    private var centerAbsolute = AbsoluteCoordinates(0, 0)

    /** Last boundaries sent to the [boundaryChangedListeners] */
    private var lastBoundaries = MapArea(ZERO, ZERO, ZERO, ZERO)

    /**
     * [setMapData] ordered by [DISPLAYED_HIGHWAY_TYPES] with the appropriate [Paint] for each [Way]
     */
    private var waysByType = emptyMap<HighwayType, MutableList<Pair<Way, Paint>>>()

    /** Callback to receive intermediate locations when [animateToLocation] is used */
    // @MainThread
    var animatedLocationListener = mutableListOf<(Location) -> Unit>()

    /** Callback to receive current boundaries of map */
    // @MainThread
    val boundaryChangedListeners = mutableListOf<(MapArea) -> Unit>()

    /**
     * Select the are to clip the rendering to
     */
    var clipArea: MapArea? = null
        set(value) {
            field = value
            requestRenderFrame()
        }

    /**
     * Set [Way]s to be displayed and render the frame.
     *
     * The processing of the [newMapData] is expensive, hence prefer to use [setMapData]
     */
    suspend fun setMapDataSync(countryCode: CountryCode, newMapData: MapData) {
        waysByType = processMapData(countryCode, newMapData)

        // Immediately render frame on this thread
        withContext(Main.immediate) {
            frameRenderer?.cancelAndJoin()
        }
        renderFrame()
    }

    /**
     * Set [Way]s to be displayed.
     *
     * (expensive processing of map data happens on coroutine)
     */
    suspend fun setMapData(countryCode: CountryCode, newMapData: MapData) {
        val processedMapData = processMapData(countryCode, newMapData)

        withContext(Main) {
            waysByType = processedMapData
            requestRenderFrame()
        }
    }

    /**
     * Process new map data into a format that can be drawn very efficiently.
     *
     * @see renderFrame
     */
    private fun processMapData(countryCode: CountryCode, newMapData: MapData):
            Map<HighwayType, MutableList<Pair<Way, Paint>>> {
        /** Get drawing priority [DISPLAYED_HIGHWAY_TYPES] or `null` for "do not draw" */
        fun Way.getDisplayedHighwayType(): HighwayType? {
            val areBicyclesAllowed by lazy {
                bicycle?.isAllowed
                    ?: (highway?.areBicyclesAllowedByDefault(countryCode) == true)
            }

            return when (highway) {
                CYCLEWAY ->
                    when {
                        surface?.isPaved != false -> CYCLEWAY
                        surface.isCycleable -> PATH
                        else -> BAD_PATH
                    }
                MOTORWAY, TRUNK, PRIMARY, SECONDARY, LIVING_STEET, MOTORWAY_LINK,
                TRUNK_LINK, PRIMARY_LINK, SECONDARY_LINK -> highway
                TERTIARY, UNCLASSIFIED, RESIDENTIAL, TERTIARY_LINK, ROAD ->
                    when (bicycle) {
                        DESIGNATED -> RESIDENTIAL_DESIGNATED
                        else -> highway
                    }
                PEDESTRIAN, PATH, TRACK, BRIDALWAY, FOOTWAY ->
                    when {
                        bicycle == DESIGNATED -> highway
                        bicycle == DISMOUNT -> null
                        !areBicyclesAllowed -> null
                        surface?.isPaved == true -> highway
                        surface?.isCycleable == true -> BAD_PATH
                        else -> null
                    }
                SERVICE ->
                    when {
                        !areBicyclesAllowed -> null
                        service?.shouldBeShown == true -> SERVICE
                        service?.shouldBeShown == false -> null
                        // Unnamed service ways are often driveways and the like -> don't
                        // display
                        name == null -> null
                        else -> SERVICE
                    }
                else -> throw IllegalStateException("Unknown way type $highway")
            }
        }

        /** Get how to draw way */
        fun Way.getPaint(): Paint {
            val areBicyclesAllowed by lazy {
                bicycle?.isAllowed
                    ?: (highway?.areBicyclesAllowedByDefault(countryCode) == true)
            }

            return when (highway) {
                CYCLEWAY -> when {
                    // Assume paved and cycleable unless specified otherwise. Also show as path if
                    // surface is not perfect
                    surface?.isPaved != false -> pathDesignatedPaint
                    surface.isCycleable -> pathPaint
                    else -> pathBadSurfacePaint
                }
                PEDESTRIAN, FOOTWAY, PATH, TRACK, BRIDALWAY -> when {
                    // If bikes are not allowed way was already filtered out in
                    // [getDisplayedHighwayType]
                    // Assume unpaved unless specified otherwise. Only show as path is paved.
                    surface?.isPaved == true && bicycle == DESIGNATED -> pathDesignatedPaint
                    surface?.isPaved == true -> pathPaint
                    else -> pathBadSurfacePaint
                }
                MOTORWAY, MOTORWAY_LINK -> motorWayPaint
                TRUNK, TRUNK_LINK, PRIMARY, PRIMARY_LINK, SECONDARY, SECONDARY_LINK ->
                    when (areBicyclesAllowed) {
                        true -> highTrafficPaint
                        false -> highTrafficNoBicyclesPaint
                    }
                TERTIARY, TERTIARY_LINK, UNCLASSIFIED, RESIDENTIAL, LIVING_STEET, SERVICE,
                ROAD ->
                    when {
                        bicycle == DESIGNATED -> residentialDesignatedPaint
                        !areBicyclesAllowed -> residentialNoBicyclesPaint
                        else -> residentialPaint
                    }
                else -> throw IllegalStateException("Unknown way type $highway")
            }
        }

        val newWaysByType = mutableMapOf<HighwayType, MutableList<Pair<Way, Paint>>>()
        newMapData.forEach { way ->
            way.getDisplayedHighwayType()?.let { displayedWayType ->
                val ways = newWaysByType[displayedWayType]
                    ?: mutableListOf<Pair<Way, Paint>>().also {
                        newWaysByType[displayedWayType] = it
                    }
                ways.add(way to way.getPaint())
            }
        }

        return newWaysByType
    }

    /**
     * Maximum fps for animations, esp. animating to a new location.
     *
     * @see animateToLocation
     */
    var maxFps = MAX_FPS
        set(value) {
            field = min(MAX_FPS, value)
        }

    /** The location to center the map around. This is usually changed via [animateToLocation]. */
    @VisibleForTesting
    var center: Location? = null
        set(value) {
            field = value

            updateCenter()
            onBoundaryChanged()

            requestRenderFrame()
        }

    /**
     * Current zoom level as defined in Mercator projection.
     *
     * @see BasicLocation.toAbsolute
     */
    private var zoom: Float = styledAttrs.getFloat(R.styleable.MapView_zoom, 18f)
        set(value) {
            field = value

            updateWayPaints()
            updateCenter()
            onBoundaryChanged()

            requestRenderFrame()
        }

    /** Track highlighted on map */
    var visibleTrack: TrackAsNodes? = null
        set(value) {
            field = value
            requestRenderFrame()
        }

    /** Update [center] */
    private fun updateCenter() {
        centerAbsolute = if (center == null) {
            AbsoluteCoordinates(0, 0)
        } else {
            center!!.toAbsolute()
        }
    }

    /**
     * Callback when boundaries of the map displayed changed
     */
    private fun onBoundaryChanged() {
        val newBoundaries = getBoundaries()
        if (newBoundaries != lastBoundaries) {
            boundaryChangedListeners.toList().forEach { listener ->
                listener(newBoundaries)
            }

            lastBoundaries = newBoundaries
        }
    }

    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : this(
        context,
        attrs,
        defStyle,
        0
    )

    init {
        setBackgroundColor(styledAttrs.getColor(R.styleable.MapView_mapColor, WHITE))
        updateWayPaints()
    }

    private fun updateWayPaints() {
        styledAttrs.apply {
            val commonWayPaint = Paint().apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                isAntiAlias = true
            }

            val dpsPerPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                1f,
                resources.displayMetrics
            )

            // way width are in dp at zoom level 18
            val zoomAdj = 2f.pow(18f - zoom)

            motorWayPaint = Paint(commonWayPaint).apply {
                strokeWidth =
                    getFloat(R.styleable.MapView_motorWayWidth, 35f) / zoomAdj * dpsPerPx
                color = getColor(R.styleable.MapView_noBicyclesWayColor, BLACK)
            }
            highTrafficPaint = Paint(commonWayPaint).apply {
                strokeWidth =
                    getFloat(R.styleable.MapView_mayorWayWidth, 25f) / zoomAdj * dpsPerPx
                color = getColor(R.styleable.MapView_highTrafficWayColor, BLACK)
            }
            highTrafficNoBicyclesPaint = Paint(highTrafficPaint).apply {
                color = getColor(R.styleable.MapView_noBicyclesWayColor, BLACK)
            }
            residentialPaint = Paint(commonWayPaint).apply {
                strokeWidth = getFloat(
                    R.styleable.MapView_residentialWayWidth,
                    18f
                ) / zoomAdj * dpsPerPx
                color = getColor(R.styleable.MapView_defaultWayColor, BLACK)
            }
            residentialDesignatedPaint = Paint(residentialPaint).apply {
                color = getColor(R.styleable.MapView_designatedWayColor, BLACK)
            }
            residentialNoBicyclesPaint = Paint(residentialPaint).apply {
                color = getColor(R.styleable.MapView_noBicyclesWayColor, BLACK)
            }
            pathPaint = Paint(commonWayPaint).apply {
                strokeWidth =
                    getFloat(R.styleable.MapView_pathWidth, 10f) / zoomAdj * dpsPerPx
                color = getColor(R.styleable.MapView_defaultWayColor, BLACK)
            }
            pathDesignatedPaint = Paint(pathPaint).apply {
                color = getColor(R.styleable.MapView_designatedWayColor, BLACK)
            }
            pathBadSurfacePaint = Paint(pathPaint).apply {
                color = getColor(R.styleable.MapView_badSurfaceWayColor, BLACK)
            }

            currentRidePaint = Paint(commonWayPaint).apply {
                strokeWidth =
                    getFloat(
                        R.styleable.MapView_currentRideWidth,
                        18f
                    ) / if (getBoolean(
                            R.styleable.MapView_scaleCurrentRideWidth,
                            true
                        )
                    ) {
                        zoomAdj / dpsPerPx
                    } else {
                        1F
                    }
                color = getColor(R.styleable.MapView_currentRideColor, YELLOW)
            }

            badSurfaceWayIndicatorColor = getColor(R.styleable.MapView_badSurfaceWayIndicatorColor, BLACK)
            currentRideIndicatorColor = getColor(R.styleable.MapView_currentRideIndicatorColor, BLACK)
            defaultWayIndicatorColor =  getColor(R.styleable.MapView_defaultWayIndicatorColor, BLACK)
            designatedWayIndicatorColor =  getColor(R.styleable.MapView_designatedWayIndicatorColor, BLACK)
            highTrafficWayIndicatorColor =  getColor(R.styleable.MapView_highTrafficWayIndicatorColor, BLACK)
            noBicyclesWayIndicatorColor = getColor(R.styleable.MapView_noBicyclesWayIndicatorColor, BLACK)

            val onewayIndicatorRes =
                getResourceId(R.styleable.MapView_onewayIndicator, R.drawable.empty)
            if (onewayIndicatorRes == R.drawable.empty) {
                onewayIndicator = null
            } else {
                onewayIndicator = AppCompatResources.getDrawable(
                    context,
                    onewayIndicatorRes
                )
                onewayIndicatorFrequency =
                    getFloat(R.styleable.MapView_onewayIndicatorFrequency, 0.001f).toDouble()
            }
        }
    }

    /**
     * Animate to the new [location] within [animationDuration] ms.
     *
     * If [forcedBearing] is set, the map is rotated to fit the bearing.
     *
     * @see maxFps
     */
    fun animateToLocation(
        location: Location,
        forcedBearing: Float? = null,
        animationDuration: Long = 0
    ) {
        center?.let { start ->
            (context as AppCompatActivity).lifecycleScope.launch(Main.immediate) {
                centerInterpolator?.cancelAndJoin()

                val end = location

                val distance = start.distanceTo(end)

                val startBearing = start.bearing
                val rotation = rotationBetweenBearings(
                    startBearing, forcedBearing ?: start.bearingTo(end)
                )

                // minFps values empirically determined
                val minFpsForMovement = distance * 2
                val minFpsForRotation = abs(rotation * 8)

                val totalFrames = max(
                    1, min(
                        maxFps,
                        ceil(max(minFpsForMovement, minFpsForRotation)).toInt()
                    ) * animationDuration / 1000
                )

                val startTime = SystemClock.elapsedRealtime()
                // Run centerInterpolator not on main thread. Hence if the main thread is slow,
                // centerUpdater will not have run, the next time centerInterpolator runs and we
                // skip a frame
                centerInterpolator =
                    launch {
                        val newLocation = Location("interpolated")

                        for (i in 1..totalFrames) {
                            if (isActive) {
                                val elapsed = SystemClock.elapsedRealtime() - startTime
                                val desiredElapsed = i * (animationDuration / totalFrames)
                                delay(max(0, desiredElapsed - elapsed))

                                if (centerUpdater?.isCompleted == false) {
                                    centerUpdater?.cancel()
                                }
                                centerUpdater = launch(Main) {
                                    center = newLocation.apply {
                                        latitude =
                                            start.latitude * (totalFrames - i) / totalFrames +
                                                    end.latitude * i / totalFrames
                                        longitude =
                                            start.longitude * (totalFrames - i) / totalFrames +
                                                    end.longitude * i / totalFrames
                                        bearing = startBearing + rotation * i / totalFrames
                                    }

                                    animatedLocationListener.toList().forEach { listener ->
                                        listener(center!!)
                                    }
                                }
                            }
                        }
                    }
            }
        } ?: run {
            center = location

            animatedLocationListener.toList().forEach { listener ->
                listener(center!!)
            }
        }
    }

    /**
     * Set [zoom] so that [topLeft] and [bottomRight] image fit on map with free edges in between
     * [minEdge] px and [maxEdge] px
     */
    fun setZoomToInclude(
        topLeft: BasicLocation,
        bottomRight: BasicLocation,
        minEdge: Int,
        maxEdge: Int,
    ) {
        // TODO: Compute zoom instead of binary search
        var step = 10F
        zoom = 10F

        while (true) {
            step /= 2

            if (step < 0.001) {
                break
            }

            val topLeftMap = topLeft.toMap()
            val bottomRightMap = bottomRight.toMap()

            if (min(topLeftMap.x, topLeftMap.y) < minEdge
                || max(
                    bottomRightMap.x,
                    bottomRightMap.y
                ) > (width - minEdge)
            ) {
                zoom -= step
                continue
            }

            if (min(
                    topLeftMap.x,
                    topLeftMap.y
                ) > maxEdge
                || max(
                    bottomRightMap.x,
                    bottomRightMap.y
                ) < (width - maxEdge)
            ) {
                zoom += step
                continue
            }

            break
        }
    }

    /**
     * Get boundaries of the the map data so that a rotation will not cause any change to the
     * boundaries
     */
    private fun getBoundaries(): MapArea {
        var minLat = 1000.0
        var maxLat = -1000.0
        var minLon = 1000.0
        var maxLon = -1000.0

        val viewRadius = hypot(
            viewPort.topLeft.x - ((viewPort.bottomLeft.x + viewPort.bottomRight.x) / 2.0),
            viewPort.topLeft.y - viewPort.bottomLeft.y.toDouble()
        )

        for (mapEdge in arrayOf(
            MapCoordinates(
                viewPort.centerMap.x - viewRadius,
                viewPort.centerMap.y - viewRadius
            ),
            MapCoordinates(
                viewPort.centerMap.x + viewRadius,
                viewPort.centerMap.y + viewRadius
            )
        )) {
            val location = mapEdge.toLocation()

            minLat = min(minLat, location.latitude)
            maxLat = max(maxLat, location.latitude)
            minLon = min(minLon, location.longitude)
            maxLon = max(maxLon, location.longitude)
        }

        val scale = MapDataRepository.TILE_SCALE

        return MapArea(
            minLat.toBigDecimal().setScale(scale, BigDecimal.ROUND_FLOOR),
            minLon.toBigDecimal().setScale(scale, BigDecimal.ROUND_FLOOR),
            maxLat.toBigDecimal().setScale(scale, BigDecimal.ROUND_CEILING),
            maxLon.toBigDecimal().setScale(scale, BigDecimal.ROUND_CEILING)
        )
    }

    private val centerOnScreen: FloatArray
    get() = floatArrayOf(
        width / 2f, when {
            centerFromBottomDim != null -> {
                height - centerFromBottomDim
            }
            centerFromBottomFraction != null -> {
                height - (height * centerFromBottomFraction)
            }
            else -> {
                0f
            }
        }
    )

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        if (changed) {
            tiltMatrix.reset()
            viewPort = ViewPort.UNSET

            updateTiltTransformation()
            updateViewPort()

            if (center != null) {
                onBoundaryChanged()
            }
        }
    }

    private fun updateTiltTransformation() {
        // Tilt map by [tilt]
        // TODO: Don't use Camera class
        Camera().apply {
            setLocation(0f, 0f, -CAMERA_Z)
            rotateX(tilt)

            getMatrix(tiltMatrix)
        }

        tiltMatrix.preTranslate(-width.toFloat() / 2, -height.toFloat() / 2)
        tiltMatrix.postTranslate(width.toFloat() / 2, height.toFloat() / 2)
    }

    private fun updateViewPort() {
        val screenEdges = floatArrayOf(
            0f, 0f,
            width.toFloat(), 0f,
            0f, height.toFloat(),
            width.toFloat(), height.toFloat()
        )

        val inv = Matrix()
        tiltMatrix.invert(inv)
        inv.mapPoints(screenEdges)

        val centerOnScreenCopy = centerOnScreen.copyOf()

        inv.mapPoints(centerOnScreenCopy)
        val centerViewYOffset = centerOnScreenCopy[1] - screenEdges[1]

        viewPort = ViewPort(
            PointF(screenEdges[0], screenEdges[1]),
            PointF(screenEdges[2], screenEdges[3]),
            PointF(screenEdges[4], screenEdges[5]),
            PointF(screenEdges[6], screenEdges[7]),
            MapCoordinates((screenEdges[2] - screenEdges[0]) / 2, centerViewYOffset)
        )
    }

    /**
     * Current [ViewPort]
     *
     * @see updateViewPort
     * */
    private var viewPort = ViewPort.UNSET

    /**
     * Matrix to rotate and tilt (==3d-effect) the map
     *
     * Tilt is fixed via [tiltMatrix] and rotation is read from [center].
     */
    private fun getTiltAndRotateTransformation(): Matrix {
        val t = Matrix(tiltMatrix)

        // Rotate map around center by center.bearing
        // TODO: Make less ugly, once I understand what is happening
        center?.let { center ->
            t.apply {
                preTranslate(-viewPort.leftOffset, -viewPort.topOffset)

                preConcat(Matrix().apply {
                    val inv = Matrix().apply {
                        preTranslate(viewPort.centerMap.x, viewPort.centerMap.y)
                        preRotate(center.bearing)
                        preTranslate(-viewPort.centerMap.x, -viewPort.centerMap.y)
                    }
                    val b = inv.invert(this)
                    if (!b) {
                        throw Exception()
                    }
                })
            }
        }

        return t
    }

    /**
     * Request re-rendering of frame. Similar to [invalidate] for regular views.
     *
     * Skips rendering frame if there is another rendering currently in progress, hence might miss
     * last rendering.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun requestRenderFrame() {
        val coroutineScope = if (context is AppCompatActivity) {
            (context as AppCompatActivity).lifecycleScope
        } else {
            GlobalScope
        }

        coroutineScope.launch(Main) {
            if (frameRenderer == null) {
                frameRenderer = launch(Default) {
                    try {
                        renderFrame()
                    } finally {
                        withContext(Main + NonCancellable) {
                            frameRenderer = null
                        }
                    }
                }
            } else {
                DebugLog.v(TAG, "Skipped rendering frame")
            }
        }
    }

    @VisibleForTesting
    suspend fun renderFrame() {
        // Copy all view state on the main thread into "input"
        class RenderInput(
            val width: Int,
            val height: Int,
            val center: Location?,
            val clipArea: MapArea?,
            val waysByType: Map<HighwayType, MutableList<Pair<Way, Paint>>>,
            val visibleTrack: TrackAsNodes?,
            val tiltAndRotate: Matrix,
            val currentRidePaint: Paint,
            val centerAbsolute: AbsoluteCoordinates
        )

        val input = withContext(Main.immediate) {
            RenderInput(
                width,
                height,
                center,
                clipArea,
                waysByType,
                visibleTrack,
                getTiltAndRotateTransformation(),
                currentRidePaint,
                centerAbsolute
            )
        }

        if (input.width == 0 || input.height == 0) {
            return
        }

        input.center?.let {
            val tiltAndRotate = input.tiltAndRotate
            val invTiltAndRotate = Matrix()
            val wasInverted = tiltAndRotate.invert(invTiltAndRotate)
            assert(wasInverted)

            // Find where in the untransformed map the view would map to.
            val screenEdges = floatArrayOf(
                0f, 0f,
                width.toFloat(), 0f,
                0f, height.toFloat(),
                width.toFloat(), height.toFloat()
            )
            invTiltAndRotate.mapPoints(screenEdges)

            // First draw ways on untransformed map and the transform the whole map at once. Somehow
            // drawing with a transformed canvas [Canvas.withMatrix] causes artifacts on some
            // devices (E.g. Pixel 3XL on Android Q). This adds 1.8ms (Pixel 4a, suburban map,
            // 1024x1024) and thereby doubles the time spent in [onDrawForeground] though.
            val minX = minOf(screenEdges[0], screenEdges[2], screenEdges[4], screenEdges[6])
            val minY = minOf(screenEdges[1], screenEdges[3], screenEdges[5], screenEdges[7])
            val unTransformedMapWidth = ceil(
                maxOf(screenEdges[0], screenEdges[2], screenEdges[4], screenEdges[6]) - minX
            ).toInt()
            val unTransformedMapHeight = ceil(
                maxOf(screenEdges[1], screenEdges[3], screenEdges[5], screenEdges[7]) - minY
            ).toInt()

            val unTransformedMap = Bitmap.createBitmap(
                unTransformedMapWidth,
                unTransformedMapHeight,
                Bitmap.Config.ARGB_8888
            )
            unTransformedMap.applyCanvas {
                // Reusing a single MapCoordinates class is saving allocations and shows to be
                // much cheaper
                fun MapCoordinates.setFromNode(node: Node) {
                    node.toAbsolute(zoom).toMap(this, input.centerAbsolute)
                }

                // Cliparea allows to only draw a partial map. Useful when drawing a thumbnail
                // one tile at a time
                input.clipArea?.let { clip ->
                    val clipPath = Path()

                    val start =
                        BasicLocation(clip.minLatD, clip.minLonD).toMap()
                    clipPath.moveTo(start.x, start.y)

                    fun Path.lineTo(lat: Double, lon: Double) {
                        val loc = BasicLocation(lat, lon).toMap()
                        lineTo(loc.x - minX, loc.y - minY)
                    }
                    clipPath.lineTo(clip.minLatD, clip.maxLonD)
                    clipPath.lineTo(clip.maxLatD, clip.maxLonD)
                    clipPath.lineTo(clip.maxLatD, clip.minLonD)
                    clipPath.close()

                    clipPath(clipPath)
                }

                fun indicateAlong(way: Array<Node>, wayPaint: Paint) {
                    val indicator = onewayIndicator!!.apply {
                        setTint(
                            when (wayPaint) {
                                pathBadSurfacePaint -> badSurfaceWayIndicatorColor
                                currentRidePaint -> currentRideIndicatorColor
                                residentialPaint, pathPaint -> defaultWayIndicatorColor
                                residentialDesignatedPaint, pathDesignatedPaint -> designatedWayIndicatorColor
                                highTrafficPaint -> highTrafficWayIndicatorColor
                                highTrafficNoBicyclesPaint, residentialNoBicyclesPaint, motorWayPaint -> noBicyclesWayIndicatorColor
                                else -> BLACK
                            }
                        )

                        // Scale to size of way and center around 0,0
                        setBounds(
                            -(wayPaint.strokeWidth / 2).toInt(), -(wayPaint.strokeWidth / 2).toInt(),
                            (wayPaint.strokeWidth / 2).toInt(), (wayPaint.strokeWidth / 2).toInt()
                        )
                    }

                    val rotateAndMove = Matrix()
                    val markerOnMap = MapCoordinates(0f,0f)

                    way.interpolateAlong(zoom, onewayIndicatorFrequency) { loc, bearing ->
                        loc.toMap(markerOnMap, input.centerAbsolute)

                        // Rotate in direction of way
                        rotateAndMove.setRotate(bearing.toFloat())

                        // Move to position of marker
                        rotateAndMove.postTranslate(markerOnMap.x - minX, markerOnMap.y - minY)

                        withMatrix(rotateAndMove) {
                            indicator.draw(this)
                        }
                    }
                }

                val paintedWay = Path()

                // Reusing the MapCoordinates avoid creation of a lot of temp objects
                // Maybe proguard can deal for us with this?
                val nodeOnMap = MapCoordinates(0f, 0f)

                DISPLAYED_HIGHWAY_TYPES.forEach { wayType ->
                    input.waysByType[wayType]?.forEach { (way, wayPaint) ->
                        coroutineContext.ensureActive()
                        paintedWay.reset()

                        // Creating the iterator to iterate through the list actually shows up as
                        // quite expensive. Iterating through an array does not need the iterator,
                        // hence turns out to be much faster.
                        //
                        // Maybe proguard can deal for us with this?
                        way.nodesArray.forEachIndexed { i, node ->
                            nodeOnMap.setFromNode(node)

                            if (i == 0) {
                                paintedWay.moveTo(nodeOnMap.x - minX, nodeOnMap.y - minY)
                            } else {
                                paintedWay.lineTo(nodeOnMap.x - minX, nodeOnMap.y - minY)
                            }
                        }

                        drawPath(paintedWay, wayPaint)
                    }

                    if (onewayIndicator != null) {
                        input.waysByType[wayType]?.forEach { (way, wayPaint) ->
                            // Motorways, large roads and links are usually one-directional, hence
                            // no need to show direction markers
                            if (way.isOneway && wayType !in setOf(
                                    MOTORWAY,
                                    MOTORWAY_LINK,
                                    TRUNK,
                                    TRUNK_LINK,
                                    PRIMARY,
                                    PRIMARY_LINK,
                                    SECONDARY_LINK,
                                    TERTIARY_LINK
                                )
                            ) {
                                coroutineContext.ensureActive()

                                indicateAlong(way.nodesArray, wayPaint)
                            }
                        }
                    }
                }

                paintedWay.reset()

                input.visibleTrack?.forEach { (_, segment) ->
                    paintedWay.reset()

                    // Creating the iterator to iterate through the list actually shows up as
                    // quite expensive. Iterating through an array does not need the iterator,
                    // hence turns out to be much faster.
                    //
                    // Maybe proguard can deal for us with this?
                    segment.forEachIndexed { i, node ->
                        nodeOnMap.setFromNode(node)

                        if (i == 0) {
                            paintedWay.moveTo(nodeOnMap.x - minX, nodeOnMap.y - minY)
                        } else {
                            paintedWay.lineTo(nodeOnMap.x - minX, nodeOnMap.y - minY)
                        }
                    }
                    drawPath(paintedWay, input.currentRidePaint)
                }

                if (onewayIndicator != null) {
                    input.visibleTrack?.forEach { (_, segment) ->
                        indicateAlong(segment, input.currentRidePaint)
                    }
                }
            }

            // Bitmaps cannot have negative coordinates, hence we drew the image slightly
            // offset; correct for that
            tiltAndRotate.preTranslate(minX, minY)

            withContext(Main.immediate) {
                if (lastDrawnFrame != null && lastDrawnFrame != nextFrame) {
                    lastDrawnFrame!!.recycle()
                    DebugLog.v(TAG, "Skipped drawing frame")
                }

                nextFrame?.recycle()
                nextFrame = FrameBuffer(unTransformedMap, tiltAndRotate)
                invalidate()
            }
        }
    }

    override fun onDrawForeground(canvas: Canvas) {
        nextFrame?.let { nextFrame ->
            // Tilt and rotate the frame to create a 3D effect
            canvas.drawBitmap(nextFrame.frame, nextFrame.transform, antiAliasingPaint)
        }
        lastDrawnFrame = nextFrame
    }

    /**
     * Map area visible in the view
     *
     * @see updateViewPort
     */
    private data class ViewPort(
        val topLeft: PointF,
        val topRight: PointF,
        val bottomLeft: PointF,
        val bottomRight: PointF,
        val centerMap: MapCoordinates
    ) {
        val leftOffset = -topLeft.x
        val topOffset = -topLeft.y

        companion object {
            val UNSET = ViewPort(
                PointF(0f, 0f),
                PointF(100f, 0f),
                PointF(0f, 100f),
                PointF(100f, 100f),
                MapCoordinates(0f, 0f)
            )
        }
    }

    @VisibleForTesting
    fun Location.toAbsolute(): AbsoluteCoordinates {
        return BasicLocation(latitude, longitude).toAbsolute()
    }

    @VisibleForTesting
    fun Location.toMap(): MapCoordinates {
        return toAbsolute().toMap()
    }

    /**
     * Converts a [BasicLocation] into absolute map coordinates using the Mercator projection for
     * the [zoom]-level of this [MapView].
     *
     * The map is not centered around [center]. For that use [AbsoluteCoordinates.toMap].
     */
    @VisibleForTesting
    fun BasicLocation.toAbsolute() = toAbsolute(zoom)

    @VisibleForTesting
    fun BasicLocation.toMap(): MapCoordinates {
        return toMap(centerAbsolute)
    }

    fun BasicLocation.toMap(center: AbsoluteCoordinates): MapCoordinates {
        return toAbsolute().toMap(center)
    }

    /**
     * X/Y Map coordinates according to mercator for the set [zoom] level, _not_ relative to
     * [center]
     */
    data class AbsoluteCoordinates(
        val x: Double,
        val y: Double
    ) {
        constructor(x: Int, y: Int) :
                this(x.toDouble(), y.toDouble())
    }

    @VisibleForTesting
    fun AbsoluteCoordinates.toLocation(): BasicLocation {
        val zoomAdj = 2f.pow(zoom)
        val mapHeight = 256 * zoomAdj
        val mapWidth = 512 * zoomAdj

        return BasicLocation(
            90 * ((4 * atan(exp(PI * (mapHeight - 2 * y) / mapWidth))) / PI - 1),
            360 * x / mapWidth - 180
        )
    }

    private fun AbsoluteCoordinates.toMap(): MapCoordinates {
        return toMap(centerAbsolute)
    }

    private fun AbsoluteCoordinates.toMap(center: AbsoluteCoordinates): MapCoordinates {
        return MapCoordinates(
            (x - center.x).toFloat() + viewPort.centerMap.x,
            (y - center.y).toFloat() + viewPort.centerMap.y
        )
    }

    private fun AbsoluteCoordinates.toMap(mapCoord: MapCoordinates, center: AbsoluteCoordinates) {
        mapCoord.x = (x - center.x).toFloat() + viewPort.centerMap.x
        mapCoord.y = (y - center.y).toFloat() + viewPort.centerMap.y
    }

    /** X/Y Coordinates on the map */
    class MapCoordinates(x: Float, y: Float) : PointF(x, y) {
        constructor(x: Double, y: Double) :
                this(x.toFloat(), y.toFloat())
    }

    private fun MapCoordinates.toAbsolute(): AbsoluteCoordinates {
        return AbsoluteCoordinates(
            x + centerAbsolute.x - viewPort.centerMap.x,
            y + centerAbsolute.y - viewPort.centerMap.y
        )
    }

    /** Get the [BasicLocation] of a point on the map */
    fun MapCoordinates.toLocation(): BasicLocation {
        return toAbsolute().toLocation()
    }

    /** Set color of current ride */
    fun setCurrentRideColor(@ColorInt color: Int) {
        currentRidePaint.apply {
            setColor(color)
        }
        requestRenderFrame()
    }
}