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

import android.location.Location
import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import java.math.BigDecimal

/** A square area of a map */
@Parcelize
data class MapArea(
    val minLat: BigDecimal,
    val minLon: BigDecimal,
    val maxLat: BigDecimal,
    val maxLon: BigDecimal
) : Parcelable {
    @IgnoredOnParcel
    val minLatD = minLat.toDouble()

    @IgnoredOnParcel
    val minLonD = minLon.toDouble()

    @IgnoredOnParcel
    val maxLatD = maxLat.toDouble()

    @IgnoredOnParcel
    val maxLonD = maxLon.toDouble()

    val min: BasicLocation
        get() {
            return BasicLocation(minLatD, minLonD)
        }
    val max: BasicLocation
        get() {
            return BasicLocation(maxLatD, maxLonD)
        }

    constructor(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double
    ) : this(
        minLat.toBigDecimal(),
        minLon.toBigDecimal(),
        maxLat.toBigDecimal(),
        maxLon.toBigDecimal()
    )

    override fun toString(): String {
        return "[$minLat/$minLon - $maxLat/$maxLon]"
    }

    fun contains(other: MapArea): Boolean {
        return this.minLat <= other.minLat
                && this.minLon <= other.minLon
                && this.maxLat >= other.maxLat
                && this.maxLon >= other.maxLon
    }

    fun contains(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): Boolean {
        return minLatD <= minLat
                && minLonD <= minLon
                && maxLatD >= maxLat
                && maxLonD >= maxLon
    }

    fun contains(loc: Location): Boolean {
        return minLatD <= loc.latitude
                && minLonD <= loc.longitude
                && maxLatD >= loc.latitude
                && maxLonD >= loc.longitude
    }

    /** Check is a line between [a] and [b] might intersect with this area. */
    fun doesSegmentIntersect(a: BasicLocation, b: BasicLocation): Boolean {
        val isSouth = a.latitude < minLatD && b.latitude < minLatD
        val isNorth = a.latitude > maxLatD && b.latitude > maxLatD
        val isWest = a.longitude < minLonD && b.latitude < minLonD
        val isEast = a.longitude > maxLonD && b.latitude > maxLonD

        return !(isSouth || isNorth || isWest || isEast)
    }
}