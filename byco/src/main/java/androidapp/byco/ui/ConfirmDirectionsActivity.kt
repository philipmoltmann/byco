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
import android.view.View
import androidapp.byco.lib.databinding.ConfirmDirectionsActivityBinding
import androidapp.byco.util.BycoActivity
import androidapp.byco.util.isDarkMode
import androidapp.byco.util.makeVisibleIf
import androidapp.byco.util.plus
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/** Find directions back to the start of the ride */
class ConfirmDirectionsActivity : BycoActivity() {
    private val viewModel by viewModels<ConfirmDirectionsActivityViewModel>()
    private lateinit var binding: ConfirmDirectionsActivityBinding

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.registerActivityResultsContracts(this)

        binding = ConfirmDirectionsActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cancel.setOnClickListener {
            viewModel.cancel(this)
        }

        binding.confirm.setOnClickListener {
            viewModel.confirm(this)
        }

        binding.openMappingApp?.setOnClickListener {
            viewModel.openMappingApp()
        }

        binding.routePreviewOutline.clipToOutline = true

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    callbackFlow {
                        val listener =
                            View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                                launch {
                                    send(right - left to bottom - top)
                                }
                            }
                        binding.routePreview.addOnLayoutChangeListener(listener)

                        awaitClose { binding.routePreview.removeOnLayoutChangeListener(listener) }
                    }.distinctUntilChanged().flatMapLatest { (width, height) ->
                        viewModel.getRoutePreview(width, height, isDarkMode())
                    }.collect {
                        binding.routePreview.setImageBitmap(it)
                    }
                }

                launch {
                    viewModel.shouldShowMappingAppButton.collect {
                        binding.openMappingApp?.makeVisibleIf(it)
                    }
                }

                launch {
                    viewModel.areDirectionsFound.collect {
                        binding.progress.makeVisibleIf(!it)
                    }
                }

                launch {
                    viewModel.cannotFindDirections.collect {
                        binding.cannotFindDirections.makeVisibleIf(it)
                    }
                }

                launch {
                    (viewModel.areDirectionsFound + viewModel.cannotFindDirections).collect { (areDirectionsFound, cannotFindDirections) ->
                        binding.confirm.isEnabled =
                            areDirectionsFound == true && cannotFindDirections == false
                    }
                }
            }
        }
    }
}