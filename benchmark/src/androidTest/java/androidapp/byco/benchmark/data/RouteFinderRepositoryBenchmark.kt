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
import androidapp.byco.data.RouteFinderRepository
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import com.google.common.truth.Truth.assertThat
import lib.gpx.BasicLocation

@RunWith(JUnit4::class)
class RouteFinderRepositoryBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    @Test
    fun findRouteDiridonToGoogle() {
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BycoApplication

        benchmarkRule.measureRepeated {
            val startLoc = BasicLocation(37.334790, -121.888140)
            val goalLoc = BasicLocation(37.423622, -122.087327)

            runBlocking {
                val routeFinder = runWithTimingDisabled {
                    withContext(Main) {
                        RouteFinderRepository[app]
                    }
                }

                val route =
                    routeFinder.findRoute(startLoc, goalLoc, postIntermediateUpdates = false)
                        .first()
                assertThat(route.first().toString()).isEqualTo(startLoc.toString())
                assertThat(route.last().toString()).isEqualTo(goalLoc.toString())
            }
        }
    }
}
