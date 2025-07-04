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

import android.annotation.SuppressLint
import android.location.Location
import android.os.Parcel
import android.os.Parcelable.Creator

/**
 * A recorded location, either by this app or read from a GPX file.
 */
@Suppress("EqualsOrHashCode")
class RecordedLocation(
    latitude: Double,
    longitude: Double,
    val elevation: Double?,
    /** UTC time of this recording, in milliseconds since January 1, 1970 */
    val time: Long?
) : BasicLocation(latitude, longitude) {
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }

        if (other == null || other !is RecordedLocation) {
            return false
        }

        return latitude == other.latitude && longitude == other.longitude
                && elevation == other.elevation && time == other.time
    }

    override fun toLocation(): Location {
        return Location("from recorded").apply {
            latitude = this@RecordedLocation.latitude
            longitude = this@RecordedLocation.longitude
            this@RecordedLocation.elevation?.let { altitude = it }
            this@RecordedLocation.time?.let { time = it }
        }
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        super.writeToParcel(parcel, flags)
        parcel.writeValue(elevation)
        parcel.writeValue(time)
    }

    companion object {
        @Suppress("unused")
        @JvmField
        val CREATOR = object : Creator<RecordedLocation> {
            @Suppress("DEPRECATION")
            @SuppressLint("ParcelClassLoader")
            override fun createFromParcel(source: Parcel): RecordedLocation {
                val basicLocation =
                    source.readParcelable<BasicLocation>(BasicLocation::class.java.classLoader)
                return RecordedLocation(
                    basicLocation!!.latitude,
                    basicLocation.longitude,
                    source.readValue(null) as Double?,
                    source.readValue(null) as Long?
                )
            }

            override fun newArray(size: Int) = Array<RecordedLocation?>(size) { null }
        }
    }
}

fun Location.toRecordedLocation(): RecordedLocation {
    return RecordedLocation(latitude, longitude, altitude, time)
}