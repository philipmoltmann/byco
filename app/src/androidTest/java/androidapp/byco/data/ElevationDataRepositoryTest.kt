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

import androidapp.byco.util.observeAsChannel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import lib.gpx.MapArea
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.math.BigDecimal
import java.math.RoundingMode

@RunWith(JUnit4::class)
class ElevationDataRepositoryTest : BaseCoroutineTest() {
    @Test(timeout = 30_000)
    fun getElevation() {
        val repo = runBlocking(Main) {
            ElevationDataRepository[app]
        }
        runBlocking {
            val data = repo.getElevationData(
                MapArea(
                    BigDecimal(37.4).setScale(1, RoundingMode.HALF_UP),
                    BigDecimal(-122.2).setScale(1, RoundingMode.HALF_UP),
                    BigDecimal(37.5).setScale(1, RoundingMode.HALF_UP),
                    BigDecimal(-122.1).setScale(1, RoundingMode.HALF_UP)
                )
            ).observeAsChannel().consumeAsFlow().first()

            assertThat(data.getElevation(37.4, -122.2)).isWithin(1f).of(90f)
            assertThat(data.getElevation(37.5, -122.2)).isWithin(1f).of(0f)
            assertThat(data.getElevation(37.4, -122.1)).isWithin(1f).of(20f)
            assertThat(data.getElevation(37.5, -122.1)).isWithin(1f).of(0f)

            assertThat(data.getElevation(37.45, -122.15)).isWithin(1f).of(13f)
        }
    }
}