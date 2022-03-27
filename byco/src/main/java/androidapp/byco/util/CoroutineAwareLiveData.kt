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

import androidx.lifecycle.MediatorLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/** A [MediatorLiveData] having a [CoroutineScope] */
open class CoroutineAwareLiveData<T> : MediatorLiveData<T>() {
    // Start with canceled scope to reject coroutines before the live data becomes active
    private var currentScope = CoroutineScope(Job().apply { cancel()})

    /** Current coroutine scope, canceled as soon as live data becomes inactive. */
    protected val liveDataScope: CoroutineScope
        get() {
            return synchronized(this) {
                currentScope
            }
        }

    override fun onActive() {
        super.onActive()

        synchronized(this) {
            currentScope = CoroutineScope(Job())
        }
    }

    /** Once called [liveDataScope] is inactive */
    override fun onInactive() {
        super.onInactive()

        synchronized(this) {
            currentScope.cancel()
        }
    }
}