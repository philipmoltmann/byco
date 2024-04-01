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
import androidapp.byco.data.MapDataRepository
import androidapp.byco.data.Node
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import lib.gpx.MapArea
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.math.BigDecimal
import java.math.RoundingMode

@RunWith(JUnit4::class)
class MapDataRepositoryBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BycoApplication
    private val diridonStation = Node(-1, 37.334790, -121.888140)
    private val tileMult = BigDecimal.TEN.pow(MapDataRepository.TILE_SCALE)
    private val tileSize = BigDecimal.ONE.divide(tileMult)

    private val minLat = diridonStation.latitude.toBigDecimal()
        .setScale(MapDataRepository.TILE_SCALE, RoundingMode.DOWN)
    private val minLon = diridonStation.longitude.toBigDecimal()
        .setScale(MapDataRepository.TILE_SCALE, RoundingMode.DOWN)
    private val mapArea = MapArea(minLat, minLon, minLat.plus(tileSize), minLon.plus(tileSize))

    @Before
    fun cacheTileOnDisk() {
        val repo = MapDataRepository(app, numCachedTiles = 0)

        // Make sure data is cached on disk
        runBlocking {
            repo.getMapDataTile(minLat to minLon).first()
        }
    }

    @Test
    fun getMergedMapData() {
        val repo = MapDataRepository(app, numCachedTiles = 6) // assume partially cached

        val largerArea = MapArea(
            mapArea.minLat - tileSize,
            mapArea.minLon - tileSize,
            mapArea.maxLat + tileSize,
            mapArea.maxLon + tileSize
        )

        benchmarkRule.measureRepeated {
            runBlocking {
                repo.getMapData(largerArea).first()
            }
        }
    }

    @Test
    fun getMapData() {
        val repo = MapDataRepository(app, numCachedTiles = 0)

        benchmarkRule.measureRepeated {
            runBlocking {
                repo.getMapData(mapArea).first()
            }
        }
    }

    @Test
    fun getMapDataTile() {
        val repo = MapDataRepository(app, numCachedTiles = 0)

        // Make sure data is cached on disk
        runBlocking {
            repo.getMapDataTile(minLat to minLon).first()
        }

        benchmarkRule.measureRepeated {
            runBlocking {
                repo.getMapDataTile(minLat to minLon).first()
            }
        }
    }

    @Test
    fun getCachedInMemMapDataTile() {
        val repo = MapDataRepository(app, numCachedTiles = 1)

        // Make sure data is cached in memory
        runBlocking {
            repo.getMapDataTile(minLat to minLon).first()
        }

        benchmarkRule.measureRepeated {
            runBlocking {
                repo.getMapDataTile(minLat to minLon).first()
            }
        }
    }
}
