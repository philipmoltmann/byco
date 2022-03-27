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

package androidapp.byco.data

import android.location.Location
import android.os.SystemClock
import androidapp.byco.ui.RidingActivity
import androidx.test.ext.junit.rules.activityScenarioRule
import com.google.android.gms.location.LocationServices
import org.junit.After
import org.junit.Before
import org.junit.Rule

open class BaseLocationTest : BaseCoroutineTest() {
    @get:Rule
    val activity = activityScenarioRule<RidingActivity>()

    private val fusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    @Before
    fun enableMockLocations() {
        fusedLocationProviderClient.setMockMode(true)
    }

    @After
    fun disableMockLocations() {
        fusedLocationProviderClient.setMockMode(false)
    }

    fun setTestLocation(
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        accuracy: Float = 1F,
        time: Long = System.currentTimeMillis(),
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
        bearing: Float = 0F,
        bearingAccuracyDegrees: Float = 0F,
        speed: Float = 0F,
        speedAccuracyMetersPerSecond: Float = 0f
    ) {
        LocationServices.getFusedLocationProviderClient(app).setMockLocation(
            Location("test").also {
                it.latitude = latitude
                it.longitude = longitude
                it.accuracy = accuracy
                it.time = time
                it.elapsedRealtimeNanos = elapsedRealtimeNanos
                it.bearing = bearing
                it.bearingAccuracyDegrees = bearingAccuracyDegrees
                it.speed = speed
                it.speedAccuracyMetersPerSecond = speedAccuracyMetersPerSecond
            })
    }
}