/*
 * Copyright 2024 Google LLC
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

package androidapp.byco.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.AbstractFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.combine

data class Sextuple<out A, out B, out C, out D, out E, out F>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
    val sixth: F
)

@OptIn(ExperimentalCoroutinesApi::class)
class SextupleFlow<A, B, C, D, E, F>(
    private val flowA: QuintupleFlow<A, B, C, D, E>,
    private val flowF: Flow<F>
) : AbstractFlow<Sextuple<A, B, C, D, E, F>>() {
    override suspend fun collectSafely(collector: FlowCollector<Sextuple<A, B, C, D, E, F>>) {
        flowA.combine(flowF) { (a, b, c, d, e), f ->
            Sextuple(a, b, c, d, e, f)
        }.collect(collector)
    }
}