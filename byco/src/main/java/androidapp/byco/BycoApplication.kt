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

package androidapp.byco

import android.app.Application
import androidapp.byco.background.Prefetcher
import androidapp.byco.util.compat.getProcessNameCompat
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class BycoApplication : Application() {
    val appScope by lazy { CoroutineScope(SupervisorJob() + Default + CoroutineName(BycoApplication::class.simpleName!!)) }

    override fun onCreate() {
        super.onCreate()

        // Clean shares that have not been cleared on startup
        File(cacheDir, SHARE_DIRECTORY).deleteRecursively()

        // Run prefetcher in main process.
        if (packageName == getProcessNameCompat(this)) {
            Prefetcher[this].start()
        }
    }
}