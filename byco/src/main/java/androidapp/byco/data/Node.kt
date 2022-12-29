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

import android.util.ArraySet
import androidapp.byco.ui.views.MapView
import androidx.annotation.VisibleForTesting
import lib.gpx.BasicLocation
import java.util.concurrent.atomic.AtomicLong

/**
 * Generated [Node] IDs have ids < 0. Use this to avoid conflicts as [Node]s with the same id are
 * considered the same regardless of lat/lon
 *
 * @see Node.equals
 */
private val lastUsedGeneratedNodeId = AtomicLong(0)

/**
 * A location on the map
 *
 * @param ways the [Way]s going through this node, i.e. for intersections
 */
open class Node(
    /** Unique OSM-ID of the [Node] */
    @get:VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    val id: Long = lastUsedGeneratedNodeId.decrementAndGet(),

    latitude: Double,
    longitude: Double,

    /**
     * RW version of [ways]
     */
    @get:VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    val mutableWays: MutableSet<Way> = ArraySet(),
    val ways: Set<Way> = mutableWays
) : BasicLocation(latitude, longitude) {
    private var cachedAbsoluteCoordinatesZoom = 18f
    private var cachedAbsoluteCoordinates: MapView.AbsoluteCoordinates? = null

    /**
     * Convert [Node] to [MapView.AbsoluteCoordinates] for a [zoom] level using the
     * [web-mercator projection](https://en.wikipedia.org/wiki/Web_Mercator_projection).
     *
     * The returned value is cached, hence future lookups for the same [zoom] level will be very
     * fast.
     */
    fun toAbsolute(zoom: Float): MapView.AbsoluteCoordinates {
        return if (zoom == cachedAbsoluteCoordinatesZoom && cachedAbsoluteCoordinates != null) {
            cachedAbsoluteCoordinates!!
        } else {
            (this as BasicLocation).toAbsolute(zoom).also {
                cachedAbsoluteCoordinates = it
                cachedAbsoluteCoordinatesZoom = zoom
            }
        }
    }

    /**
     * Get all neighbors of this [Node].
     *
     * In the unlikely case that two [Way]s connect two [Node]s, there might be duplicates.
     *
     * A [Node] is not a neighbor of a [Node] if you have to go against a one-way street to get to
     * the other [Node].
     */
    fun getNeighbors(): List<Pair<Node, Way>> {
        val neighbors = ArrayList<Pair<Node, Way>>(ways.size * 2)

        ways.forEach { way ->
            neighbors += way.getNeighborsOf(this).map { it to way }
        }
        return neighbors
    }

    /**
     * Get [Way] that directly connects this and other [Node] (no [Node] in between) if such a
     * [Way] exists.
     */
    fun getWayTo(other: Node): Way? {
        return ways.find { it.areNeighbors(this, other) }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }

        if (other == null || other !is Node) {
            return false
        }

        return id == other.id
    }

    override fun hashCode(): Int {
        return id.toInt()
    }

    override fun toString(): String {
        return "Node(id=$id, latitude=$latitude, longitude=$longitude, ways=${ways.map { it.id }})"
    }
}

/**
 * Create a new [Node] for this location. This will change the `equals` behavior of the new object
 * as [Node]s `equal` by id, [BasicLocation]s by lat/lon.
 */
fun BasicLocation.toNode() = Node(latitude = latitude, longitude = longitude)
