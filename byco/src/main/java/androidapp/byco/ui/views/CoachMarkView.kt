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
import android.content.Context.MODE_PRIVATE
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.view.View.OnClickListener
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidapp.byco.lib.R
import androidapp.byco.ui.views.CoachMarkView.ArrowDirection.*
import androidx.core.content.res.getIntOrThrow
import androidx.core.content.edit

/** A tooltip that can be permanently dismissed */
class CoachMarkView(
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

    private enum class ArrowDirection {
        TOP_START,
        TOP_END,
        START,
        END,
        BOTTOM_START,
        BOTTOM_END,
    }

    private val persistentId: String?

    private val view: View = inflate(getContext(), R.layout.coachmark, null)
    private val prefs = context.getSharedPreferences(CoachMarkView::class.java.name, MODE_PRIVATE)

    init {
        addView(view)

        clipToPadding = false
        clipChildren = false

        val styledAttrs = context.obtainStyledAttributes(
            attrs,
            R.styleable.CoachMarkView,
            defStyleAttr,
            defStyleRes
        )
        val addPaddingAttr = entries.find {
            it.ordinal == styledAttrs.getIntOrThrow(
                R.styleable.CoachMarkView_arrowDirection,
            )
        } ?: throw IllegalArgumentException("arrowDirection needs to be specified")

        val start = findViewById<ImageView>(R.id.start)
        val topStart = findViewById<ImageView>(R.id.topStart)
        val topEnd = findViewById<ImageView>(R.id.topEnd)
        val end = findViewById<ImageView>(R.id.end)
        val bottomStart = findViewById<ImageView>(R.id.bottomStart)
        val bottomEnd = findViewById<ImageView>(R.id.bottomEnd)

        start.visibility = GONE
        topStart.visibility = GONE
        topEnd.visibility = GONE
        end.visibility = GONE
        bottomStart.visibility = GONE
        bottomEnd.visibility = GONE

        when (addPaddingAttr) {
            START -> start.visibility = VISIBLE
            TOP_START -> topStart.visibility = VISIBLE
            TOP_END -> topEnd.visibility = VISIBLE
            END -> end.visibility = VISIBLE
            BOTTOM_START -> bottomStart.visibility = VISIBLE
            BOTTOM_END -> bottomEnd.visibility = VISIBLE
        }

        val textView = findViewById<TextView>(R.id.text)
        val dismissStart = findViewById<ImageButton>(R.id.dismissStart)
        val dismissEnd = findViewById<ImageButton>(R.id.dismissEnd)

        dismissStart.visibility = GONE
        dismissEnd.visibility = GONE

        when (addPaddingAttr) {
            START, TOP_START, BOTTOM_START -> dismissEnd.visibility = VISIBLE
            TOP_END, END, BOTTOM_END -> dismissStart.visibility = VISIBLE
        }

        val text = styledAttrs.getString(R.styleable.CoachMarkView_text)
        val textColor = styledAttrs.getColor(R.styleable.CoachMarkView_textColor, Color.BLACK)

        text?.let { textView.text = it }
        textView.setTextColor(textColor)
        dismissStart.drawable.setTint(textColor)
        dismissEnd.drawable.setTint(textColor)

        val bubble = findViewById<View>(R.id.bubble)

        val backgroundColor =
            styledAttrs.getColor(R.styleable.CoachMarkView_backgroundColor, Color.WHITE)

        bubble.background.setTint(backgroundColor)
        start.drawable.setTint(backgroundColor)
        topStart.drawable.setTint(backgroundColor)
        topEnd.drawable.setTint(backgroundColor)
        end.drawable.setTint(backgroundColor)

        bubble.elevation = elevation
        start.elevation = elevation
        topStart.elevation = elevation
        topEnd.elevation = elevation
        end.elevation = elevation
        bottomStart.elevation = elevation
        bottomEnd.elevation = elevation

        elevation = 0f

        persistentId = styledAttrs.getString(R.styleable.CoachMarkView_persistentId)

        // Move "labelFor" to textView
        if (labelFor != 0) {
            textView.labelFor = labelFor
            labelFor = 0
        }

        styledAttrs.recycle()

        val dismiss = OnClickListener {
            if (persistentId != null) {
                prefs.edit { putBoolean(persistentId, true) }
            }
            view.visibility = GONE
        }

        dismissStart.setOnClickListener(dismiss)
        dismissEnd.setOnClickListener(dismiss)

        if (persistentId != null) {
            if (prefs.getBoolean(persistentId, false)) {
                view.visibility = GONE
            }
        }
    }

    /**
     * Never show this coach mark again.
     *
     * For testing you can remove the CoachMarkView shared preference file.
     */
    fun neverShowAgain() {
        if (persistentId == null) {
            throw IllegalArgumentException("persistentId is not set, cannot permanently hide")
        }

        prefs.edit { putBoolean(persistentId, true) }
        view.visibility = GONE
    }
}