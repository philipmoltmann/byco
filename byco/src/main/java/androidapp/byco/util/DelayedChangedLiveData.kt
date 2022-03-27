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

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData

/**
 * [LiveData] wrapper that changes value only if a new value stays contant with a certain delay
 */
class DelayedChangedLiveData<T>(
    private val delay: Long, wrapped: LiveData<T>,
    isEqual: (T, T?) -> Boolean = { a, b -> a == b }
) :
    MediatorLiveData<T>() {
    private var lastChange = Long.MIN_VALUE
    private var lastData: T? = null

    init {
        addSource(wrapped) { newData ->
            val now = System.currentTimeMillis()
            if (isEqual(newData, lastData)) {
                if (now - lastChange > delay) {
                    value = newData
                }
            } else {
                lastChange = now
            }
            lastData = newData
        }
    }
}