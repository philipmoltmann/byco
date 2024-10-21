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
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import androidapp.byco.FILE_PROVIDER_AUTHORITY
import androidapp.byco.SHARE_DIRECTORY
import androidapp.byco.data.PreviousRidesRepository
import androidapp.byco.data.ThumbnailRepository
import androidapp.byco.data.writePreviousRide
import androidapp.byco.lib.R
import androidapp.byco.util.BycoViewModel
import androidapp.byco.util.compat.putExcludeComponentsExtraCompat
import androidapp.byco.util.plus
import androidapp.byco.util.stateIn
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import lib.gpx.DebugLog
import lib.gpx.GPX_FILE_EXTENSION
import lib.gpx.GPX_MIME_TYPE
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class ShareRideActivityViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
    private val rideFileName: String
) : BycoViewModel(application) {
    private val TAG = ShareRideActivityViewModel::class.java.simpleName

    private val KEY_REMOVE_START_AND_END = "remove_start_and_end"

    private val MIN_REMOVE_START_END_METERS = 200f
    private val REMOVE_START_END_METERS = 500f

    private val removeStart = MIN_REMOVE_START_END_METERS +
            abs(Random.nextInt()) % ((REMOVE_START_END_METERS - MIN_REMOVE_START_END_METERS) * 2)
    private val removeEnd = REMOVE_START_END_METERS * 2 - removeStart
    private lateinit var shareRideLauncher: ActivityResultLauncher<Intent>

    private val ride = PreviousRidesRepository[app].previousRides.map { previousRides ->
        previousRides.find { previousRide -> previousRide.file.name == rideFileName }
    }.stateIn(null)

    /** Get preview of to-be-shared ride */
    fun getPreview(isDarkMode: Boolean) =
        (ride + removeStartAndEnd).flatMapLatest { (ride, removeStartAndEnd) ->
            ride?.let {
                ThumbnailRepository[app].getThumbnailWithHighlightedStartAndEnd(
                    ride,
                    isDarkMode,
                    if (removeStartAndEnd) {
                        removeStart
                    } else {
                        0f
                    },
                    if (removeStartAndEnd) {
                        removeEnd
                    } else {
                        0f
                    }
                )
            } ?: flowOf(Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8))
        }

    val removeStartAndEnd = savedStateHandle.getStateFlow(KEY_REMOVE_START_AND_END, false)

    /** Set if some of start and end of ride should be removed (for privacy) */
    fun setRemoveStartAndEnd(removeStartAndEnd: Boolean) {
        savedStateHandle[KEY_REMOVE_START_AND_END] = removeStartAndEnd
    }

    /** Share a [ride] with apps that can use files with the [GPX_MIME_TYPE] */
    fun share() {
        ride.value?.let { ride ->
            viewModelScope.launch(Dispatchers.IO) {
                val share = File(app.cacheDir, SHARE_DIRECTORY).also { it.mkdirs() }
                val gpxToShare = File(
                    share,
                    (ride.title ?: app.getString(R.string.unknown_ride_name)) + GPX_FILE_EXTENSION
                )
                PreviousRidesRepository[app].getTrack(ride).first()?.let { track ->
                    gpxToShare.outputStream().buffered().use {
                        it.writePreviousRide(
                            track,
                            ride.time,
                            ride.title,
                            removeStart = if (removeStartAndEnd.value) {
                                removeStart
                            } else {
                                0f
                            },
                            removeEnd = if (removeStartAndEnd.value) {
                                removeEnd
                            } else {
                                0f
                            }
                        )
                    }
                }

                val uri = FileProvider.getUriForFile(app, FILE_PROVIDER_AUTHORITY, gpxToShare)

                // Temporarily enable additional sharing target. As there is no default handler to
                // write a file to disk (at least on Pixel devices), we need to add our own.
                //
                // Other options considered:
                // - Use direct share shortcuts: SaveToFile should not show up as Launcher icon
                // - Add Activity as [ChooserTarget]: Did not work on Android P and lower (even
                //   crashed on Samsung devices)
                SaveToFileActivity.setEnabled(app, true)

                try {
                    shareRideLauncher.launch(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType(GPX_MIME_TYPE)
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                .putExtra(SaveToFileActivity.EXTRA_FILE_NAME, gpxToShare.name),
                            null
                        ).putExcludeComponentsExtraCompat(
                            arrayOf(
                                ComponentName(
                                    app,
                                    AddRideActivity::class.java
                                )
                            )
                        )
                    )
                } catch (e: ActivityNotFoundException) {
                    DebugLog.e(TAG, "Cannot share $ride", e)
                }
            }
        }
    }

    /** Allow this view model to use the passed [activity] for start-with-activity-result */
    fun registerActivityResultsContracts(activity: AppCompatActivity) {
        shareRideLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            File(app.cacheDir, SHARE_DIRECTORY).deleteRecursively()
            SaveToFileActivity.setEnabled(app, false)
            activity.finish()
        }
    }

    fun cancel(activity: Activity) {
        activity.finish()
    }
}

class ShareRideActivityViewModelFactory(
    val application: Application,
    private val ride: String
) :
    ViewModelProvider.AndroidViewModelFactory(application) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        if (modelClass.isAssignableFrom(ShareRideActivityViewModel::class.java)) {
            return ShareRideActivityViewModel(
                application,
                extras.createSavedStateHandle(),
                ride
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}