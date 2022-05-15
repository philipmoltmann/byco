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

@OptIn(ExperimentalCoroutinesApi::class)
class TripleFlow<A, B, C>(private val flowA: PairFlow<A, B>, private val flowC: Flow<C>) :
    AbstractFlow<Triple<A, B, C>>() {
    override suspend fun collectSafely(collector: FlowCollector<Triple<A, B, C>>) {
        flowA.combine(flowC) { (a, b), c ->
            Triple(a, b, c)
        }.collect(collector)
    }

    operator fun <D> plus(flowD: Flow<D>): QuadrupleFlow<A, B, C, D> {
        return QuadrupleFlow(this, flowD)
    }
}