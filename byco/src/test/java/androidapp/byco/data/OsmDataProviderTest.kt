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
import io.mockk.coEvery
import io.mockk.slot
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(minSdk = 28, maxSdk = 28)
class OsmDataProviderTest {
    private val provider by lazy {
        spyk(OsmDataProvider).apply {
            val minLat = slot<BigDecimal>()
            val minLon = slot<BigDecimal>()

            coEvery {
                getOSMData(capture(minLat), capture(minLon), any(), any())
            } coAnswers {
                @Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
                this@OsmDataProviderTest.javaClass.classLoader.getResourceAsStream(
                    String.format(
                        "xml/%.2f,%.2f.xml", minLat.captured.toDouble(),
                        minLon.captured.toDouble()
                    )
                ).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        }
    }

    @Test
    fun parseOSMData() {
        val data = runBlocking {
            provider.getParsedOsmData(
                BigDecimal.valueOf(37.45),
                BigDecimal.valueOf(-122.19),
                BigDecimal.valueOf(37.46),
                BigDecimal.valueOf(-122.18)
            )
        }
        assertThat(data.first).isNotEmpty()
        assertThat(data.second).isNotEmpty()
    }
}