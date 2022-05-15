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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.Color.*
import android.graphics.drawable.Drawable
import android.location.Location
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidapp.byco.data.*
import androidapp.byco.data.OsmDataProvider.BicycleType.DESIGNATED
import androidapp.byco.data.OsmDataProvider.BicycleType.DISMOUNT
import androidapp.byco.data.OsmDataProvider.HighwayType
import androidapp.byco.data.OsmDataProvider.HighwayType.*
import androidapp.byco.lib.R
import androidapp.byco.util.*
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
import lib.gpx.toBasicLocation
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

private typealias Vector = DoubleArray

/**
 * Verify that the vector is valid
 */
private fun Vector.isValid(): Boolean {
    return size == 2
}

private fun vectorOf(x: Double, y: Double): Vector {
    return doubleArrayOf(x, y)
}

@JvmName("vectorOfInt")
private fun vectorOf(x: Int, y: Int): Vector {
    return vectorOf(x.toDouble(), y.toDouble())
}

@JvmName("vectorOfFloat")
private fun vectorOf(x: Float, y: Float): Vector {
    return vectorOf(x.toDouble(), y.toDouble())
}

/**
 * Scale vector down by a.
 */
private operator fun Vector.div(a: Double): DoubleArray {
    assert(isValid())
    return vectorOf(x / a, y / a)
}

private val Vector.x: Double
    get() {
        assert(isValid())
        return this[0]
    }

private val Vector.y: Double
    get() {
        assert(isValid())
        return this[1]
    }

