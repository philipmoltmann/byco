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
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidapp.byco.lib.R
import androidx.lifecycle.AndroidViewModel

/** ViewModel for [AboutActivity] */
class AboutActivityViewModel(private val app: Application) : AndroidViewModel(app) {
    fun close(activity: Activity) {
        activity.finish()
    }

    fun openPrivacyPolicy(activity: Activity) {
        activity.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                    app.packageManager.getApplicationInfo(
                        app.packageName,
                        PackageManager.GET_META_DATA
                    ).metaData.getString("privacy_policy_url")
                )
            )
        )
    }

    fun openLicenses(activity: Activity) {
        try {
            activity.startActivity(Intent("androidapp.byco.action.SHOW_LICENSES").apply {
                `package` = activity.packageName
            })
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(app, R.string.cannot_find_licenses, Toast.LENGTH_SHORT).show()
        }
    }
}
