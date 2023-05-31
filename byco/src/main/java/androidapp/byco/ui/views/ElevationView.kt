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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import androidapp.byco.data.ElevationProgressNode
import androidapp.byco.lib.R
import kotlin.math.floor

/**
 * Shows the [elevations] as a height profile
 */
class ElevationView(
    context: Context,
    attrs: AttributeSet?,
    defStyle: Int,
    defStyleRes: Int
) : View(context, attrs, defStyle, defStyleRes) {
    private var MAX_ELEVATION_PTS = 64

    private var minEle = 0f
    private var maxEle = 0f
    private var progressEle = 0f

    /** Elevations to show */
    var elevations = NO_DATA
        set(v) {
            assert(v.size >= 2)
            assert(
                v.fold(null) { lastX: Float?, point ->
                    lastX?.let { assert(lastX <= point.progress) }
                    point.progress
                } != null)
            assert(v.first().progress < v.last().progress)

            if (v == field) {
                return
            }

            minEle = v.minOf { it.elevation }
            maxEle = v.maxOf { it.elevation }

            // Bucket the y values of the points into MAX_ELEVATION_PTS buckets
            val numPointsInBucket = arrayOfNulls<Int>(MAX_ELEVATION_PTS)
            val combinedY = arrayOfNulls<Float>(MAX_ELEVATION_PTS)

            v.forEach { p ->
                val i =
                    floor(
                        (p.progress - v.first().progress) / (v.last().progress - v.first().progress) *
                                (MAX_ELEVATION_PTS - 1)
                    ).toInt()
                if (numPointsInBucket[i] == null) {
                    numPointsInBucket[i] = 1
                    combinedY[i] = p.elevation
                } else {
                    numPointsInBucket[i] = numPointsInBucket[i]!! + 1
                    combinedY[i] = combinedY[i]!! + p.elevation
                }
            }

            // Set field to average of the buckets. This was the resulting elevation profile is not
            // overly complex in the case of complex input value
            processedElevations = combinedY.mapIndexedNotNull { i, y ->
                y?.let {
                    ElevationProgressNode(
                        y / numPointsInBucket[i]!!,
                        v.first().progress +
                                i.toFloat() / (MAX_ELEVATION_PTS - 1) * (v.last().progress - v.first().progress)
                    )
                }
            }.toTypedArray()

            field = v
        }

    private var processedElevations = NO_DATA.toTypedArray()
        set(v) {
            field = v
            invalidate()
        }

    /** Progress maker (in meters) to show */
    var progress = 0f
        set(v) {
            assert(v >= elevations.first().progress)
            assert(v <= elevations.last().progress)
            assert(v >= processedElevations.first().progress)
            assert(v <= processedElevations.last().progress)

            if (field == v) {
                return
            }

            (1 until processedElevations.size).forEach { i ->
                val xl = processedElevations[i - 1].progress
                val xh = processedElevations[i].progress

                if (v in xl..xh) {
                    val yl = processedElevations[i - 1].elevation
                    val yh = processedElevations[i].elevation
                    val g = (yh - yl) / (xh - xl)

                    progressEle = yl + (v - xl) * g
                }
            }

            field = v
            invalidate()
        }

    private val styledAttrs = context.theme.obtainStyledAttributes(
        attrs,
        R.styleable.ElevationView,
        defStyle,
        defStyleRes
    )
    private val reliefColor = styledAttrs.getColor(R.styleable.ElevationView_color, Color.BLACK)
    private val topLineColor =
        styledAttrs.getColor(R.styleable.ElevationView_topLineColor, Color.LTGRAY)
    private val topLineWidth =
        styledAttrs.getDimension(R.styleable.ElevationView_topLineWidth, 2f)
    private val progressColor =
        styledAttrs.getColor(R.styleable.ElevationView_progressColor, Color.DKGRAY)
    private val progressMarkerColor =
        styledAttrs.getColor(R.styleable.ElevationView_progressMarkerColor, Color.RED)
    private val progressMarkerRadius =
        styledAttrs.getDimension(R.styleable.ElevationView_progressMarkerRadius, 15f)
    private val progressLineColor =
        styledAttrs.getColor(R.styleable.ElevationView_progressLineColor, Color.WHITE)
    private val progressLineWidth =
        styledAttrs.getDimension(R.styleable.ElevationView_progressLineWidth, 5f)

    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : this(
        context,
        attrs,
        defStyle,
        0
    )

    init {
        if (isInEditMode) {
            elevations = listOf(
                ElevationProgressNode(0f, 0f),
                ElevationProgressNode(1f, 1f),
                ElevationProgressNode(3f, 2f),
                ElevationProgressNode(-2f, 3f),
                ElevationProgressNode(4f, 4f),
                ElevationProgressNode(4f, 5f),
                ElevationProgressNode(5f, 6f),
                ElevationProgressNode(6.3f, 7f),
            )
            progress = 3.5f
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, width / 3)
    }

    override fun onDrawForeground(canvas: Canvas) {
        val scaleFactorX =
            width / (processedElevations.last().progress - processedElevations.first().progress)
        val scaleFactorY = height / (maxEle - minEle)

        fun Path.moveToLineTo(x: Float, y: Float) {
            if (isEmpty) {
                moveTo(x, y)
            } else {
                lineTo(x, y)
            }
        }

        val elevationProfile = Path().apply {
            processedElevations.forEach { p ->
                val px = (p.progress - processedElevations.first().progress) * scaleFactorX
                val py = height - ((p.elevation - minEle) * scaleFactorY)

                moveToLineTo(px, py)
            }
        }
        canvas.drawPath(elevationProfile, Paint().apply {
            isAntiAlias = true
            color = topLineColor
            strokeWidth = topLineWidth * 2 // Half is hidden behind
            style = Paint.Style.STROKE
        })

        val progressPath = Path().apply {
            moveToLineTo(0f, height.toFloat())

            for (i in processedElevations.indices) {
                val p = processedElevations[i]

                if (p.progress < progress) {
                    val px = (p.progress - processedElevations.first().progress) * scaleFactorX
                    val py = height - ((p.elevation - minEle) * scaleFactorY)

                    moveToLineTo(px, py)
                } else {
                    val px = (progress - processedElevations.first().progress) * scaleFactorX
                    val py = height - (progressEle - minEle) * scaleFactorY

                    moveToLineTo(px, py)
                    moveToLineTo(px, height.toFloat())

                    break
                }
            }

            close()
        }

        canvas.drawPath(progressPath, Paint().apply {
            isAntiAlias = true
            color = progressColor
            style = Paint.Style.FILL
        })

        val leftPath = Path().apply {
            val startX = (progress - processedElevations.first().progress) * scaleFactorX
            val startY = height - (progressEle - minEle) * scaleFactorY

            moveToLineTo(startX, height.toFloat())
            moveToLineTo(startX, startY)

            for (i in processedElevations.indices) {
                val p = processedElevations[i]

                if (p.progress > progress) {
                    val px = (p.progress - processedElevations.first().progress) * scaleFactorX
                    val py = height - ((p.elevation - minEle) * scaleFactorY)

                    moveToLineTo(px, py)
                }
            }

            moveToLineTo(width.toFloat(), height.toFloat())
            close()
        }

        canvas.drawPath(leftPath, Paint().apply {
            isAntiAlias = true
            color = reliefColor
            style = Paint.Style.FILL
        })

        val progressTop = PointF(
            (progress - processedElevations.first().progress) * scaleFactorX,
            height - (progressEle - minEle) * scaleFactorY
        )
        val progressMarker = Path().apply {
            moveToLineTo(progressTop.x, progressTop.y)
            moveToLineTo(progressTop.x, height.toFloat())
        }

        canvas.drawPath(progressMarker, Paint().apply {
            isAntiAlias = true
            color = progressLineColor
            strokeWidth = progressLineWidth
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        })

        canvas.drawCircle(progressTop.x,
            progressTop.y, progressMarkerRadius, Paint().apply {
                color = progressMarkerColor
                style = Paint.Style.FILL
            }
        )
        canvas.drawCircle(progressTop.x,
            progressTop.y, progressMarkerRadius, Paint().apply {
                isAntiAlias = true
                color = progressLineColor
                strokeWidth = progressLineWidth
                style = Paint.Style.STROKE
            }
        )
    }

    companion object {
        val NO_DATA = listOf(
            ElevationProgressNode(0f, 0f),
            ElevationProgressNode(0f, 1f)
        )
    }
}