private val Vector.length: Double
    get() {
        assert(isValid())
        return sqrt(x * x + y * y)
    }

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
    private val DRAGGED_CENTER_TIMEOUT = 5000L // ms
    private val ANIMATION_DURATION_AFTER_DRAGGED_CENTER = 1000L // ms

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
    private var locationIndicator: Drawable? = null
    private var locationIndicatorWidth = 0
    private var directionToCurrentIndicator: Drawable? = null
    private var directionToCurrentIndicatorWidth = 0

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

    /** Job used to move the [centerAndLocation] in [animateToLocation] */
    private var centerAndLocationInterpolator: Job? = null
    private var centerAndLocationUpdater: Job? = null

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

    /**
     * Ignore animation durations until a certain time. This is used to guarantee a smooth animation
     * even though new calls to [animateToLocation] come in.
     */
    private var ignoreAnimationDurationsUntil = 0L

    private data class TouchPointer(
        val id: Int,
        val screen: PointF
    ) {
        fun toDraggedTouchPointer(mapView: MapView): DraggedTouchPointer {
            return DraggedTouchPointer(
                id,
                mapView.run { this@TouchPointer.screen.toMap().toAbsolute() })
        }
    }

    private data class DraggedTouchPointer(
        val id: Int,
        var absolute: AbsoluteCoordinates
    )

    private data class DragState(
        /** [DraggedTouchPointer] involved in the current drag action. */
        val pointers: MutableList<DraggedTouchPointer>,

        /**
         * Is a dragged [center] (not [location]) set?
         *
         * @see onTouchEvent
         * @see dragState
         */
        var useDraggedCenter: Boolean,

        /** Once the user does not drag anymore, wait a little and then unset the [useDraggedCenter]. */
        var draggedCenterTimeout: Job?
    )

    /**
     * The state of the current drag.
     *
     * @see onTouchEvent
     */
    private val dragState = DragState(mutableListOf(), false, null)

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

    /** [center] and [location]. Set as pair to keep it in sync. This is usually changed via [animateToLocation]. */
    @VisibleForTesting
    var centerAndLocation: Pair<Location?, Location?> = null to null
        set(value) {
            field = value

            updateCenter()
            onBoundaryChanged()

            requestRenderFrame()
        }

    /** The location to center the map around. */
    private val center: Location?
        get() = centerAndLocation.first

    /** The current location of the user. */
    private val location: Location?
        get() = centerAndLocation.second

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

    private val MotionEvent.pointers: List<TouchPointer>
        get() {
            return (0 until pointerCount).map { i ->
                TouchPointer(getPointerId(i), PointF(getX(i), getY(i)))
            }
        }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_DOWN -> {
                event.pointers.filter { pointer -> pointer.id !in dragState.pointers.map { it.id } }
                    .map { newPointer ->
                        if (dragState.pointers.size < 2) {
                            dragState.pointers.add(newPointer.toDraggedTouchPointer(this))
                        }
                    }

                dragState.useDraggedCenter = true
                centerAndLocationUpdater?.cancel()
                centerAndLocationUpdater = null

                dragState.draggedCenterTimeout?.cancel()
                dragState.draggedCenterTimeout = null
            }
            MotionEvent.ACTION_MOVE -> {
                val move = dragState.pointers.mapNotNull { dragPointer ->
                    event.pointers.firstOrNull { it.id == dragPointer.id }?.let { touchPointer ->
                        dragPointer.absolute to touchPointer.toDraggedTouchPointer(this).absolute
                    }
                }

                if (move.isEmpty()) {
                    // a pointer that is not part of activeDragState moved
                } else {
                    val dragStart = move.map { (dragPointer, _) -> dragPointer }
                    val dragNow = move.map { (_, dragNow) -> dragNow }

                    val dragNowNoScale = if (dragNow.size == 1) {
                        dragNow
                    } else {
                        val centerDrag = AbsoluteCoordinates(
                            dragNow.averageOf { it.x },
                            dragNow.averageOf { it.y }
                        )

                        val dragScale = dragNow[0].distanceTo(dragNow[1]) /
                                dragStart[0].distanceTo(dragStart[1])

                        // To prevent scaling making sure distance between dragNow-points and
                        // dragStart-points is the same
                        dragNow.map { dragNowPoint ->
                            centerDrag + (dragNowPoint - centerDrag) / dragScale
                        }
                    }

                    val dragTransform = Matrix()
                    dragTransform.setPolyToPoly(
                        dragNowNoScale.flatMap { it.toFloatList() }.toFloatArray(), 0,
                        dragStart.flatMap { it.toFloatList() }.toFloatArray(), 0,
                        dragStart.size
                    )
                    assert(dragTransform.isAffine)

                    // Move center so that screen and absolute coordinates will align again. This
                    // way the screen -> coordinates mapping for the point under the center of the
                    // tracked pointers never changes. I.e. for a single finger it looks like the
                    // map is glued to the finger.
                    val newCenter = centerAbsolute.transform(dragTransform)

                    // To determine bearing change transform a point west of the center (bearing to
                    // center is 0)
                    val newWestPoint =
                        (centerAbsolute - vectorOf(0, 10000)).transform(dragTransform)

                    // Get bearing of the transformed points
                    val bearingChange = newCenter.toLocation().bearingTo(newWestPoint.toLocation())
                    val newBearing = canonicalizeBearing(center!!.bearing + bearingChange)

                    animateToLocation(
                        newCenter.toLocation().toLocation().apply {
                            bearing = newBearing
                        }, animate = false,
                        setCenterInsteadOfLocation = true
                    )
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val numPointersBefore = dragState.pointers.size

                dragState.pointers.retainAll { dragPointer ->
                    dragPointer.id != event.getPointerId(
                        event.actionIndex
                    )
                }

                if (numPointersBefore == 2 && dragState.pointers.size == 1) {
                    // When moving from a two point to a single point gesture the map would usually
                    // jump as the scaling is not ignored anymore. Hence update the dragState's
                    // absolute coordinates to match the current position, i.e. guarantee that at
                    // this time there wont be any jump.

                    dragState.pointers.forEach { dragPointer ->
                        event.pointers.firstOrNull { touchPointer -> touchPointer.id == dragPointer.id }
                            ?.let { touchPointer ->
                                dragPointer.absolute = touchPointer.screen.toMap().toAbsolute()
                            }
                    }
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                if (dragState.pointers.isNotEmpty()) {
                    dragState.pointers.clear()

                    dragState.draggedCenterTimeout?.cancel()
                    dragState.draggedCenterTimeout =
                        (context as AppCompatActivity).lifecycleScope.launch(Main.immediate) {
                            try {
                                delay(DRAGGED_CENTER_TIMEOUT)
                            } finally {
                                if (dragState.pointers.isEmpty()) {
                                    // Always unset the dragged center if there is no more dragging,
                                    // even if activity is closed
                                    dragState.useDraggedCenter = false
                                }
                            }

                            ignoreAnimationDurationsUntil =
                                SystemClock.elapsedRealtime() + ANIMATION_DURATION_AFTER_DRAGGED_CENTER
                            location?.let { location ->
                                animateToLocation(
                                    location,
                                    forcedBearing = location.bearing,
                                    animationDuration = ANIMATION_DURATION_AFTER_DRAGGED_CENTER,
                                    animate = true
                                )
                            }
                        }
                }
            }
        }

        DebugLog.i("Test", "action=${event.actionMasked}, ptrs=${dragState.pointers.map { it.id }}")


        return true
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

            badSurfaceWayIndicatorColor =
                getColor(R.styleable.MapView_badSurfaceWayIndicatorColor, BLACK)
            currentRideIndicatorColor =
                getColor(R.styleable.MapView_currentRideIndicatorColor, BLACK)
            defaultWayIndicatorColor = getColor(R.styleable.MapView_defaultWayIndicatorColor, BLACK)
            designatedWayIndicatorColor =
                getColor(R.styleable.MapView_designatedWayIndicatorColor, BLACK)
            highTrafficWayIndicatorColor =
                getColor(R.styleable.MapView_highTrafficWayIndicatorColor, BLACK)
            noBicyclesWayIndicatorColor =
                getColor(R.styleable.MapView_noBicyclesWayIndicatorColor, BLACK)

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

            locationIndicator = styledAttrs.getDrawable(R.styleable.MapView_locationIndicator)
            locationIndicatorWidth = styledAttrs.getDimensionPixelSize(
                R.styleable.MapView_locationIndicatorWidth,
                locationIndicator?.intrinsicWidth ?: 0
            )

            directionToCurrentIndicator =
                styledAttrs.getDrawable(R.styleable.MapView_directionToCurrentIndicator)
            directionToCurrentIndicatorWidth = styledAttrs.getDimensionPixelSize(
                R.styleable.MapView_directionToCurrentIndicatorWidth,
                directionToCurrentIndicator?.intrinsicWidth ?: 0
            )
        }
    }

    /** Set bearing for the direction indicator */
    var directionToCurrentIndicatorBearing: Float? = null
        set(value) {
            field = value
            requestRenderFrame()
        }

    /**
     * Animate to the new [location] within [animationDuration] ms.
     *
     * If [forcedBearing] is set, the map is rotated to fit the bearing.
     *
     * @see maxFps
     */
    fun animateToLocation(
        end: Location,
        forcedBearing: Float? = null,
        animationDuration: Long = 0,
        animate: Boolean = false,
        setCenterInsteadOfLocation: Boolean = false
    ) {
        val adjustedAnimationDuration =
            max(ignoreAnimationDurationsUntil - SystemClock.elapsedRealtime(), animationDuration)

        if (animate && location != null && center != null) {
            // Not supported
            assert(!setCenterInsteadOfLocation)

            data class AnimationSpec(
                val start: Location,
                val distance: Float,
                val startBearing: Float,
                val rotation: Float
            ) {
                fun step(i: Long, total: Long): Location {
                    return Location("interpolated").apply {
                        latitude =
                            start.latitude * (total - i) / total +
                                    end.latitude * i / total
                        longitude =
                            start.longitude * (total - i) / total +
                                    end.longitude * i / total
                        bearing = startBearing + rotation * i / total
                    }
                }
            }

            fun Location.animateTo(end: Location): AnimationSpec {
                return AnimationSpec(
                    this,
                    this.distanceTo(end),
                    this.bearing,
                    rotationBetweenBearings(
                        this.bearing, forcedBearing ?: this.bearingTo(end)
                    )
                )
            }

            val locationAnim = location!!.animateTo(end)
            val centerAnim = center!!.animateTo(end)

            (context as AppCompatActivity).lifecycleScope.launch(Main.immediate) {
                centerAndLocationInterpolator?.cancelAndJoin()

                // minFps values empirically determined
                val minFpsForMovement = max(locationAnim.distance * 2, centerAnim.distance * 2)
                val minFpsForRotation =
                    max(abs(locationAnim.rotation * 8), abs(centerAnim.rotation * 8))

                val totalFrames = max(
                    1, min(
                        maxFps,
                        ceil(max(minFpsForMovement, minFpsForRotation)).toInt()
                    ) * adjustedAnimationDuration / 1000
                )

                val startTime = SystemClock.elapsedRealtime()
                // Run centerInterpolator not on main thread. Hence if the main thread is slow,
                // centerUpdater will not have run, the next time centerInterpolator runs and we
                // skip a frame
                centerAndLocationInterpolator =
                    launch {
                        for (i in 1..totalFrames) {
                            if (isActive) {
                                val elapsed = SystemClock.elapsedRealtime() - startTime
                                val desiredElapsed = i * (adjustedAnimationDuration / totalFrames)
                                delay(max(0, desiredElapsed - elapsed))

                                if (centerAndLocationUpdater?.isCompleted == false) {
                                    centerAndLocationUpdater?.cancel()
                                }
                                centerAndLocationUpdater = launch(Main) {
                                    val newLocation = locationAnim.step(i, totalFrames)

                                    val newCenter = if (!dragState.useDraggedCenter) {
                                        centerAnim.step(i, totalFrames)
                                    } else {
                                        center
                                    }

                                    centerAndLocation = newCenter to newLocation

                                    animatedLocationListener.toList().forEach { listener ->
                                        listener(location!!)
                                    }
                                }
                            }
                        }
                    }
            }
        } else {
            val newLocation = if (!setCenterInsteadOfLocation) {
                end
            } else {
                location
            }

            val newCenter = if (!dragState.useDraggedCenter || setCenterInsteadOfLocation) {
                end
            } else {
                center
            }

            centerAndLocation = newCenter to newLocation

            animatedLocationListener.toList().forEach { listener ->
                listener(location!!)
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
            viewPort.centerMap - vectorOf(viewRadius, viewRadius),
            viewPort.centerMap + vectorOf(viewRadius, viewRadius)
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

    private val centerOnScreen: PointF
        get() = PointF(
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

        val centerOnScreenAsFloatArray = floatArrayOf(centerOnScreen.x, centerOnScreen.y)

        inv.mapPoints(centerOnScreenAsFloatArray)
        val centerViewYOffset = centerOnScreenAsFloatArray[1] - screenEdges[1]

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

        // Rotate map around center by [center].bearing
        // TODO: Make less ugly, once I understand what is happening
        center?.let {
            t.apply {
                preTranslate(-viewPort.leftOffset, -viewPort.topOffset)

                preConcat(Matrix().apply {
                    val inv = Matrix().apply {
                        preTranslate(viewPort.centerMap.x, viewPort.centerMap.y)
                        center?.let { center -> preRotate(center.bearing) }
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

    /** Convert screen coordinates to [MapCoordinates] */
    private fun screenToMapCoordinates(
        screenCoordinates: Array<PointF>,
        tiltAndRotate: Matrix
    ): Array<MapCoordinates> {
        val screenCoordinatesAsFlatFloat = FloatArray(screenCoordinates.size * 2) { i ->
            if (i % 2 == 0) {
                screenCoordinates[i / 2].x
            } else {
                screenCoordinates[i / 2].y
            }
        }

        val invTiltAndRotate = Matrix()
        val wasInverted = tiltAndRotate.invert(invTiltAndRotate)
        assert(wasInverted)

        invTiltAndRotate.mapPoints(screenCoordinatesAsFlatFloat)

        return Array(screenCoordinatesAsFlatFloat.size / 2) { i ->
            MapCoordinates(
                screenCoordinatesAsFlatFloat[i * 2],
                screenCoordinatesAsFlatFloat[i * 2 + 1]
            )
        }
    }

    private fun PointF.toMap(tiltAndRotate: Matrix = getTiltAndRotateTransformation()) =
        screenToMapCoordinates(arrayOf(this), tiltAndRotate)[0]

    @VisibleForTesting
    suspend fun renderFrame() {
        // Copy all view state on the main thread into "input"
        class RenderInput(
            val width: Int,
            val height: Int,
            val center: Location?,
            val location: Location?,
            val clipArea: MapArea?,
            val waysByType: Map<HighwayType, MutableList<Pair<Way, Paint>>>,
            val visibleTrack: TrackAsNodes?,
            val tiltAndRotate: Matrix,
            val currentRidePaint: Paint,
            val centerAbsolute: AbsoluteCoordinates,
        )

        val input = withContext(Main.immediate) {
            RenderInput(
                width,
                height,
                center,
                location,
                clipArea,
                waysByType,
                visibleTrack,
                getTiltAndRotateTransformation(),
                currentRidePaint,
                centerAbsolute,
            )
        }

        if (input.width == 0 || input.height == 0) {
            return
        }

        input.center?.let {
            // Find where in the untransformed map the view would map to.
            val screenEdges = screenToMapCoordinates(
                arrayOf(
                    PointF(0f, 0f),
                    PointF(width.toFloat(), 0f),
                    PointF(0f, height.toFloat()),
                    PointF(width.toFloat(), height.toFloat())
                ), input.tiltAndRotate
            )

            // First draw ways on untransformed map and the transform the whole map at once. Somehow
            // drawing with a transformed canvas [Canvas.withMatrix] causes artifacts on some
            // devices (E.g. Pixel 3XL on Android Q). This adds 1.8ms (Pixel 4a, suburban map,
            // 1024x1024) and thereby doubles the time spent in [onDrawForeground] though.
            val minX = screenEdges.minOf { it.x }
            val minY = screenEdges.minOf { it.y }
            val unTransformedMapWidth = ceil(screenEdges.maxOf { it.x } - minX).toInt()
            val unTransformedMapHeight = ceil(screenEdges.maxOf { it.y } - minY).toInt()

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

                fun drawRotatedDrawable(
                    drawable: Drawable,
                    locOfCenter: MapCoordinates,
                    rotation: Float,
                    widthOnScreen: Int
                ) {
                    val leftOnMap = PointF(
                        centerOnScreen.x - widthOnScreen.toFloat() / 2,
                        centerOnScreen.y
                    ).toMap(input.tiltAndRotate)

                    val rightOnMap = PointF(
                        centerOnScreen.x + widthOnScreen.toFloat() / 2,
                        centerOnScreen.y
                    ).toMap(input.tiltAndRotate)


                    val widthOnMap = leftOnMap.distanceTo(rightOnMap)
                    val height =
                        drawable.intrinsicHeight * widthOnMap / drawable.intrinsicWidth

                    drawable.setBounds(
                        (locOfCenter.x - minX - widthOnMap / 2).toInt(),
                        (locOfCenter.y - minY - height / 2).toInt(),
                        (locOfCenter.x - minX + widthOnMap / 2).toInt(),
                        (locOfCenter.y - minY + height / 2).toInt()
                    )

                    val rotate = Matrix().apply {
                        preTranslate(
                            locOfCenter.x - minX,
                            locOfCenter.y - minY
                        )
                        preRotate(rotation)
                        preTranslate(
                            -(locOfCenter.x - minX),
                            -(locOfCenter.y - minY)
                        )
                    }

                    withMatrix(rotate) {
                        drawable.draw(this)
                    }
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
                            -(wayPaint.strokeWidth / 2).toInt(),
                            -(wayPaint.strokeWidth / 2).toInt(),
                            (wayPaint.strokeWidth / 2).toInt(),
                            (wayPaint.strokeWidth / 2).toInt()
                        )
                    }

                    val rotateAndMove = Matrix()
                    val markerOnMap = MapCoordinates(0f, 0f)

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

                input.location?.let { location ->
                    val mapLocation = location.toBasicLocation().toMap(input.centerAbsolute)

                    locationIndicator?.let { locationIndicator ->
                        drawRotatedDrawable(
                            locationIndicator,
                            mapLocation,
                            location.bearing,
                            locationIndicatorWidth
                        )
                    }

                    directionToCurrentIndicator?.let { directionToCurrentIndicator ->
                        directionToCurrentIndicatorBearing?.let { directionToCurrentIndicatorBearing ->
                            drawRotatedDrawable(
                                directionToCurrentIndicator,
                                mapLocation,
                                (location.bearing) + directionToCurrentIndicatorBearing,
                                directionToCurrentIndicatorWidth
                            )
                        }
                    }
                }
            }

            // Bitmaps cannot have negative coordinates, hence we drew the image slightly
            // offset; correct for that
            val tiltAndRotateAndShift = input.tiltAndRotate
            tiltAndRotateAndShift.preTranslate(minX, minY)

            withContext(Main.immediate) {
                if (lastDrawnFrame != null && lastDrawnFrame != nextFrame) {
                    lastDrawnFrame!!.recycle()
                    DebugLog.v(TAG, "Skipped drawing frame")
                }

                nextFrame?.recycle()
                nextFrame = FrameBuffer(unTransformedMap, tiltAndRotateAndShift)
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

    private fun BasicLocation.toMap(center: AbsoluteCoordinates): MapCoordinates {
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

        constructor(x: Float, y: Float) :
                this(x.toDouble(), y.toDouble())

        constructor(values: FloatArray) :
                this(values[0], values[1])

        /** Distance to `other` in pixels on absolute map */
        fun distanceTo(other: AbsoluteCoordinates): Double {
            return (this - other).length
        }

        fun toFloatArray(): FloatArray {
            return floatArrayOf(x.toFloat(), y.toFloat())
        }

        fun toFloatList(): List<Float> {
            return listOf(x.toFloat(), y.toFloat())
        }

        /**
         * Move `this` by the vector `a`.
         */
        operator fun plus(a: Vector): AbsoluteCoordinates {
            assert(a.isValid())
            return AbsoluteCoordinates(x + a.x, y + a.y)
        }

        /**
         * Move `this` by the inverse of the vector `a`.
         */
        operator fun minus(a: Vector): AbsoluteCoordinates {
            assert(a.isValid())
            return AbsoluteCoordinates(x - a.x, y - a.y)
        }

        /**
         * Get vector between `this` and `a`.
         */
        operator fun minus(a: AbsoluteCoordinates): Vector {
            return doubleArrayOf(x - a.x, y - a.y)
        }

        /**
         * Project `this` by `m`
         */
        fun transform(m: Matrix): AbsoluteCoordinates {
            val dst = FloatArray(2)
            m.mapPoints(dst, this.toFloatArray())
            return AbsoluteCoordinates(dst)
        }
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
        return viewPort.centerMap + (this - center)
    }

    private fun AbsoluteCoordinates.toMap(mapCoord: MapCoordinates, center: AbsoluteCoordinates) {
        mapCoord.x = (x - center.x).toFloat() + viewPort.centerMap.x
        mapCoord.y = (y - center.y).toFloat() + viewPort.centerMap.y
    }

    /** X/Y Coordinates on the map */
    class MapCoordinates(x: Float, y: Float) : PointF(x, y) {
        constructor(x: Double, y: Double) :
                this(x.toFloat(), y.toFloat())

        fun distanceTo(other: MapCoordinates): Double {
            return (this - other).length
        }

        operator fun minus(a: MapCoordinates): Vector {
            return vectorOf(x - a.x, y - a.y)
        }

        operator fun minus(a: Vector): MapCoordinates {
            assert(a.isValid())
            return MapCoordinates(x - a.x, y - a.y)
        }

        operator fun plus(a: Vector): MapCoordinates {
            assert(a.isValid())
            return MapCoordinates(x + a.x, y + a.y)
        }
    }

    private fun MapCoordinates.toAbsolute(): AbsoluteCoordinates {
        return centerAbsolute + (this - viewPort.centerMap)
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