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
import androidapp.byco.util.BycoViewModel
import androidapp.byco.util.plus
import androidapp.byco.util.stateIn
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import lib.gpx.GPX_MIME_TYPE
import java.text.DateFormat
import java.util.Date

/** ViewModel for [PreviousRidesActivity] */
class PreviousRidesViewModel(application: Application, private val state: SavedStateHandle) :
    BycoViewModel(application) {
    private val KEY_TITLE_FILTER = "title_filter"

    private lateinit var selectFileToAddLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var addRideLauncher: ActivityResultLauncher<Intent>

    val titleFilter = state.getStateFlow<String?>(KEY_TITLE_FILTER, null)

    fun setTitleFilter(filter: String?) {
        state[KEY_TITLE_FILTER] = filter
    }

    /**
     * Ride that is currently shown.
     *
     * @see show
     */
    val shownRide = PreviousRidesRepository[app].rideShownOnMap

    /** All [PreviousRide]s currently known to the app */
    val previousRides =
        (PreviousRidesRepository[app].previousRides + titleFilter).map { (previousRides, titleFilter) ->
            titleFilter?.let {
                previousRides.filter {
                    it.title?.lowercase()?.contains(titleFilter.lowercase()) == true
                            || it.time?.let { time ->
                        DateFormat.getDateTimeInstance(
                            DateFormat.SHORT,
                            DateFormat.SHORT
                        ).format(Date(time)).contains(titleFilter)
                    } == true
                }
            } ?: previousRides
        }.stateIn(emptyList())

    /** Trigger the UI flow to add a ride */
    fun add() {
        selectFileToAddLauncher.launch(arrayOf("*/*", GPX_MIME_TYPE))
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
    fun show(ride: PreviousRide?) {
        viewModelScope.launch {
            PreviousRidesRepository[app].showOnMap(ride)
        }
    }

    /** Change the title of a existing [ride] */
    fun changeTitle(ride: PreviousRide, newTitle: String) {
        viewModelScope.launch {
            PreviousRidesRepository[app].changeTitle(ride, newTitle)
        }
    }

    /** The most recently added ride. */
    val recentlyAddedRide = MutableLiveData<PreviousRide?>()

    /** Allow this view model to use the passed [activity] for start-with-activity-result */
    fun registerActivityResultsContracts(activity: AppCompatActivity) {
        addRideLauncher =
            activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                if (it.resultCode == Activity.RESULT_OK) {
                    it.data?.getStringArrayListExtra(AddRideActivity.EXTRA_ADDED_FILE_NAMES)
                        ?.lastOrNull()
                        ?.let { lastAddedFile ->
                            PreviousRidesRepository[app].previousRides.value.find { ride -> ride.file.name == lastAddedFile }
                                .let { lastAddedRide ->
                                    recentlyAddedRide.value = lastAddedRide
                                    recentlyAddedRide.value = null
                                }
                        }
                }
            }

        selectFileToAddLauncher =
            activity.registerForActivityResult(OpenMultipleDocuments()) { uris ->
                addRideLauncher.launch(
                    Intent(AddRideActivity.ACTION_ADD_MULTIPLE).setPackage(app.packageName)
                        .putParcelableArrayListExtra(AddRideActivity.EXTRA_URIS, ArrayList(uris))
                )
            }
    }
}