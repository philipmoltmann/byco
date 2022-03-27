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

@RunWith(JUnit4::class)
class OsmDataProviderAndroidTest {
    @Test
    fun getOSMDataFromNetwork() {
        runBlocking {
            val data = OsmDataProvider.getParsedOsmData(
                BigDecimal(37.45),
                BigDecimal(-122.19),
                BigDecimal(37.46),
                BigDecimal(-122.18)
            )

            assertThat(data.first).isNotEmpty()
            assertThat(data.second).isNotEmpty()
        }
    }
}
