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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidapp.byco.lib.R
import androidapp.byco.util.compat.getParcelableExtraCompat
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Action receiver when adding a ride shared by another app
 */
class AddRideActivity : AppCompatActivity() {
    private val TAG = AddRideActivity::class.java.simpleName

    private val viewModel by viewModels<AddRideRecordingViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.add_ride_activity)

        if (savedInstanceState == null && intent.action == Intent.ACTION_SEND) {
            val src = contentResolver.openInputStream(
                intent.getParcelableExtraCompat(
                    Intent.EXTRA_STREAM,
                    Uri::class.java
                )!!
            )!!

            lifecycle.coroutineScope.launch(IO) {
                val addedRide = try {
                    viewModel.add(src)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not add ride", e)
                    null
                }

                withContext(Main) {
                    Toast.makeText(
                        this@AddRideActivity,
                        addedRide?.let { r ->
                            getString(
                                R.string.add_ride_result,
                                r.title ?: getString(R.string.unknown_ride_name)
                            )
                        } ?: getString(R.string.could_not_add_ride),
                        Toast.LENGTH_SHORT).show()
                }

                src.close()
                finish()
            }
        } else {
            finish()
        }
    }
}