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
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.math.BigDecimal
import kotlin.math.roundToInt

@RunWith(JUnit4::class)
class OpenTopographyDataProviderAndroidTest: BaseTest() {
    @Test
    fun getOpenTopographyDataFromNetwork() {
        runBlocking {
            val data = OpenTopographyDataProvider[app].getParsedOpenTopographyData(
                BigDecimal(37.4),
                BigDecimal(-122.2),
                BigDecimal(37.5),
                BigDecimal(-122.1)
            )

            assertThat(data.minLat).isWithin(data.cellSize).of(37.4)
            assertThat(data.minLon).isWithin(data.cellSize).of(-122.2)
            assertThat(data.cellSize).isGreaterThan(0)
            assertThat(data.elevations.size).isEqualTo((0.1 / data.cellSize).roundToInt())
            assertThat(data.elevations[0].size).isEqualTo((0.1 / data.cellSize).roundToInt())
        }
    }
}