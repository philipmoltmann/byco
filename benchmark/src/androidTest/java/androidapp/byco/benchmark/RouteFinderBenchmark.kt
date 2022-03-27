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

package androidapp.byco.benchmark

import android.app.Application
import android.util.Log
import androidapp.byco.data.Node
import androidapp.byco.data.RouteFinderRepository
import androidapp.byco.util.observeAsChannel
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class RouteFinderBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    @Test
    fun findRouteDiridonToDolores() {
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

        benchmarkRule.measureRepeated {
            val startLoc = Node(-1, 37.334790, -121.888140)
            val goalLoc = Node(-1, 37.741249, -122.424217)

            runBlocking {
                val routeFinder = runWithTimingDisabled {
                    withContext(Main) {
                        RouteFinderRepository[app]
                    }
                }
                Log.d("Test", "Get nodes closest to home and goal")

                routeFinder.findRoute(startLoc, goalLoc).observeAsChannel().consumeAsFlow().first()
            }
        }
    }
}
