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

package lib.gpx

import android.util.Log

/**
 * Like [Log] but only prints in non-release builds
 */
class DebugLog {
    companion object {
        @Suppress("KotlinConstantConditions")
        const val isEnabled = BuildConfig.BUILD_TYPE != "release"

        fun v(tag: String, message: String, exception: Throwable? = null) {
            if (isEnabled) {
                if (exception == null) {
                    Log.v(tag, message)
                } else {
                    Log.v(tag, message, exception)
                }
            }
        }

        fun d(tag: String, message: String, exception: Throwable? = null) {
            if (isEnabled) {
                if (exception == null) {
                    Log.d(tag, message)
                } else {
                    Log.d(tag, message, exception)
                }
            }
        }

        fun i(tag: String, message: String, exception: Throwable? = null) {
            if (isEnabled) {
                if (exception == null) {
                    Log.i(tag, message)
                } else {
                    Log.i(tag, message, exception)
                }
            }
        }

        fun w(tag: String, message: String, exception: Throwable? = null) {
            if (isEnabled) {
                if (exception == null) {
                    Log.w(tag, message)
                } else {
                    Log.w(tag, message, exception)
                }
            }
        }

        fun e(tag: String, message: String, exception: Throwable? = null) {
            if (isEnabled) {
                if (exception == null) {
                    Log.e(tag, message)
                } else {
                    Log.e(tag, message, exception)
                }
            }
        }
    }
}