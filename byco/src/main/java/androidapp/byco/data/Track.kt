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

package androidapp.byco.data

import lib.gpx.MapArea
import lib.gpx.RecordedLocation
import lib.gpx.Track

typealias TrackAsNodes = List<Pair<Float, Array<Node>>>

/**
 * Only returns [Node]s of track that are inside the [area]. If [area] is `null` the whole track is
 * returned.
 *
 * If [computeProgress] is set the float provides with each array gives the progress in the track at
 * the start of the array
 */
fun Track.restrictTo(
    area: MapArea?,
    computeProgress: Boolean = false
): TrackAsNodes {
    var progress = 0f

    return segments.flatMap { segment ->
        val subSegments = mutableListOf<Pair<Float, List<RecordedLocation>>>()
        var currentSubSegment: MutableList<RecordedLocation>? = null

        segment.forEachIndexed { i, current ->
            if (i > 0 && computeProgress) {
                progress += segment[i - 1].distanceTo(current)
            }

            // Add if any track segment including this node (incoming or outgoing) is visible.
            val isVisible = area?.let { area ->
                val isVisible = current.isInside(area)

                val isIncomingVisible = if (i > 0) {
                    area.doesSegmentIntersect(current, segment[i - 1])
                } else {
                    false
                }
                val isOutGoingVisible = if (i < segment.size - 1) {
                    area.doesSegmentIntersect(current, segment[i + 1])
                } else {
                    false
                }

                isVisible || isIncomingVisible || isOutGoingVisible
            } ?: true

            if (isVisible) {
                if (currentSubSegment == null) {
                    currentSubSegment = mutableListOf()
                    subSegments.add(progress to currentSubSegment!!)
                }
                currentSubSegment!!.add(current)
            } else {
                currentSubSegment = null
            }
        }

        subSegments.map { (progress, locations) ->
            progress to locations.map { location ->
                // Convert to Node to be able to cache absolute coordinates
                location.toNode()
                // Convert to typed array to be able to iterate through it without needing
                // an iterator. This is actually much faster.
            }.toTypedArray()
        }
    }
}

internal val Track.minLat: Double
    get() = segments.minOf { segment -> segment.minOfOrNull { it.latitude } ?: Double.NaN }

internal val Track.minLon: Double
    get() = segments.minOf { segment -> segment.minOfOrNull { it.longitude } ?: Double.NaN }

internal val Track.maxLat: Double
    get() = segments.maxOf { segment -> segment.maxOfOrNull { it.latitude } ?: Double.NaN }

internal val Track.maxLon: Double
    get() = segments.maxOf { segment -> segment.maxOfOrNull { it.longitude } ?: Double.NaN }
