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

import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min

/** A recorded gps track */
class Track(
    val segments: List<List<RecordedLocation>>
) {
    /** Total distance ridden */
    val distance: Distance
        get() {
            return Distance(segments.fold(0f) { prevSegmentsDistance, segment ->
                var lastTrackPoint: RecordedLocation? = null

                prevSegmentsDistance + segment.fold(0f) { thisSegmentDistance, nextTrackPoint ->
                    thisSegmentDistance + (lastTrackPoint?.distanceTo(nextTrackPoint) ?: 0f).also {
                        lastTrackPoint = nextTrackPoint
                    }
                }
            })
        }

    /** Total time covered by track */
    val duration: Duration?
        get() {
            var minTime = Long.MAX_VALUE
            var maxTime = Long.MIN_VALUE

            segments.forEach {
                it.forEach { trackPoint ->
                    if (trackPoint.time != null) {
                        minTime = min(minTime, trackPoint.time)
                        maxTime = max(maxTime, trackPoint.time)
                    }
                }
            }

            return if (minTime == Long.MAX_VALUE) {
                null
            } else {
                Duration(maxTime - minTime)
            }
        }

    companion object {
        suspend fun parseFrom(file: File): Track {
            return withContext(IO) {
                file.inputStream().buffered().use { ins ->
                    ZipInputStream(ins).use { zipIs ->
                        zipIs.findFirstNotNull { entry ->
                            when (entry.name) {
                                TRACK_ZIP_ENTRY ->
                                    GpxParser(zipIs).also { it.parse() }.track
                                else ->
                                    null
                            }
                        }!!
                    }
                }
            }
        }
    }
}