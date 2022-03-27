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

import android.graphics.Bitmap
import android.os.Bundle
import androidapp.byco.background.Prefetcher
import androidapp.byco.background.Prefetcher.ProcessPriority.FOREGROUND
import androidapp.byco.lib.databinding.ConfirmDirectionsActivityBinding
import androidapp.byco.util.isDarkMode
import androidapp.byco.util.makeVisibleIf
import androidapp.byco.util.plus
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer

/** Find directions back to the start of the ride */
class ConfirmDirectionsActivity : AppCompatActivity() {
    private val viewModel by viewModels<ConfirmDirectionsActivityViewModel>()
    private lateinit var binding: ConfirmDirectionsActivityBinding

    private val routePreviewObserver = Observer<Bitmap> { binding.routePreview.setImageBitmap(it) }

    private var lastPreviewWidth = -1
    private var lastPreviewHeight = -1
    private var routePreviewViewModel: LiveData<Bitmap>? = null
    private fun updateRoutePreview() {
        val width = binding.routePreview.width
        val height = binding.routePreview.height

        if (lastPreviewWidth != width || lastPreviewHeight != height) {
            routePreviewViewModel?.removeObserver(routePreviewObserver)
            routePreviewViewModel = null
            lastPreviewWidth = width
            lastPreviewHeight = height

            if (width > 0 && height > 0) {
                routePreviewViewModel = viewModel.getRoutePreview(width, height, isDarkMode())
                routePreviewViewModel!!.observe(this, routePreviewObserver)
            }
        }
    }

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

        viewModel.shouldShowMappingAppButton.observe(this) {
            binding.openMappingApp?.makeVisibleIf(it)
        }

        viewModel.areDirectionsFound.observe(this) {
            binding.progress.makeVisibleIf(!it)
        }

        viewModel.cannotFindDirections.observe(this) {
            binding.cannotFindDirections.makeVisibleIf(it)
        }

        (viewModel.areDirectionsFound + viewModel.cannotFindDirections).observe(this) { (areDirectionsFound, cannotFindDirections) ->
            binding.confirm.isEnabled = areDirectionsFound == true && cannotFindDirections == false
        }

        binding.routePreviewOutline.clipToOutline = true
        binding.routePreview.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateRoutePreview()
        }
    }

    override fun onStart() {
        super.onStart()

        Prefetcher[application].start(FOREGROUND)
    }

    override fun onStop() {
        super.onStop()

        Prefetcher[application].stop(FOREGROUND)
    }
}