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

package androidapp.byco.util

import android.content.Context
import androidapp.byco.lib.R
import kotlin.math.min
import kotlin.math.roundToInt

/** Speeeeeeeed, more speeeaaaad */
class Speed(private val metersPerSecond: Float) {
    fun format(context: Context, isUsingMiles: Boolean, showUnits: Boolean = true): String {
        val localUnitSpeed = min(
            99, if (isUsingMiles) {
                metersPerSecond * 2.23694f
            } else {
                metersPerSecond * 3.6f
            }.roundToInt()
        )

        return if (isUsingMiles) {
            context.getString(
                if (showUnits) {
                    R.string.speed_pattern_imperial
                } else {
                    R.string.speed_pattern_imperial_no_units
                }, localUnitSpeed
            )
        } else {
            context.getString(
                if (showUnits) {
                    R.string.speed_pattern_metric
                } else {
                    R.string.speed_pattern_metric_no_units
                }, localUnitSpeed
            )
        }
    }
}