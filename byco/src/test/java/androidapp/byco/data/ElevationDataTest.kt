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

import androidapp.byco.util.TWO
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import lib.gpx.MapArea
import org.junit.Test
import java.math.BigDecimal.ONE
import java.math.BigDecimal.ZERO

class ElevationDataTest {
    @Test
    fun minmax() {
        val e = ElevationData(
            MapArea(ZERO, ZERO, ONE, ONE),
            0.0,
            0.0,
            0.5,
            arrayOf(
                shortArrayOf(0, 2),
                shortArrayOf(3, 1),
            )
        )

        // min/max (remember: origin is lower left, x == lon, y == lat)
        // Even though the max is past the last value the data structure pretends it is
        // == the last value
        assertThat(e.getElevation(0.0, 0.0)).isEqualTo(3f)
        assertThat(e.getElevation(0.0, 1.0)).isEqualTo(1f)
        assertThat(e.getElevation(1.0, 0.0)).isEqualTo(0f)
        assertThat(e.getElevation(1.0, 1.0)).isEqualTo(2f)
    }

    @Test
    fun corners() {
        val e = ElevationData(
            MapArea(ZERO, ZERO, ONE, ONE),
            0.0,
            0.0,
            0.5,
            arrayOf(
                shortArrayOf(0, 2),
                shortArrayOf(3, 1),
            )
        )

        // corners (remember: origin is lower left, x == lon, y == lat)
        assertThat(e.getElevation(0.0, 0.0)).isEqualTo(3f)
        assertThat(e.getElevation(0.0, 0.5)).isEqualTo(1f)
        assertThat(e.getElevation(0.5, 0.0)).isEqualTo(0f)
        assertThat(e.getElevation(0.5, 0.5)).isEqualTo(2f)
    }

    @Test
    fun interpolation() {
        val e = ElevationData(
            MapArea(ZERO, ZERO, ONE, ONE),
            0.0,
            0.0,
            0.5,
            arrayOf(
                shortArrayOf(0, 2),
                shortArrayOf(3, 1),
            )
        )

        // middle
        assertThat(e.getElevation(0.25, 0.25)).isEqualTo(1.5f)

        // middle of edges
        assertThat(e.getElevation(0.0, 0.25)).isEqualTo(2f)
        assertThat(e.getElevation(0.25, 0.5)).isEqualTo(1.5f)
        assertThat(e.getElevation(0.5, 0.25)).isEqualTo(1f)
        assertThat(e.getElevation(0.25, 0.0)).isEqualTo(1.5f)
    }

    @Test
    fun offset() {
        val nonOffset = ElevationData(
            MapArea(ZERO, ZERO, ONE, ONE),
            0.0,
            0.0,
            0.5,
            arrayOf(
                shortArrayOf(0, 2),
                shortArrayOf(3, 1),
            )
        )

        val offset = ElevationData(
            MapArea(ZERO, ZERO, ONE, ONE),
            0.1,
            0.1,
            0.5,
            arrayOf(
                shortArrayOf(0, 2),
                shortArrayOf(3, 1),
            )
        )

        // corners (remember: origin is lower left, x == lon, y == lat)
        assertThat(nonOffset.getElevation(0.0, 0.0)).isEqualTo(offset.getElevation(0.1, 0.1))
        //assertThat(nonOffset.getElevation(-0.1, -0.1)).isEqualTo(offset.getElevation(0.0, 0.0))
        assertThat(nonOffset.getElevation(0.0, 0.5)).isEqualTo(offset.getElevation(0.1, 0.6))
        // assertThat(nonOffset.getElevation(-0.1, 0.4)).isEqualTo(offset.getElevation(0.0, 0.5))
        assertThat(nonOffset.getElevation(0.5, 0.0)).isEqualTo(offset.getElevation(0.6, 0.1))
        //assertThat(nonOffset.getElevation(0.4, -0.1)).isEqualTo(offset.getElevation(0.5, 0.0))
        assertThat(nonOffset.getElevation(0.5, 0.5)).isEqualTo(offset.getElevation(0.6, 0.6))
        assertThat(nonOffset.getElevation(0.4, 0.4)).isEqualTo(offset.getElevation(0.5, 0.5))

        // center
        assertThat(nonOffset.getElevation(0.15, 0.15)).isEqualTo(offset.getElevation(0.25, 0.25))
        assertThat(nonOffset.getElevation(0.25, 0.25)).isEqualTo(offset.getElevation(0.35, 0.35))
    }

