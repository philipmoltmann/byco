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

import androidapp.byco.util.restrictTo
import androidx.annotation.VisibleForTesting
import lib.gpx.BasicLocation
import lib.gpx.MapArea
import kotlin.math.*

/**
 * Elevation data for an area
 */
class ElevationData(
    val area: MapArea,
    val latOffset: Double,
    val lonOffset: Double,
    val cellSize: Double,
    // Elevations according to https://en.wikipedia.org/wiki/Esri_grid, [0,0] is the most
    // north-western point (top-left on map). lat == -y, lon == x
    val elevation: Array<ShortArray>
) {
    init {
        assert(
            abs(
                (lonOffset + cellSize * elevation[0].size) -
                        (area.maxLon.toDouble() - area.minLon.toDouble())
            ) < cellSize
        ) { "$area c=$cellSize o=$latOffset/$lonOffset e=${elevation[0].size}x${elevation.size}" }
        assert(
            abs(
                (latOffset + cellSize * elevation.size) -
                        (area.maxLatD - area.minLatD)
            ) < cellSize
        )
    }

    /** Get latitude of a y-coordinate in the [elevation] array. */
    private fun getLat(y: Int): Double {
        // [0,0] is the most north-western point (top-left on map). lat == -y, lon == x
        // @see getY(y)
        return area.minLatD + latOffset + (elevation.size - y - 1) * cellSize
    }

    /** Get longitude of a x-coordinate in the [elevation] array. */
    private fun getLon(x: Int): Double {
        return area.minLonD + lonOffset + x * cellSize
    }

    /** The y value in the [elevation] array for a latitude */
    private fun getY(lat: Double): Double {
        // [0,0] is the most north-western point (top-left on map). lat == -y, lon == x
        // @see getLat(y)
        return (elevation.size - 1) - ((lat - latOffset) - area.minLatD) / cellSize
    }

    /** The x value in the [elevation] array for a longitude */
    private fun getX(lon: Double): Double {
        return ((lon - lonOffset) - area.minLonD) / cellSize
    }

    /** Get elevation of a [lat]/[lon]. */
    fun getElevation(lat: Double, lon: Double): Float {
        assert(lat >= area.minLatD)
        assert(lat <= area.maxLatD)
        assert(lon >= area.minLonD)
        assert(lon <= area.maxLonD)

        // For edges we don't have enough data, so just pretend they match data point exactly
        val x = getX(lon).restrictTo(0, elevation[0].size - 1)
        // For edges we don't have enough data, so just pretend they match data point exactly
        val y = getY(lat).restrictTo(0, elevation.size - 1)

        val xl = floor(x).toInt()
        val xh = ceil(x).toInt()
        val yl = floor(y).toInt()
        val yh = ceil(y).toInt()

        return ((elevation[yl][xl] * (1 - (x - xl)) +
                elevation[yl][xh] * (x - xl))
                * (1 - (y - yl)) +
                (elevation[yh][xl] * (1 - (x - xl)) +
                        elevation[yh][xh] * (x - xl))
                * (y - yl)).toFloat()
    }

    /** Get elevation of a basic location. */
    fun getElevation(loc: BasicLocation): Float {
        return getElevation(loc.latitude, loc.longitude)
    }

    /**
     * Merges two adjacent [ElevationData]s into one rectangle. The [cellSize] and
     * [latOffset]/[lonOffset] is taken from the more north or west one.
     *
     * @throws IllegalArgumentException if the result would not be rectangular
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    fun merge(other: ElevationData): ElevationData {
        // TODO:
        // - Currently the seems between tiles with different offsets or cellSizing are not
        //   properly interpolated.
        // - Merging datas with different sizes (even off by one) will produce weird results as
        //   we always add all data from "b" even though it might be out of the area

        return if (area.minLat == other.area.minLat) {
            if (area.maxLat != other.area.maxLat) {
                throw IllegalArgumentException()
            }

            val (a, b) = if (area.minLon < other.area.minLon) {
                this to other
            } else {
                other to this
            }

            // a a b b
            // a a b b

            if (a.area.maxLon != b.area.minLon) {
                throw IllegalArgumentException()
            }

            if (abs(a.cellSize - b.cellSize) < 0.0000001
                && abs(a.latOffset - b.latOffset) < 0.0000001
                && abs(a.lonOffset - b.lonOffset) < 0.0000001
            ) {
                ElevationData(
                    MapArea(
                        a.area.minLat,
                        a.area.minLon,
                        b.area.maxLat,
                        b.area.maxLon
                    ),
                    a.latOffset,
                    a.lonOffset,
                    a.cellSize,
                    Array(a.elevation.size) { y ->
                        val aRow = a.elevation[y]
                        val bRow = b.elevation[y]

                        val newRow = ShortArray(aRow.size + bRow.size)
                        System.arraycopy(aRow, 0, newRow, 0, aRow.size)
                        System.arraycopy(bRow, 0, newRow, aRow.size, bRow.size)

                        newRow
                    }
                )
            } else {
                ElevationData(
                    MapArea(
                        a.area.minLat,
                        a.area.minLon,
                        b.area.maxLat,
                        b.area.maxLon
                    ),
                    a.latOffset,
                    a.lonOffset,
                    a.cellSize,
                    Array(a.elevation.size) { y ->
                        val aRow = a.elevation[y]
                        val bRow = b.elevation[y]

                        val newRow = ShortArray(aRow.size + bRow.size)
                        System.arraycopy(aRow, 0, newRow, 0, aRow.size)

                        val lat = a.getLat(y).restrictTo(b.area.minLatD, b.area.maxLatD)
                        var lon = a.getLon(aRow.size).restrictTo(b.area.minLonD, b.area.maxLonD)

                        // Recompute all data from "b" into reference frame of "a" as offsets and/or
                        // cellSpacing is different
                        for (x in aRow.size until newRow.size) {
                            newRow[x] =
                                b.getElevation(lat, min(b.area.maxLonD, max(b.area.minLonD, lon)))
                                    .toInt().toShort()
                                    .also { lon += a.cellSize }
                        }

                        newRow
                    }
                )
            }
        } else {
            if (area.minLon != other.area.minLon || area.maxLon != other.area.maxLon) {
                throw java.lang.IllegalArgumentException()
            }

            val (a, b) = if (area.minLat < other.area.minLat) {
                other to this
            } else {
                this to other
            }

            // a a
            // a a
            // b b
            // b b

            if (b.area.maxLat != a.area.minLat) {
                throw java.lang.IllegalArgumentException()
            }

            if (abs(a.cellSize - b.cellSize) < 0.0000001
                && abs(a.latOffset - b.latOffset) < 0.0000001
                && abs(a.lonOffset - b.lonOffset) < 0.0000001
            ) {
                ElevationData(
                    MapArea(
                        b.area.minLat,
                        b.area.minLon,
                        a.area.maxLat,
                        a.area.maxLon
                    ),
                    a.latOffset,
                    a.lonOffset,
                    a.cellSize,
                    Array(a.elevation.size + b.elevation.size) { y ->
                        if (y < a.elevation.size) {
                            a.elevation[y]
                        } else {
                            b.elevation[y - a.elevation.size]
                        }
                    }
                )
            } else {
                ElevationData(
                    MapArea(
                        b.area.minLat,
                        b.area.minLon,
                        a.area.maxLat,
                        a.area.maxLon
                    ),
                    a.latOffset,
                    a.lonOffset,
                    a.cellSize,
                    Array(a.elevation.size + b.elevation.size) { y ->
                        if (y < a.elevation.size) {
                            a.elevation[y]
                        } else {
                            val lat = a.getLat(y).restrictTo(b.area.minLatD, b.area.maxLatD)
                            var lon = a.getLon(0).restrictTo(b.area.minLonD, b.area.maxLonD)

                            ShortArray(a.elevation[0].size) {
                                b.getElevation(lat, min(b.area.maxLonD, max(b.area.minLonD, lon)))
                                    .toInt().toShort()
                                    .also { lon += a.cellSize }
                            }
                        }
                    }
                )
            }
        }
    }

    override fun toString(): String {
        return elevation.fold("") { acc, line ->
            acc + "\n" + line.fold("") { a, ele ->
                "%s% 5d ".format(a, ele)
            }.trimEnd()
        }.substring(1)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (javaClass != other?.javaClass) {
            return false
        }

        other as ElevationData

        return area == other.area
                && latOffset == other.latOffset
                && lonOffset == other.lonOffset
                && cellSize == other.cellSize &&
                elevation.contentDeepEquals(other.elevation)
    }

    override fun hashCode(): Int {
        return area.hashCode() + (31 * cellSize).toInt()
    }

    companion object {
        const val NO_DATA = Short.MIN_VALUE
    }
}