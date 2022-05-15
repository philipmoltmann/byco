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

data class Quintuple<out A, out B, out C, out D, out E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E
)

@OptIn(ExperimentalCoroutinesApi::class)
class QuintupleFlow<A, B, C, D, E>(
    private val flowA: QuadrupleFlow<A, B, C, D>,
    private val flowD: Flow<E>
) : AbstractFlow<Quintuple<A, B, C, D, E>>() {
    override suspend fun collectSafely(collector: FlowCollector<Quintuple<A, B, C, D, E>>) {
        flowA.combine(flowD) { (a, b, c, d), e ->
            Quintuple(a, b, c, d, e)
        }.collect(collector)
    }
}