    @Test
    fun mergeLat() {
        runBlocking {
            val west = ElevationData(
                MapArea(ZERO, ZERO, ONE, ONE),
                0.0,
                0.0,
                0.5,
                arrayOf(
                    shortArrayOf(200, 300),
                    shortArrayOf(100, 400),
                )
            )

            val east = ElevationData(
                MapArea(ZERO, ONE, ONE, TWO),
                0.0,
                0.0,
                0.5,
                arrayOf(
                    shortArrayOf(600, 700),
                    shortArrayOf(500, 800),
                )
            )

            val e = east.merge(west)

            assertThat(e.getElevation(0.0, 0.0)).isEqualTo(west.getElevation(0.0, 0.0))
            assertThat(e.getElevation(0.5, 0.0)).isEqualTo(west.getElevation(0.5, 0.0))
            assertThat(e.getElevation(0.5, 1.5)).isEqualTo(east.getElevation(0.5, 1.5))
            assertThat(e.getElevation(0.0, 1.5)).isEqualTo(east.getElevation(0.0, 1.5))

            // Direction or merge is irrelevant
            assertThat(e).isEqualTo(west.merge(east))
        }
    }

    @Test
    fun mergeLon() {
        runBlocking {
            val north = ElevationData(
                MapArea(ONE, ZERO, TWO, ONE),
                0.0,
                0.0,
                0.5,
                arrayOf(
                    shortArrayOf(600, 700),
                    shortArrayOf(500, 800),
                )
            )

            val south = ElevationData(
                MapArea(ZERO, ZERO, ONE, ONE),
                0.0,
                0.0,
                0.5,
                arrayOf(
                    shortArrayOf(200, 300),
                    shortArrayOf(100, 400),
                )
            )

            val e = north.merge(south)

            assertThat(e.getElevation(0.0, 0.0)).isEqualTo(south.getElevation(0.0, 0.0))
            assertThat(e.getElevation(1.5, 0.0)).isEqualTo(north.getElevation(1.5, 0.0))
            assertThat(e.getElevation(1.5, 0.5)).isEqualTo(north.getElevation(1.5, 0.5))
            assertThat(e.getElevation(0.0, 0.5)).isEqualTo(south.getElevation(0.0, 0.5))

            // Direction or merge is irrelevant
            assertThat(e).isEqualTo(south.merge(north))
        }
    }

    @Test
    fun mergeLatWithOffset() {
        runBlocking {
            val west = ElevationData(
                MapArea(ZERO, ZERO, ONE, ONE),
                0.0,
                0.0,
                0.5,
                arrayOf(
                    shortArrayOf(200, 300),
                    shortArrayOf(100, 400),
                )
            )

            val east = ElevationData(
                MapArea(ZERO, ONE, ONE, TWO),
                0.1,
                0.1,
                0.5,
                arrayOf(
                    shortArrayOf(600, 700),
                    shortArrayOf(500, 800),
                )
            )

            val e = east.merge(west)

            assertThat(e.getElevation(0.0, 0.0)).isEqualTo(west.getElevation(0.0, 0.0))
            assertThat(e.getElevation(0.5, 0.0)).isEqualTo(west.getElevation(0.5, 0.0))
            assertThat(e.getElevation(0.5, 1.5)).isEqualTo(east.getElevation(0.5, 1.5))
            assertThat(e.getElevation(0.0, 1.5)).isEqualTo(east.getElevation(0.0, 1.5))

            // Direction or merge is irrelevant
            assertThat(e).isEqualTo(west.merge(east))
        }
    }

    @Test
    fun mergeLonWithOffset() {
        runBlocking {
            val north = ElevationData(
                MapArea(ONE, ZERO, TWO, ONE),
                0.0,
                0.0,
                0.5,
                arrayOf(
                    shortArrayOf(600, 700),
                    shortArrayOf(500, 800),
                )
            )

            val south = ElevationData(
                MapArea(ZERO, ZERO, ONE, ONE),
                0.1,
                0.1,
                0.5,
                arrayOf(
                    shortArrayOf(200, 300),
                    shortArrayOf(100, 400),
                )
            )

            val e = north.merge(south)

            assertThat(e.getElevation(0.0, 0.0)).isEqualTo(south.getElevation(0.0, 0.0))
            assertThat(e.getElevation(1.5, 0.0)).isEqualTo(north.getElevation(1.5, 0.0))
            assertThat(e.getElevation(1.5, 0.5)).isEqualTo(north.getElevation(1.5, 0.5))
            assertThat(e.getElevation(0.0, 0.5)).isEqualTo(south.getElevation(0.0, 0.5))

            // Direction or merge is irrelevant
            assertThat(e).isEqualTo(south.merge(north))
        }
    }
}
