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

package androidapp.byco.benchmark.views

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import androidapp.byco.data.MapDataRepository
import androidapp.byco.lib.R
import androidapp.byco.ui.views.MapView
import androidapp.byco.util.observeAsChannel
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import lib.gpx.BasicLocation
import lib.gpx.MapArea
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class MapViewBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    // 9 tiles
    private val mapArea = MapArea(37.44, -122.19, 37.47, -122.16)
    private val mapData = runBlocking { MapDataRepository[app].getMapData(mapArea).observeAsChannel().receive() }

    private val mapView = (app.getSystemService(LayoutInflater::class.java)
        .inflate(R.layout.thumbnail, null) as MapView).apply {

        measure(
            View.MeasureSpec.makeMeasureSpec(
                1024,
                View.MeasureSpec.EXACTLY
            ),
            View.MeasureSpec.makeMeasureSpec(
                1024,
                View.MeasureSpec.EXACTLY
            )
        )
        layout(0, 0, measuredWidth, measuredHeight)

        animateToLocation(BasicLocation(0.0, 0.0).toLocation(), animate = false)
        setZoomToInclude(BasicLocation(-1.0, -1.0), BasicLocation(1.0, 1.0), 0, 0)
    }

    private val canvas = Canvas(
        Bitmap.createBitmap(
            mapView.measuredWidth,
            mapView.measuredHeight, Bitmap.Config.ARGB_8888
        )
    )

    private fun clearCachedLocation() {
        mapData.forEach { way -> way.nodes.forEach { node -> node.toAbsolute(-1.2345f) } }
    }

    @Test
    fun setMapDataNoCachedLocation() {
        benchmarkRule.measureRepeated {
            runWithTimingDisabled {
                clearCachedLocation()
            }

            runBlocking {
                mapView.setMapDataSync("US", mapData)
            }
        }
    }

    @Test
    fun setMapData() {
        benchmarkRule.measureRepeated {
            runBlocking {
                mapView.setMapDataSync("US", mapData)
            }
        }
    }

    @Test
    fun renderFrameNoCachedLocation() {
        runBlocking {
            mapView.setMapDataSync("US", mapData)
        }

        benchmarkRule.measureRepeated {
            runWithTimingDisabled {
                clearCachedLocation()
            }

            runBlocking {
                mapView.renderFrame()
            }
        }
    }

    @Test
    fun renderFrame() {
        runBlocking {
            mapView.setMapDataSync("US", mapData)
        }

        benchmarkRule.measureRepeated {
            runBlocking {
                mapView.renderFrame()
            }
        }
    }

    @Test
    fun drawNoMapData() {
        benchmarkRule.measureRepeated {
            mapView.draw(canvas)
        }
    }

    @Test
    fun drawNoCachedFrameNoCachedLocation() {
        benchmarkRule.measureRepeated {
            runWithTimingDisabled {
                clearCachedLocation()
            }

            runBlocking {
                mapView.setMapDataSync("US", mapData)
            }

            mapView.draw(canvas)
        }
    }

    @Test
    fun drawNoCachedFrame() {
        benchmarkRule.measureRepeated {
            runBlocking {
                mapView.setMapDataSync("US", mapData)
            }

            mapView.draw(canvas)
        }
    }

    @Test
    fun drawCachedFrame() {
        runBlocking {
            mapView.setMapDataSync("US", mapData)
        }

        benchmarkRule.measureRepeated {
            mapView.draw(canvas)
        }
    }
}
