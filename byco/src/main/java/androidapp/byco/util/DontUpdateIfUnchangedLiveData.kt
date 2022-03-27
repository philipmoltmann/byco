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

import androidx.lifecycle.MutableLiveData

/**
 * [MutableLiveData] that does not trigger a value update in dependencies if the new value is
 * unchanged (using the [Object.equals] method).
 */
open class DontUpdateIfUnchangedLiveData<T> : MutableLiveData<T>() {
    override fun setValue(newValue: T?) {
        if (newValue != value) {
            super.setValue(newValue)
        }
    }
}

/**
 * Wrap a [MutableLiveData] in a [DontUpdateIfUnchangedLiveData]. The wrapper has no own state and
 * just modifies and returns the wrapped state.
 */
fun <T> MutableLiveData<T>.dontUpdateIfUnchanged(): DontUpdateIfUnchangedLiveData<T> {
    return object : DontUpdateIfUnchangedLiveData<T>() {
        override fun setValue(newValue: T?) {
            if (newValue != this@dontUpdateIfUnchanged.value) {
                this@dontUpdateIfUnchanged.value = newValue
            }
        }

        override fun getValue(): T? {
            return this@dontUpdateIfUnchanged.value
        }
    }
}