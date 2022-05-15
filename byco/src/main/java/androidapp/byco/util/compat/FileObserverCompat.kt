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

package androidapp.byco.util.compat

import android.os.Build
import android.os.FileObserver
import java.io.File

fun createFileObserverCompat(
    path: File,
    onEvent: ((event: Int, path: String?) -> Unit)
): FileObserver {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        object : FileObserver(path) {
            override fun onEvent(event: Int, path: String?) {
                onEvent(event, path)
            }
        }
    } else {
        @Suppress("DEPRECATION")
        object : FileObserver(path.absolutePath) {
            override fun onEvent(event: Int, path: String?) {
                onEvent(event, path)
            }
        }
    }
}