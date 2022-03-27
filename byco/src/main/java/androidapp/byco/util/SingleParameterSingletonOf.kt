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

/**
 * A companion object to make a class a singleton taking a single parameter for the instance to
 * get created. Parameters passed later are ignored.
 */
open class SingleParameterSingletonOf<P, T>(private val creator: (P) -> T) {
    @Volatile
    private var instance: T? = null

    operator fun get(creatorArgs: P): T {
        return instance ?: run {
            synchronized(this) {
                // Check instance again as some other thread might have initialized it since the
                // last check
                instance ?: run {
                    return creator(creatorArgs).also { instance = it }
                }
            }
        }
    }
}