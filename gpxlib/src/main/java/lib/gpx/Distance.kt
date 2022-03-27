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

package lib.gpx

import android.content.Context
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlin.math.roundToInt

/** Physical distance */
@Parcelize
data class Distance(private val meters: Float) : Parcelable {
    fun format(
        context: Context, isUsingMiles: Boolean, showUnits: Boolean = true,
        maybeUseSmallUnits: Boolean = false
    ): String {
        assert(!maybeUseSmallUnits || showUnits)

        val localUnitDistance = if (isUsingMiles) {
            meters / 1609.34f
        } else {
            meters / 1000
        }

        val localSmallUnitDistance = if (isUsingMiles) {
            (meters * 3.2808f / 50).roundToInt() * 50
        } else {
            (meters / 10).roundToInt() * 10
        }

        return if (isUsingMiles) {
            if (localUnitDistance.roundToInt() < 10) {
                if (maybeUseSmallUnits && localSmallUnitDistance < 1000) {
                    context.getString(
                        R.string.distance_pattern_imperial_small_units,
                        localSmallUnitDistance
                    )
                } else {
                    context.getString(
                        if (showUnits) {
                            R.string.distance_pattern_imperial_low
                        } else {
                            R.string.distance_pattern_imperial_no_units_low
                        }, localUnitDistance
                    )
                }
            } else {
                context.getString(
                    if (showUnits) {
                        R.string.distance_pattern_imperial
                    } else {
                        R.string.distance_pattern_imperial_no_units
                    }, localUnitDistance.roundToInt()
                )
            }
        } else {
            if (localUnitDistance.roundToInt() < 10) {
                if (maybeUseSmallUnits && localSmallUnitDistance < 1000) {
                    context.getString(
                        R.string.distance_pattern_metric_small_units,
                        localSmallUnitDistance
                    )
                } else {
                    context.getString(
                        if (showUnits) {
                            R.string.distance_pattern_metric_low
                        } else {
                            R.string.distance_pattern_metric_no_units_low
                        }, localUnitDistance
                    )
                }
            } else {
                context.getString(
                    if (showUnits) {
                        R.string.distance_pattern_metric
                    } else {
                        R.string.distance_pattern_metric_no_units
                    }, localUnitDistance.roundToInt()
                )
            }
        }
    }
}
