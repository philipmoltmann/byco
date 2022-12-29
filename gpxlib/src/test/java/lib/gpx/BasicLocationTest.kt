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

import com.google.common.truth.DoubleSubject
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(minSdk = 28, maxSdk = 28)
class BasicTest {
    private fun DoubleSubject.isWithinPctOf(pct: Double, expected: Double) {
        isWithin(abs(pct * expected)).of(expected)
    }

    @Test
    fun bearingToNorth() {
        val a = BasicLocation(5.0, -2.0)
        val b = BasicLocation(10.0, -2.0)

        // Compare to Android's built in bearingTo
        assertThat(a.bearingTo(b)).isEqualTo(a.toLocation().bearingTo(b.toLocation()))
    }

    @Test
    fun bearingToEast() {
        val a = BasicLocation(5.0, 2.0)
        val b = BasicLocation(5.0, 4.0)

        // Compare to Android's built in bearingTo
        assertThat(a.bearingTo(b)).isEqualTo(a.toLocation().bearingTo(b.toLocation()))
    }

    @Test
    fun bearingToSouth() {
        val a = BasicLocation(5.0, 2.0)
        val b = BasicLocation(-10.0, 2.0)

        // Compare to Android's built in bearingTo
        assertThat(a.bearingTo(b)).isEqualTo(a.toLocation().bearingTo(b.toLocation()))
    }

    @Test
    fun bearingToWest() {
        val a = BasicLocation(5.0, 2.0)
        val b = BasicLocation(5.0, -4.0)

        // Compare to Android's built in bearingTo
        assertThat(a.bearingTo(b)).isEqualTo(a.toLocation().bearingTo(b.toLocation()))
    }

    @Test
    fun crossTrackPointKnownCoordinates() {
        val sf = BasicLocation(37.752215, -122.358186)
        val la = BasicLocation(33.923852, -118.065374)
        val fresno = BasicLocation(36.734603, -119.765577)

        val crossTrackFresnoSFToLA = fresno.closestNodeOn(sf, la)
        assertThat(crossTrackFresnoSFToLA.latitude).isWithinPctOf(0.01, 36.18)
        assertThat(crossTrackFresnoSFToLA.longitude).isWithinPctOf(0.01, -120.52)
    }
}