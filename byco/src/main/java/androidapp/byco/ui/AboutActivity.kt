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
import androidapp.byco.lib.databinding.AboutActivityBinding
import androidapp.byco.util.BycoActivity
import androidapp.byco.util.compat.getPackageInfoCompat
import androidx.activity.viewModels

/**
 * About activity.
 */
class AboutActivity : BycoActivity() {
    private val viewModel by viewModels<AboutActivityViewModel>()
    private lateinit var binding: AboutActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = AboutActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.version.text = packageManager.getPackageInfoCompat(packageName, 0).versionName
        binding.appIcon.clipToOutline = true

        binding.close.setOnClickListener { viewModel.close(this) }
        binding.privacyPolicy.setOnClickListener { viewModel.openPrivacyPolicy(this) }
        binding.dependenciesLicenses.setOnClickListener { viewModel.openDependenciesLicenses(this) }
        binding.appLicense.setOnClickListener { viewModel.openAppLicenses(this) }
    }
}