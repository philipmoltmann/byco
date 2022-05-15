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
class PairFlow<A, B>(private val flowA: Flow<A>, private val flowB: Flow<B>) :
    AbstractFlow<Pair<A, B>>() {

    operator fun <C> plus(flowC: Flow<C>): TripleFlow<A, B, C> {
        return TripleFlow(this, flowC)
    }

    override suspend fun collectSafely(collector: FlowCollector<Pair<A, B>>) {
        flowA.combine(flowB) { a, b -> a to b }.collect(collector)
    }
}

operator fun <A, B> Flow<A>.plus(flowB: Flow<B>): PairFlow<A, B> {
    return PairFlow(this, flowB)
}