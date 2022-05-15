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

package androidapp.byco.background

import android.app.job.JobParameters
import android.util.Log
import androidapp.byco.util.BycoJobService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Job to run the [Prefetcher]
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrefetcherJobService : BycoJobService(), LifecycleOwner {
    private val TAG = PrefetcherJobService::class.java.simpleName

    var isFinished = false

    override fun onStartJob(params: JobParameters?): Boolean {
        super.onStartJob(params)

        // The prefetcher is started by the [BycoApplication] as the [BycoJobService] sets the
        // [ProcessPriority] to [BACKGROUND]

        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    Prefetcher[app].isPrefetchWorkPending.filterNotNull().filter { !it }
                        .collect {
                            isFinished = true

                            Log.i(TAG, "No more prefetching work")
                            jobFinished(params, false)
                            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                        }
                }
            }
        }

        return !isFinished
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        super.onStopJob(params)

        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)

        return !isFinished
    }

    override val lifecycle = LifecycleRegistry(this)
}