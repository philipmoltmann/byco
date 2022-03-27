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
import android.app.job.JobService
import androidapp.byco.background.Prefetcher.ProcessPriority.BACKGROUND

/**
 * Job that runs the [Prefetcher] in the [BACKGROUND]
 */
class PrefetcherJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val prefetcher = Prefetcher[application]

        prefetcher.registerFinishedListener (object : Prefetcher.OnFinishedCallback {
            override fun onFinished() {
                jobFinished(params, false)
                prefetcher.unregisterFinishedListener(this)
            }
        })

        prefetcher.start(BACKGROUND)

        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Prefetcher[application].stop(BACKGROUND)

        return true
    }
}