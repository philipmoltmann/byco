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

package androidapp.byco.benchmark.data

import androidapp.byco.BycoApplication
import androidapp.byco.data.ElevationData
import androidapp.byco.data.ElevationDataRepository
import androidapp.byco.data.MapDataRepository
import androidapp.byco.data.Node
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import lib.gpx.MapArea
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.math.BigDecimal
import java.math.RoundingMode

@RunWith(JUnit4::class)
class ElevationDataBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BycoApplication
    private val diridonStation = Node(-1, 37.334790, -121.888140)
    private val tileMult = BigDecimal.TEN.pow(ElevationDataRepository.TILE_SCALE)
    private val tileSize = BigDecimal.ONE.divide(tileMult)

    private val minLat = diridonStation.latitude.toBigDecimal()
        .setScale(MapDataRepository.TILE_SCALE, RoundingMode.DOWN)
    private val minLon = diridonStation.longitude.toBigDecimal()
        .setScale(MapDataRepository.TILE_SCALE, RoundingMode.DOWN)
    private val mapArea = MapArea(minLat, minLon, minLat.plus(tileSize), minLon.plus(tileSize))

    @Test
    fun mergeElevationDataLat() {
        val repo = runBlocking {
            withContext(Main) { ElevationDataRepository(app, numCachedTiles = 0) }
        }

        val tile1 = runBlocking {
            repo.getElevationData(mapArea).first()
        }!!

        val tile2 = runBlocking {
            repo.getElevationData(
                MapArea(
                    mapArea.minLat + tileSize,
                    mapArea.minLon,
                    mapArea.maxLat + tileSize,
                    mapArea.maxLon
                )
            ).first()
        }!!

        benchmarkRule.measureRepeated {
            runBlocking {
                tile1.merge(tile2)
            }
        }
    }

    @Test
    fun mergeElevationDataLon() {
        val repo = runBlocking {
            withContext(Main) { ElevationDataRepository(app, numCachedTiles = 0) }
        }

        val tile1 = runBlocking {
            repo.getElevationData(mapArea).first()
        }!!

        val tile2 = runBlocking {
            repo.getElevationData(
                MapArea(
                    mapArea.minLat,
                    mapArea.minLon + tileSize,
                    mapArea.maxLat,
                    mapArea.maxLon + tileSize
                )
            ).first()
        }!!

        benchmarkRule.measureRepeated {
            runBlocking {
                tile1.merge(tile2)
            }
        }
    }

    @Test
    fun mergeElevationDataWithDifferentOffsetLat() {
        val repo = runBlocking {
            withContext(Main) { ElevationDataRepository(app, numCachedTiles = 0) }
        }

        val tile1 = runBlocking {
            repo.getElevationData(mapArea).first()
        }!!

        val tile2 = runBlocking {
            repo.getElevationData(
                MapArea(
                    mapArea.minLat + tileSize,
                    mapArea.minLon,
                    mapArea.maxLat + tileSize,
                    mapArea.maxLon
                )
            ).first()
        }!!
        val tile2WithDifferentOffset = ElevationData(
            tile2.area,
            tile2.latOffset + 0.000001,
            tile2.lonOffset + 0.000001,
            tile2.cellSize,
            tile2.elevation
        )

        benchmarkRule.measureRepeated {
            runBlocking {
                tile1.merge(tile2WithDifferentOffset)
            }
        }
    }

    @Test
    fun mergeElevationDataWithDifferentOffsetLon() {
        val repo = runBlocking {
            withContext(Main) { ElevationDataRepository(app, numCachedTiles = 0) }
        }

        val tile1 = runBlocking {
            repo.getElevationData(mapArea).first()
        }!!

        val tile2 = runBlocking {
            repo.getElevationData(
                MapArea(
                    mapArea.minLat,
                    mapArea.minLon + tileSize,
                    mapArea.maxLat,
                    mapArea.maxLon + tileSize
                )
            ).first()
        }!!
        val tile2WithDifferentOffset = ElevationData(
            tile2.area,
            tile2.latOffset + 0.000001,
            tile2.lonOffset + 0.000001,
            tile2.cellSize,
            tile2.elevation
        )

        benchmarkRule.measureRepeated {
            runBlocking {
                tile1.merge(tile2WithDifferentOffset)
            }
        }
    }
}
