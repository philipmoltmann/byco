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

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Parcelable

fun <T : Parcelable> Intent.getParcelableExtraCompat(name: String, clazz: Class<T>): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, clazz)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }
}

fun <T : Parcelable> Intent.getParcelableArrayListExtraCompat(
    name: String,
    clazz: Class<T>
): ArrayList<T>? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(name, clazz)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayListExtra(name)
    }
}

fun Intent.putExcludeComponentsExtraCompat(components: Array<ComponentName>): Intent {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        // Prevent sending back to the this app
        putExtra(
            Intent.EXTRA_EXCLUDE_COMPONENTS,
            components
        )
    } else {
        // not available on this API version
    }

    return this
}