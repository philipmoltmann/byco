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

import android.app.job.JobInfo
import android.os.Build

fun JobInfo.Builder.setRequiresBatteryNotLowCompat(requiresBatteryNotLow: Boolean): JobInfo.Builder {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        setRequiresBatteryNotLow(requiresBatteryNotLow)
    } else {
        // not available on this API version
    }

    return this
}

fun JobInfo.Builder.setRequiresStorageNotLowCompat(requiresStorageNotLow: Boolean): JobInfo.Builder {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        setRequiresStorageNotLow(requiresStorageNotLow)
    } else {
        // not available on this API version
    }

    return this
}