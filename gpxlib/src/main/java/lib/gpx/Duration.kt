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
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import java.util.concurrent.TimeUnit.MILLISECONDS

/** Duration of a ride */
@Parcelize
data class Duration(val millis: Long) : Parcelable {
    fun format(context: Context, showUnits: Boolean = true): String {
        return if (MILLISECONDS.toHours(millis) == 0L) {
            context.getString(
                if (showUnits) {
                    R.string.duration_pattern_low
                } else {
                    R.string.duration_pattern_no_units_low
                }, MILLISECONDS.toMinutes(millis)
            )
        } else {
            context.getString(
                if (showUnits) {
                    R.string.duration_pattern
                } else {
                    R.string.duration_pattern_no_units
                },
                MILLISECONDS.toHours(millis),
                MILLISECONDS.toMinutes(millis) % 60
            )
        }
    }

    @IgnoredOnParcel
    val isNone: Boolean = millis == 0L
}