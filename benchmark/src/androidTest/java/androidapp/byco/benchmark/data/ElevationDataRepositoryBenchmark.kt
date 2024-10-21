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
import androidapp.byco.data.ElevationDataRepository
import androidapp.byco.data.ElevationTileKey
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.math.BigDecimal
import java.math.RoundingMode

@RunWith(JUnit4::class)
class ElevationDataRepositoryBenchmark {
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

    @Before
    fun cacheTileOnDisk() {
        val repo = runBlocking {
            withContext(Main) { ElevationDataRepository(app, numCachedTiles = 0) }
        }

        // Make sure data is cached on disk
        runBlocking {
            repo.getElevationDataTile(ElevationTileKey(minLat, minLon)).first()
        }
    }

    @Test
    fun getMergedElevationData() {
        val repo = runBlocking {
            withContext(Main) {
                ElevationDataRepository(
                    app,
                    numCachedTiles = 6
                )
            } // assume partially cached
        }

        val largerArea = MapArea(
            mapArea.minLat - tileSize,
            mapArea.minLon - tileSize,
            mapArea.maxLat + tileSize,
            mapArea.maxLon + tileSize
        )

        benchmarkRule.measureRepeated {
            runBlocking {
                repo.getElevationData(largerArea).first()
            }
        }
    }

    @Test
    fun getElevationData() {
        val repo = runBlocking {
            withContext(Main) { ElevationDataRepository(app, numCachedTiles = 0) }
        }

        benchmarkRule.measureRepeated {
            runBlocking {
                repo.getElevationData(mapArea).first()
            }
        }
    }

    @Test
    fun getElevationDataTile() {
        val repo = runBlocking {
            withContext(Main) { ElevationDataRepository(app, numCachedTiles = 0) }
        }

        // Make sure data is cached in memory
        runBlocking {
            repo.getElevationDataTile(ElevationTileKey(minLat, minLon)).first()
        }

        benchmarkRule.measureRepeated {
            runBlocking {
                repo.getElevationDataTile(ElevationTileKey(minLat, minLon))
                    .first()
            }
        }
    }

    @Test
    fun getCachedInMemElevationDataTile() {
        val repo = runBlocking {
            withContext(Main) { ElevationDataRepository(app, numCachedTiles = 1) }
        }

        // Make sure data is cached in memory
        runBlocking {
            repo.getElevationDataTile(ElevationTileKey(minLat, minLon)).first()
        }

        benchmarkRule.measureRepeated {
            runBlocking {
                repo.getElevationDataTile(ElevationTileKey(minLat, minLon))
                    .first()
            }
        }
    }
}
