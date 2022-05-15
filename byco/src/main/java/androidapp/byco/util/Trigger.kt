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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn

/**
 * Wake up a `FlowCollector` collecting from [flow] each time [trigger] is called.
 */
class Trigger(coroutineScope: CoroutineScope) {
    private val updateRequest = Channel<Unit>(1, BufferOverflow.DROP_OLDEST)

    val flow = updateRequest.receiveAsFlow().onStart { emit(Unit) }
        .shareIn(coroutineScope, SharingStarted.WhileSubscribed(), 1)

    fun trigger() {
        updateRequest.trySend(Unit)
    }
}