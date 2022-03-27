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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidapp.byco.lib.R
import androidapp.byco.lib.databinding.SaveToFileActivityBinding
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import lib.gpx.DebugLog

/**
 * Share action Receiver to store a file to disk. Not sure why there is no default target for this.
 */
class SaveToFileActivity : AppCompatActivity() {
    private val TAG = SaveToFileActivity::class.java.simpleName

    private lateinit var binding: SaveToFileActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val fileName = intent.getStringExtra(EXTRA_FILE_NAME)

        binding = SaveToFileActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.saveToFileMsg.text = getString(R.string.save_to_file_message, fileName)

        val selectFileLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument()) { uri ->
                uri?.let {
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            contentResolver.openOutputStream(uri)!!.buffered().use { out ->
                                contentResolver.openInputStream(
                                    intent.getParcelableExtra(Intent.EXTRA_STREAM)!!
                                )!!.buffered().use { ins ->
                                    ins.copyTo(out)
                                }
                            }
                        } catch (e: Exception) {
                            DebugLog.e(TAG, "Cannot save $fileName to $uri", e)
                        }

                        finish()
                    }
                } ?: run {
                    finish()
                }
            }

        if (savedInstanceState == null) {
            selectFileLauncher.launch(fileName)
        }
    }

    companion object {
        const val EXTRA_FILE_NAME = "EXTRA_PREVIOUS_RIDE_FILE_NAME"

        /** Set this activity as enabled/disabled */
        fun setEnabled(context: Context, isEnabled: Boolean) {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(
                    context,
                    SaveToFileActivity::class.java
                ), if (isEnabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }, PackageManager.DONT_KILL_APP
            )
        }
    }
}