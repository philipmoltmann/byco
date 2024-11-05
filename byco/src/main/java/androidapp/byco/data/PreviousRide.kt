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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import lib.gpx.GpxParser
import lib.gpx.MapArea
import lib.gpx.RecordedLocation
import lib.gpx.TRACK_ZIP_ENTRY
import lib.gpx.Track
import lib.gpx.findFirstNotNull
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipInputStream

/**
 * A previously recorded ride.
 *
 * `Equals` by [file].
 */
class PreviousRide(
    val time: Long?,
    val title: String?,
    track: Track,
    internal val file: File,
    internal val lastModified: Long,
) {
    /** Total distance ridden */
    val distance = track.distance

    /** Total time ridden */
    val duration = track.duration

    /** Rectangular area the ride is in */
    val area = MapArea(track.minLat, track.minLon, track.maxLat, track.maxLon)

    val isDirectionsHome = file.name.startsWith(DIRECTIONS_HOME_FILE_PREFIX)

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }

        if (javaClass != other?.javaClass) {
            return false
        }

        return file.name == (other as PreviousRide).file.name
                && lastModified == other.lastModified
    }

    override fun hashCode(): Int {
        return file.name.hashCode()
    }

    override fun toString(): String {
        return file.name
    }

    companion object {
        const val RECORDING_FILE_NAME_PREFIX = "recording-"
        const val IMPORTED_FILE_FILE_PREFIX = "import-"
        const val DIRECTIONS_HOME_FILE_PREFIX = "directionsHome-"

        suspend fun parseFrom(file: File): PreviousRide {
            ZipInputStream(file.inputStream().buffered()).use { zipIs ->
                val gpx = zipIs.findFirstNotNull { entry ->
                    when (entry.name) {
                        TRACK_ZIP_ENTRY ->
                            GpxParser(zipIs).also { it.parse() }

                        else ->
                            null
                    }
                }

                if (gpx?.track != null) {
                    return PreviousRide(
                        gpx.time,
                        gpx.name,
                        gpx.track!!,
                        file,
                        file.lastModified()
                    )
                } else {
                    throw IllegalArgumentException("No gpx or track found")
                }
            }
        }
    }
}

/** Write the data for the [track] to the output stream.
 *
 * @param removeStart Meters to remove from start of ride
 * @param removeEnd Meters to remove from end of ride
 */
suspend fun OutputStream.writePreviousRide(
    track: Track,
    time: Long?,
    title: String?,
    removeStart: Float = 0f,
    removeEnd: Float = 0f,
) {
    // Do not cancel while overwriting file to avoid corruption.
    withContext(Dispatchers.IO + NonCancellable) {
        GpxSerializer(this@writePreviousRide, title, time).use { gpx ->
            var isFirstSegment = true

            // find first and last loc to write
            var firstLocToWrite: RecordedLocation? = null
            var distance = 0f
            for (segment in track.segments) {
                var lastLoc: RecordedLocation? = null
                for (loc in segment) {
                    if (distance >= removeStart) {
                        firstLocToWrite = loc
                        break
                    }

                    if (lastLoc != null) {
                        distance += lastLoc.distanceTo(loc)
                    }
                    lastLoc = loc
                }

                if (firstLocToWrite != null) {
                    break
                }
            }

            var lastLocToWrite: RecordedLocation? = null
            distance = 0f
            for (segment in track.segments.asReversed()) {
                var lastLoc: RecordedLocation? = null
                for (loc in segment.asReversed()) {
                    if (distance >= removeEnd) {
                        lastLocToWrite = loc
                        break
                    }

                    if (lastLoc != null) {
                        distance += lastLoc.distanceTo(loc)
                    }
                    lastLoc = loc
                }

                if (lastLocToWrite != null) {
                    break
                }
            }

            var hasFoundFirstLoc = false
            var hasFoundLastLoc = false
            track.segments.forEach { segment ->
                if (isFirstSegment) {
                    isFirstSegment = false
                } else {
                    gpx.newSegment()
                }

                segment.forEach { loc ->
                    // instance comparison as a track might go through same physical location
                    // twice
                    if (loc === firstLocToWrite) {
                        hasFoundFirstLoc = true
                    }

                    if (hasFoundFirstLoc && !hasFoundLastLoc) {
                        gpx.addPoint(loc)
                    }

                    // instance comparison as a track might go through same physical location
                    // twice
                    if (loc === lastLocToWrite) {
                        hasFoundLastLoc = true
                    }
                }
            }
        }
    }
}