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
import androidapp.byco.lib.R
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.floor

class AutoColumnSizingRecyclerView(
    context: Context,
    attrs: AttributeSet?,
    defStyleAttr: Int,
    defStyleRes: Int
) : RecyclerView(context, attrs, defStyleAttr) {
    private val maxColumnWidth: Int

    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : this(
        context,
        attrs,
        defStyleAttr,
        0
    )

    init {
        val a = context.obtainStyledAttributes(
            attrs,
            R.styleable.AutoColumnSizingRecyclerView,
            defStyleAttr,
            defStyleRes
        )
        maxColumnWidth =
            a.getDimensionPixelSize(R.styleable.AutoColumnSizingRecyclerView_maxColumnWidth, 0)

        a.recycle()

        layoutManager = GridLayoutManager(context, attrs, defStyleAttr, defStyleRes)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, heightSpec)

        if (maxColumnWidth > 0) {
            (layoutManager as GridLayoutManager).spanCount =
                floor(measuredWidth.toFloat() / maxColumnWidth).toInt()
        }
    }
}