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

import java.lang.ref.WeakReference

class SelfCleaningCache<K, V>(private val NUM_CACHED: Int) {
    private val data = LinkedHashMap<K, WeakReference<V>>(NUM_CACHED)

    operator fun get(key: K): V? {
        // TODO: Cycling each time sounds expensive
        val valueRef = data.remove(key)
        return if (valueRef != null) {
            val value = valueRef.get()
            if (value != null) {
                set(key, value)

                return value
            } else {
                null
            }
        } else {
            null
        }
    }

    private fun cleanEmptyReferences() {
        data.keys.filter { key -> data[key] == null }.forEach { key ->
            data.remove(key)
        }
    }

    operator fun set(key: K, value: V) {
        if (NUM_CACHED > 0) {
            if (data.size > NUM_CACHED - 1) {
                cleanEmptyReferences()

                if (data.size > NUM_CACHED - 1) {
                    data.remove(data.keys.first())
                }
            }

            data.remove(key)
            data[key] = WeakReference(value)
        }
    }
}