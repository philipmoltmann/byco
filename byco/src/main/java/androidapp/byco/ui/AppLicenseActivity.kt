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
import android.view.ViewGroup.MarginLayoutParams
import androidapp.byco.lib.R
import androidapp.byco.lib.databinding.AppLicenseActivityBinding
import androidapp.byco.util.BycoActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

class AppLicenseActivity : BycoActivity() {
    private lateinit var binding: AppLicenseActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = AppLicenseActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { v: View, insets: WindowInsetsCompat ->
            val params = v.layoutParams as MarginLayoutParams
            params.topMargin = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            v.updatePadding(right = insets.getInsets(WindowInsetsCompat.Type.systemBars()).right)
            v.updatePadding(left = insets.getInsets(WindowInsetsCompat.Type.systemBars()).left)
            v.updatePadding(right = insets.getInsets(WindowInsetsCompat.Type.tappableElement()).right)
            v.updatePadding(left = insets.getInsets(WindowInsetsCompat.Type.tappableElement()).left)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.license) { v: View, insets: WindowInsetsCompat ->
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            v.updatePadding(right = insets.getInsets(WindowInsetsCompat.Type.systemBars()).right)
            v.updatePadding(left = insets.getInsets(WindowInsetsCompat.Type.systemBars()).left)
            v.updatePadding(right = insets.getInsets(WindowInsetsCompat.Type.tappableElement()).right)
            v.updatePadding(left = insets.getInsets(WindowInsetsCompat.Type.tappableElement()).left)
            insets
        }

        binding.license.text =
            resources.openRawResource(R.raw.app_license).use { it.readBytes().toString(Charsets.UTF_8) }
    }
}