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
import android.util.AttributeSet
import android.view.View.MeasureSpec.*
import android.widget.FrameLayout

/** [FrameLayout] that hides if it would be smaller than desired */
class HideIfNotFitFrameLayout(
    context: Context,
    attrs: AttributeSet?,
    defStyleAttr: Int,
    defStyleRes: Int
) : FrameLayout(context, attrs, defStyleAttr, defStyleRes) {
    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : this(
        context,
        attrs,
        defStyleAttr,
        0
    )

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(makeMeasureSpec(0, UNSPECIFIED), makeMeasureSpec(0, UNSPECIFIED))

        val desiredWidth = measuredWidth
        val desiredHeight = measuredHeight

        val widthMode = getMode(widthMeasureSpec)
        val widthSize = if (widthMode == AT_MOST || widthMode == EXACTLY) {
            getSize(widthMeasureSpec)
        } else {
            Int.MAX_VALUE
        }
        val heightMode = getMode(heightMeasureSpec)
        val heightSize =
            if (heightMode == AT_MOST || heightMode == EXACTLY) {
                getSize(heightMeasureSpec)
            } else {
                Int.MAX_VALUE
            }

        if (widthSize < desiredWidth || heightSize < desiredHeight) {
            setMeasuredDimension(0, 0)
        }
    }
}