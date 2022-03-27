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

import androidapp.byco.ui.views.MapView
import lib.gpx.BasicLocation
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/**
 * Convert [BasicLocation] to [MapView.AbsoluteCoordinates] for a [zoom] level using the
 * [web-mercator projection](https://en.wikipedia.org/wiki/Web_Mercator_projection).
 */
fun BasicLocation.toAbsolute(zoom: Float): MapView.AbsoluteCoordinates {
    val zoomFactor = 2f.pow(zoom)
    val mapHeight = 256 * zoomFactor
    val mapWidth = 512 * zoomFactor

    return MapView.AbsoluteCoordinates(
        (longitude + 180) * mapWidth / 360,
        (mapHeight / 2) - (mapWidth * ln(tan((PI / 4) + (latitude * PI / 360))) / (2 * PI))
    )
}
