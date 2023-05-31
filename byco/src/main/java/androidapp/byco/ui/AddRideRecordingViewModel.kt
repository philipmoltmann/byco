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

package androidapp.byco.ui

import android.app.Application
import androidapp.byco.data.PreviousRide
import androidapp.byco.data.PreviousRidesRepository
import androidx.lifecycle.AndroidViewModel
import java.io.InputStream

/** ViewModel for [AddRideActivity] */
class AddRideRecordingViewModel(private val app: Application) : AndroidViewModel(app) {
    /** Add a [ride] loaded from an [InputStream] to the list of known `PreviousRide`s */
    suspend fun add(ride: InputStream, progressCallback: suspend (Float?) -> Unit): PreviousRide? =
        PreviousRidesRepository[app].addRide(ride, null, progressCallback)
}
