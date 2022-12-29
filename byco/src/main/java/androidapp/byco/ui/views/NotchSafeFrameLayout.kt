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
import android.graphics.Rect
import android.util.AttributeSet
import android.view.WindowInsets
import android.widget.FrameLayout
import androidapp.byco.lib.R
import androidapp.byco.ui.views.NotchSafeFrameLayout.AddPadding.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.getIntOrThrow
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * [FrameLayout] that automatically extends padding on one side to exclude display cutouts if
 * needed.
 *
 * Requires `app:addPadding="top"` or similar in layout/.xml to specify where the padding should be
 * added.
 */
@SuppressWarnings("ResourceType")
class NotchSafeFrameLayout(
    context: Context,
    attrs: AttributeSet?,
    defStyleAttr: Int,
    defStyleRes: Int
) : FrameLayout(context, attrs, defStyleAttr, defStyleRes) {
    private var cutouts: List<Rect>? = null

    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : this(
        context,
        attrs,
        defStyleAttr,
        0
    )

    private enum class AddPadding {
        LEFT,
        TOP,
        BOTTOM,
        RIGHT,
        START,
        END
    }

    private val addPadding: AddPadding
    private val minLeftPadding: Int
    private val minTopPadding: Int
    private val minRightPadding: Int
    private val minBottomPadding: Int

    init {
        val isLtr = context.resources.configuration.layoutDirection == LAYOUT_DIRECTION_LTR

        val paddingAttrs = context.obtainStyledAttributes(
            attrs,
            intArrayOf(
                android.R.attr.paddingLeft,
                android.R.attr.paddingTop,
                android.R.attr.paddingRight,
                android.R.attr.paddingBottom,
                android.R.attr.paddingStart,
                android.R.attr.paddingEnd
            ),
            defStyleAttr,
            defStyleRes
        )

        minLeftPadding = max(
            paddingAttrs.getDimensionPixelOffset(0, 0),
            if (isLtr) {
                paddingAttrs.getDimensionPixelOffset(4, 0)
            } else {
                paddingAttrs.getDimensionPixelOffset(5, 0)
            }
        )
        minTopPadding = paddingAttrs.getDimensionPixelOffset(1, 0)
        minRightPadding = max(
            paddingAttrs.getDimensionPixelOffset(2, 0),
            if (isLtr) {
                paddingAttrs.getDimensionPixelOffset(5, 0)
            } else {
                paddingAttrs.getDimensionPixelOffset(4, 0)
            }
        )
        minBottomPadding = paddingAttrs.getDimensionPixelOffset(3, 0)

        paddingAttrs.recycle()

        val styledAttrs = context.obtainStyledAttributes(
            attrs,
            R.styleable.NotchSafeFrameLayout,
            defStyleAttr,
            defStyleRes
        )
        val addPaddingAttr = AddPadding.values().find {
            it.ordinal == styledAttrs.getIntOrThrow(
                R.styleable.NotchSafeFrameLayout_addPadding,
            )
        } ?: throw IllegalArgumentException("addPadding needs to be specified")

        addPadding = when (addPaddingAttr) {
            START -> if (isLtr) {
                LEFT
            } else {
                RIGHT
            }
            END -> if (isLtr) {
                RIGHT
            } else {
                LEFT
            }
            else -> addPaddingAttr
        }

        styledAttrs.recycle()
    }

    override fun onApplyWindowInsets(insets: WindowInsets?): WindowInsets {
        this.cutouts =
            insets?.let { WindowInsetsCompat.toWindowInsetsCompat(it).displayCutout?.boundingRects }

        return super.onApplyWindowInsets(insets)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        var requiredLeftPadding = 0
        var requiredTopPadding = 0
        var requiredRightPadding = 0
        var requiredBottomPadding = 0

        cutouts?.forEach { cutout ->
            if (cutout.intersect(
                    left + requiredLeftPadding,
                    top + requiredTopPadding,
                    right - requiredRightPadding,
                    bottom - requiredBottomPadding
                )
            ) {
                when (addPadding) {
                    LEFT -> requiredLeftPadding =
                        max(requiredLeftPadding, cutout.right - (left + requiredLeftPadding))

                    TOP -> requiredTopPadding =
                        max(requiredTopPadding, cutout.bottom - (top + requiredTopPadding))

                    RIGHT -> requiredRightPadding =
                        max(requiredRightPadding, (right - requiredRightPadding) - cutout.left)

                    BOTTOM -> requiredBottomPadding = max(
                        requiredBottomPadding,
                        (bottom - requiredBottomPadding) - cutout.top
                    )
                    START, END -> throw IllegalStateException()
                }
            }
        }

        if (paddingLeft != max(minLeftPadding, requiredLeftPadding)
            || paddingTop != max(minTopPadding, requiredTopPadding)
            || paddingRight != max(minRightPadding, requiredRightPadding)
            || paddingBottom != max(minBottomPadding, requiredBottomPadding)
        ) {
            if (!isInEditMode) {
                // When called sync during the doLayout call the view is not re-layouted
                (context as AppCompatActivity).lifecycle.coroutineScope.launch(Main) {
                    setPadding(
                        max(minLeftPadding, requiredLeftPadding),
                        max(minTopPadding, requiredTopPadding),
                        max(minRightPadding, requiredRightPadding),
                        max(minBottomPadding, requiredBottomPadding)
                    )
                }
            }
        } else {
            super.onLayout(changed, left, top, right, bottom)
        }
    }
}