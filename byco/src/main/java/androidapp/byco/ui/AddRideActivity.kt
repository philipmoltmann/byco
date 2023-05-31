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
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidapp.byco.data.PreviousRide
import androidapp.byco.lib.R
import androidapp.byco.lib.databinding.AddRideActivityBinding
import androidapp.byco.util.compat.getParcelableArrayListExtraCompat
import androidapp.byco.util.compat.getParcelableExtraCompat
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Action receiver when adding a ride shared by another app
 */
class AddRideActivity : AppCompatActivity() {
    private val TAG = AddRideActivity::class.java.simpleName

    private val viewModel by viewModels<AddRideRecordingViewModel>()
    private lateinit var binding: AddRideActivityBinding

    private suspend fun addRide(src: InputStream): PreviousRide? {
        src.use {
            val addedRide = try {
                viewModel.add(src) { progress ->
                    withContext(Main) {
                        progress?.let {
                            binding.progress.isIndeterminate = false
                            binding.progress.progress = (progress * binding.progress.max).toInt()
                        } ?: run {
                            binding.progress.isIndeterminate = true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not add ride", e)
                null
            }

            withContext(Main) {
                Toast.makeText(this@AddRideActivity, addedRide?.let { r ->
                    getString(
                        R.string.add_ride_result,
                        r.title ?: getString(R.string.unknown_ride_name)
                    )
                } ?: getString(R.string.could_not_add_ride), Toast.LENGTH_SHORT).show()
            }

            return addedRide
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = AddRideActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cancel.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        try {
            if (savedInstanceState == null && intent.action == Intent.ACTION_SEND) {
                val src = contentResolver.openInputStream(
                    intent.getParcelableExtraCompat(
                        Intent.EXTRA_STREAM, Uri::class.java
                    )!!
                )!!

                lifecycle.coroutineScope.launch(IO) {
                    try {
                        addRide(src)
                        setResult(Activity.RESULT_OK)
                    } finally {
                        src.close()
                        finish()
                    }
                }

                setResult(Activity.RESULT_OK)
            } else if (savedInstanceState == null && intent.action == ACTION_ADD_MULTIPLE) {
                val srcs = intent.getParcelableArrayListExtraCompat(
                    EXTRA_URIS, Uri::class.java
                )!!.map {
                    contentResolver.openInputStream(
                        it
                    )!!
                }

                lifecycle.coroutineScope.launch(IO) {
                    try {
                        val resultData = Intent()
                        resultData.putStringArrayListExtra(
                            EXTRA_ADDED_FILE_NAMES,
                            ArrayList(srcs.mapNotNull { src -> addRide(src) }.map { it.file.name })
                        )

                        setResult(RESULT_OK, resultData)
                    } finally {
                        srcs.forEach { it.close() }
                        finish()
                    }
                }
            } else {
                setResult(Activity.RESULT_FIRST_USER)
                finish()
            }
        } catch (e: Exception) {
            setResult(Activity.RESULT_FIRST_USER)
            finish()
        }
    }

    companion object {
        const val EXTRA_URIS = "EXTRA_URIS"
        const val EXTRA_ADDED_FILE_NAMES = "ADDED_FILE_NAMES"
        const val ACTION_ADD_MULTIPLE = "ACTION_ADD_MULTIPLE"
    }
}