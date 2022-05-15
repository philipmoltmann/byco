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

package androidapp.byco.views

import android.location.Location
import androidapp.byco.ui.views.MapView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(minSdk = 28, maxSdk = 28)
class MapViewTest {
    private val mapView = spyk(MapView(ApplicationProvider.getApplicationContext())).apply {
        every { width } returns 1080
        every { height } returns 1920

        centerAndLocation = mockk<Pair<Location?, Location?>>().apply {
            every { first } returns mockk<Location>().apply {
                every { latitude } returns 37.44
                every { longitude } returns -122.20
                every { bearing } returns 42f
            }

            every { second } returns mockk<Location>().apply {
                every { latitude } returns 37.44
                every { longitude } returns -122.20
                every { bearing } returns 42f
            }
        }
    }

    @Test
    fun locationToAbsoluteAndBack() {
        val location = Location("test").apply {
            latitude = 37.43
            longitude = -122.21
        }

        mapView.apply {
            val absolute = location.toAbsolute()
            val doubleTransformed = absolute.toLocation()

            assertThat(doubleTransformed.latitude).isWithin(0.0001).of(location.latitude)
            assertThat(doubleTransformed.longitude).isWithin(0.0001).of(location.longitude)
        }
    }

    @Test
    fun absoluteToLocationAndBack() {
        val absolute = MapView.AbsoluteCoordinates( 500.0,  700.0)

        mapView.apply {
            val location = absolute.toLocation()
            val doubleTransformed = location.toAbsolute()

            assertThat(doubleTransformed.x).isWithin(0.0001).of(absolute.x)
            assertThat(doubleTransformed.y).isWithin(0.0001).of(absolute.y)
        }
    }

    @Test
    fun latLonToMapXYAndBack() {
        val location = Location("test").apply {
            latitude = 37.43
            longitude = -122.21
        }

        mapView.apply {
            val map = location.toMap()
            val doubleTransformed = map.toLocation()

            assertThat(doubleTransformed.latitude).isWithin(0.0001).of(location.latitude)
            assertThat(doubleTransformed.longitude).isWithin(0.0001).of(location.longitude)
        }
    }

    @Test
    fun mapXYToLatLonAndBack() {
        val map = MapView.MapCoordinates( 500.0,  700.0)

        mapView.apply {
            val location = map.toLocation()
            val doubleTransformed = location.toMap()

            assertThat(doubleTransformed.x).isWithin(0.0001f).of(map.x)
            assertThat(doubleTransformed.y).isWithin(0.0001f).of(map.y)
        }
    }
}
