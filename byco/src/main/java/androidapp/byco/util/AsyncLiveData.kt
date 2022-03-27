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

package androidapp.byco.util

import androidapp.byco.SingleThread
import androidx.lifecycle.LiveData
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.Main

/** Async live data */
abstract class AsyncLiveData<T>(
    private val dispatcher: CoroutineDispatcher = Default,
    /** If `false` never set `null` values returned from [update] */
    private val setNullValues: Boolean = false
) : CoroutineAwareLiveData<T>() {
    private var retriever: Job? = null

    fun requestUpdateIfChanged(vararg sources: LiveData<*>) {
        sources.forEach { addSource(it) { requestUpdate() } }
    }

    override fun onActive() {
        super.onActive()

        requestUpdate()
    }

    /** Request live data to update */
    open fun requestUpdate() {
        liveDataScope.launch(SingleThread) {
            while (retriever?.isActive == true) {
                retriever?.cancelAndJoin()
            }

            retriever = launch(dispatcher) {
                val v = update()

                if (setNullValues || v != null) {
                    withContext(Main.immediate) {
                        value = v
                    }
                }
            }
        }
    }

    /** Compute and return the new value */
    abstract suspend fun update(): T?
}