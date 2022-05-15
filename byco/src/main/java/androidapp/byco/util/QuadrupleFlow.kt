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

data class Quadruple<out A, out B, out C, out D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

@OptIn(ExperimentalCoroutinesApi::class)
class QuadrupleFlow<A, B, C, D>(
    private val flowA: TripleFlow<A, B, C>,
    private val flowD: Flow<D>
) :
    AbstractFlow<Quadruple<A, B, C, D>>() {
    override suspend fun collectSafely(collector: FlowCollector<Quadruple<A, B, C, D>>) {
        flowA.combine(flowD) { (a, b, c), d ->
            Quadruple(a, b, c, d)
        }.collect(collector)
    }

    operator fun <E> plus(flowE: Flow<E>): QuintupleFlow<A, B, C, D, E> {
        return QuintupleFlow(this, flowE)
    }
}