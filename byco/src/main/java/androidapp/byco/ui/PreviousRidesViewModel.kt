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

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidapp.byco.data.PreviousRide
import androidapp.byco.data.PreviousRidesRepository
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import lib.gpx.DebugLog
import lib.gpx.GPX_MIME_TYPE
import java.io.FileInputStream
import java.text.DateFormat
import java.util.*

/** ViewModel for [PreviousRidesActivity] */
class PreviousRidesViewModel(private val app: Application, state: SavedStateHandle) :
    AndroidViewModel(app) {
    private val TAG = PreviousRidesRepository::class.java.simpleName
    private val KEY_TITLE_FILTER = "title_filter"

    private lateinit var addRideLauncher: ActivityResultLauncher<Array<String>>

    val titleFilter = state.getLiveData<String?>(KEY_TITLE_FILTER, null)

    /**
     * Ride that is currently shown.
     *
     * @see show
     */
    val shownRide = PreviousRidesRepository[app].rideShownOnMap

    /** All [PreviousRide]s currently known to the app */
    val previousRides = object : MediatorLiveData<List<PreviousRide>>() {
        init {
            addSource(PreviousRidesRepository[app].previousRides) { update() }
            addSource(titleFilter) { update() }
        }

        private fun update() {
            value = titleFilter.value?.let { titleFilter ->
                PreviousRidesRepository[app].previousRides.value?.filter {
                    it.title?.lowercase()?.contains(titleFilter.lowercase()) == true
                            || it.time?.let { time ->
                        DateFormat.getDateTimeInstance(
                            DateFormat.SHORT,
                            DateFormat.SHORT
                        ).format(Date(time)).contains(titleFilter)
                    } == true
                }
            } ?: PreviousRidesRepository[app].previousRides.value
        }
    }

    /** Trigger the UI flow to add a ride */
    fun add() {
        addRideLauncher.launch(arrayOf("*/*", GPX_MIME_TYPE))
    }

    /** Delete a [ride] */
    fun delete(ride: PreviousRide) = PreviousRidesRepository[app].delete(ride)

    /** Share a [ride] with other apps */
    fun share(activity: Activity, ride: PreviousRide) {
        activity.startActivity(
            Intent(activity, ShareRideActivity::class.java).putExtra(
                ShareRideActivity.EXTRA_PREVIOUS_RIDE_FILE_NAME,
                ride.file.name
            )
        )
    }

    /**
     * Set a [ride] to be shown on the map in the [RidingActivity]. Set to `null` to unset.
     *
     * @see shownRide
     */
    fun show(ride: PreviousRide?) = PreviousRidesRepository[app].showOnMap(ride)

    /** Change the title of a existing [ride] */
    fun changeTitle(ride: PreviousRide, newTitle: String) {
        viewModelScope.launch {
            PreviousRidesRepository[app].changeTitle(ride, newTitle)
        }
    }

    /** Allow this view model to use the passed [activity] for start-with-activity-result */
    fun registerActivityResultsContracts(activity: AppCompatActivity) {
        addRideLauncher = activity.registerForActivityResult(OpenMultipleDocuments()) { uris ->
            uris.forEach { uri ->
                viewModelScope.launch(IO) {
                    try {
                        app.contentResolver.openFileDescriptor(
                            uri,
                            "r"
                        )?.fileDescriptor?.let { fd ->
                            PreviousRidesRepository[app].addRide(
                                FileInputStream(fd)
                            )
                        }
                    } catch (e: Exception) {
                        DebugLog.e(TAG, "Cannot add $uri", e)
                    }
                }
            }
        }
    }
}