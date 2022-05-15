/*
 * Copyright 2023 Google LLC
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

import android.app.Service
import androidapp.byco.BycoApplication
import androidapp.byco.data.PhoneStateRepository
import androidapp.byco.data.PhoneStateRepository.ProcessPriority.BACKGROUND
import androidx.annotation.CallSuper

abstract class BycoService : Service() {
    val app by lazy { application as BycoApplication }

    @CallSuper
    override fun onCreate() {
        super.onCreate()

        PhoneStateRepository[app].processPriority.onStart(BACKGROUND)
    }

    @CallSuper
    override fun onDestroy() {
        super.onDestroy()

        PhoneStateRepository[app].processPriority.onStart(BACKGROUND)
    }
}