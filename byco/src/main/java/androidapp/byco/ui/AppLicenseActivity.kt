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
import androidapp.byco.lib.databinding.AppLicenseActivityBinding
import androidapp.byco.util.BycoActivity

class AppLicenseActivity : BycoActivity() {
    private lateinit var binding: AppLicenseActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = AppLicenseActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.license.text =
            resources.openRawResource(R.raw.app_license).use { it.readBytes().toString(Charsets.UTF_8) }
    }
}