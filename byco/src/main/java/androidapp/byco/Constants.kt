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

import android.os.Process
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

enum class NotificationIds {
    INVALID,
    RIDE_RECORDING
}

enum class JobIds {
    INVALID,
    PREFETCHER
}

// files
const val RECORDINGS_DIRECTORY = "recordings"

// cache
const val THUMBNAILS_DIRECTORY = "thumbnails"
const val SHARE_DIRECTORY = "share"

const val ONGOING_TRIP_RECORDINGS_NOTIFICATION_CHANNEL =
    "ONGOING_TRIP_RECORDINGS_NOTIFICATION_CHANNEL"

const val FILE_PROVIDER_AUTHORITY = "androidapp.byco.fileprovider"

/** Single thread executor */
val SingleThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

/** A fixed set of threads */
val FixedThreads = (0 until 8).map { Executors.newSingleThreadExecutor().asCoroutineDispatcher() }

/** A fixed set of lower priority threads */
val FixedLowPriorityThreads = (0 until 8).map {
    Executors.newSingleThreadExecutor {
        object : Thread() {
            override fun run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_LESS_FAVORABLE)
                it.run()
            }
        }
    }.asCoroutineDispatcher()
}