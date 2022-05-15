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
import kotlinx.parcelize.Parcelize
import java.lang.Math.toRadians
import kotlin.math.*

/**
 * A basic location, i.e. lat/lon pair.
 *
 * Uses significantly less memory than Android's [Location] objects.
 *
 * @see RecordedLocation
 */
@Parcelize
open class BasicLocation(
    val latitude: Double,
    val longitude: Double
) : Parcelable {
    private fun latitudeRadians() = toRadians(latitude)
    private fun longitudeRadians() = toRadians(longitude)

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }

        if (other == null || other !is BasicLocation) {
            return false
        }

        return latitude == other.latitude && longitude == other.longitude
    }

    fun isInside(mapArea: MapArea): Boolean {
        return latitude in mapArea.minLatD..mapArea.maxLatD
                && longitude in mapArea.minLonD..mapArea.maxLonD
    }

    override fun hashCode(): Int {
        return (latitude + longitude).hashCode()
    }

    open fun toLocation(): Location {
        return Location("from basic").apply {
            latitude = this@BasicLocation.latitude
            longitude = this@BasicLocation.longitude
        }
    }

    /**
     * Distance to other location in meters
     */
    fun distanceTo(other: BasicLocation): Float {
        val result = floatArrayOf(Float.NaN)
        Location.distanceBetween(latitude, longitude, other.latitude, other.longitude, result)
        return result[0]
    }

    fun distanceTo(other: Location): Float {
        val result = floatArrayOf(Float.NaN)
        Location.distanceBetween(latitude, longitude, other.latitude, other.longitude, result)
        return result[0]
    }

    override fun toString(): String {
        return "$latitude,$longitude"
    }

    /**
     * Bearing between this and an-[other] node on a map (in radians (-PI - +PI).
     */
    private fun bearingToRad(other: BasicLocation): Double {
        val lat = latitudeRadians()
        val lon = longitudeRadians()

        val otherLat = other.latitudeRadians()
        val otherLon = other.longitudeRadians()

        return atan2(
            sin(otherLon - lon) * cos(otherLat),
            cos(lat) * sin(otherLat) - sin(lat) * cos(otherLat) * cos(otherLon - lon)
        )
    }

    /**
     * Bearing between this and an-[other] node on a map (in the range from `-180` to `+180`).
     */
    fun bearingTo(other: BasicLocation): Float {
        return Math.toDegrees(bearingToRad(other)).toFloat()
    }

    /**
     * Convert a location (which is in spherical coordinates) into a [Vector3D] assuming a unit
     * sphere.
     */
    private fun toUnitSphereVector(): Vector3D {
        val latRad = (90 - latitude) * PI / 180
        val lonRad = longitude * PI / 180
        return Vector3D(cos(lonRad) * sin(latRad), sin(lonRad) * sin(latRad), cos(latRad))
    }

    /**
     * Closest location on a great circle (as two points [a] and [b] on it) to this location.
     *
     * If there are multiple correct solutions this methods prefers `this` over [a] over other
     * locations.
     */
    private fun closesLocationOnGreatCircle(
        a: BasicLocation,
        b: BasicLocation
    ): BasicLocation? {
        val aVec = a.toUnitSphereVector()
        val bVec = b.toUnitSphereVector()

        if (aVec == bVec) {
            // a and b are the same location, hence they don't describe a great circle
            return null
        } else if (aVec == -bVec) {
            // a and b are on opposite sides of the earth, hence all paths between them are the same
            // length, including the one through this location
            return this
        }

        val destVec = toUnitSphereVector()
        val gcNormalVec = aVec x bVec

        if (destVec == gcNormalVec) {
            // the great circle is exactly equatorial to to the point, hence any location on it is
            // similarly close
            return a
        }

        val gcDestNormalVec = gcNormalVec x destVec

        if (gcDestNormalVec == gcNormalVec) {
            // the location is on the great circle
            return this
        }

        val intVec = (gcDestNormalVec x gcNormalVec).asUnitVec()

        val int1 = intVec.toBasicLocation()
        val int2 = (-intVec).toBasicLocation()

        val int1Dist = distanceTo(int1)
        val int2Dist = distanceTo(int2)
        return if (int1Dist < int2Dist) {
            int1
        } else {
            int2
        }
    }

    /** Closest location to this on a segment */
    fun closestNodeOn(segmentStart: BasicLocation, segmentEnd: BasicLocation): BasicLocation {
        val gcPoint = closesLocationOnGreatCircle(segmentStart, segmentEnd) ?: return segmentStart

        val segmentStartDist = distanceTo(segmentStart)
        val segmentEndDist = distanceTo(segmentEnd)
        val segmentLen = segmentStart.distanceTo(segmentEnd)

        return if (gcPoint.distanceTo(segmentStart) < segmentLen
            && gcPoint.distanceTo(segmentEnd) < segmentLen
        ) {
            gcPoint
        } else if (segmentStartDist < segmentEndDist) {
            segmentStart
        } else {
            segmentEnd
        }
    }

    /** Closest node to node on a segment (estimation usually good enough for small distances away
     * from poles) */
    fun closestNodeOnEst(segmentStart: BasicLocation, segmentEnd: BasicLocation): BasicLocation {
        // TODO: Do we need to use spherical math to avoid errors?

        val latDiff = segmentEnd.latitude - segmentEnd.latitude
        val lonDiff = segmentEnd.longitude - segmentStart.longitude

        if (latDiff == 0.0 && lonDiff == 0.0) {
            return segmentStart
        }

        val segmentProgress = ((latitude - segmentStart.latitude) * latDiff +
                (longitude - segmentStart.longitude) * lonDiff) /
                (latDiff * latDiff + lonDiff * lonDiff)

        return when {
            segmentProgress < 0 -> segmentStart
            segmentProgress > 1 -> segmentEnd
            else -> {
                BasicLocation(
                    segmentStart.longitude + segmentProgress * lonDiff,
                    segmentStart.latitude + segmentProgress * latDiff
                )
            }
        }
    }

    /** 3d vector, used when making spherical calculations */
    private data class Vector3D(
        val x: Double,
        val y: Double,
        val z: Double
    ) {
        /** Cross product of this vector with the [other] vector */
        infix fun x(other: Vector3D) =
            Vector3D(
                y * other.z - z * other.y,
                z * other.x - x * other.z,
                x * other.y - y * other.x
            )

        fun length(): Double {
            return sqrt(x * x + y * y + z * z)
        }

        fun asUnitVec(): Vector3D {
            return Vector3D(x / length(), y / length(), z / length())
        }

        operator fun unaryMinus(): Vector3D {
            return Vector3D(-x, -y, -z)
        }

        fun toBasicLocation(): BasicLocation {
            val latRad = acos(z / length()) / PI * 180
            val lonRad = atan2(y, x) / PI * 180

            return BasicLocation(90 - latRad, lonRad)
        }
    }
}

fun Location.toBasicLocation(): BasicLocation {
    return BasicLocation(latitude, longitude)
}

fun Location.distanceTo(other: BasicLocation): Float {
    val result = floatArrayOf(Float.NaN)
    Location.distanceBetween(latitude, longitude, other.latitude, other.longitude, result)
    return result[0]
}
