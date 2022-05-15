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

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class LocationRepositoryTest : BaseLocationTest() {
    private fun assertIsMovingIs(shouldBeMoving: Boolean) {
        var isFirst = true
        assertThat(runBlocking {
            LocationRepository[app].smoothedLocation.filter { smoothedLocation ->
                // The first value might be stale, hence it is acceptable to ignore it
                if (isFirst && smoothedLocation?.isMoving != shouldBeMoving) {
                    isFirst = true
                    false
                } else {
                    true
                }
            }.first()
        }).isEqualTo(shouldBeMoving)
    }

    @Test(timeout = 10000)
    fun isNotMovingByStandingStill() {
        testScope.launch {
            while (isActive) {
                setTestLocation()
                delay(100)
            }
        }

        assertIsMovingIs(false)
    }

    @Test(timeout = 10000)
    fun isMovingByLatChangeAndSpeed() {
        testScope.launch {
            var lat = 0.0
            while (isActive) {
                setTestLocation(latitude = lat, speed = 3F)
                delay(100)
                lat = (lat + 0.1) % 180
            }
        }

        assertIsMovingIs(true)
    }

    @Ignore("Broken, maybe app not mock provider")
    @Test(timeout = 10000)
    fun isNotMovingByLatChangeOnly() {
        testScope.launch {
            var lat = 0.0
            while (isActive) {
                setTestLocation(latitude = lat)
                delay(100)
                lat = (lat + 0.1) % 180
            }
        }

        assertIsMovingIs(false)
    }

    @Test(timeout = 10000)
    fun isNotMovingBySpeedOnly() {
        testScope.launch {
            while (isActive) {
                setTestLocation(speed = 3F)
                delay(100)
            }
        }

        assertIsMovingIs(false)
    }
}