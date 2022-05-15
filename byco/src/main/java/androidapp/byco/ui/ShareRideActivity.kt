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

import android.os.Bundle
import androidapp.byco.lib.R
import androidapp.byco.lib.databinding.ShareRideActivityBinding
import androidapp.byco.util.BycoActivity
import androidapp.byco.util.isDarkMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * Dialog that allow configuring settings when sharing a ride
 */
class ShareRideActivity : BycoActivity() {
    private lateinit var binding: ShareRideActivityBinding
    private val viewModel by lazy {
        ViewModelProvider(
            this,
            ShareRideActivityViewModelFactory(
                app,
                intent.getStringExtra(EXTRA_PREVIOUS_RIDE_FILE_NAME)!!
            )
        )[ShareRideActivityViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.registerActivityResultsContracts(this)

        binding = ShareRideActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cancel.setOnClickListener {
            viewModel.cancel(this)
        }

        binding.share.setOnClickListener {
            viewModel.share()
        }

        binding.shareMode.setOnCheckedChangeListener { _, checkedId ->
            viewModel.setRemoveStartAndEnd(checkedId == R.id.remove_start_and_end)
            binding.shareModeCoach.neverShowAgain()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.removeStartAndEnd.collect { removeStartAndEnd ->
                        if (removeStartAndEnd) {
                            binding.removeStartAndEnd.isChecked = true
                        } else {
                            binding.wholeRide.isChecked = true
                        }
                    }
                }

                launch {
                    binding.routePreviewOutline?.clipToOutline = true
                    viewModel.getPreview(isDarkMode()).collect {
                        binding.routePreview?.setImageBitmap(it)
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_PREVIOUS_RIDE_FILE_NAME = "EXTRA_PREVIOUS_RIDE_FILE_NAME"
    }
}