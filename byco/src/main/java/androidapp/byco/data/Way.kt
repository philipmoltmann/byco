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

import androidapp.byco.data.OsmDataProvider.HighwayType
import androidapp.byco.ui.views.MapView
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

/**
 * Generated way IDs have ids < 0. Use this to avoid conflicts as Ways with the same id are
 * considered the same regardless of lat/lon
 */
private val lastUsedGeneratedWayId = AtomicLong(0)

/**
 * A road, way, street, or highway
 *
 * @param isOpen `true` iff the [Way] is linear, `false` if circular
 * @param isOneway `true` iff the way is one way
 * @param highway the [HighwayType] of the way
 * @param name the name of the way
 * @param bicycle bicycle tag
 * @param surface surface of a way
 * @param service service-type of a way (according to OSM spec), i.e. a more detailed description
 * for ways of [HighwayType.SERVICE].
 * @param nodesArray location of the [Way] modeled as array of [Node]s
 */
class Way(
    /** Unique OSM-Id of the [Way] */
    internal val id: Long = lastUsedGeneratedWayId.decrementAndGet(),
    private val isOpen: Boolean = true,
    val isOneway: Boolean = false,
    val highway: HighwayType?,
    val name: String? = null,
    val bicycle: OsmDataProvider.BicycleType? = null,
    val surface: OsmDataProvider.SurfaceType? = null,
    val service: OsmDataProvider.ServiceType? = null,
    val nodesArray: Array<Node>
) {
    constructor(
        id: Long,
        isOpen: Boolean,
        isOneWay: Boolean,
        highway: HighwayType?,
        name: String?,
        bicycle: OsmDataProvider.BicycleType?,
        surface: OsmDataProvider.SurfaceType?,
        service: OsmDataProvider.ServiceType?,
        nodes: List<Node>
    ) : this(id, isOpen, isOneWay, highway, name, bicycle, surface, service, nodes.toTypedArray())

    /** [Node]s that describe where the track is */
    val nodes: List<Node>
        get() {
            return nodesArray.toList()
        }

    /** Check if two [Node]s are neighbors in this [Way] */
    private fun areNeighbors(indexOfA: Int, b: Node): Boolean {
        assert(indexOfA >= 0 && indexOfA < nodesArray.size)
        assert(nodesArray.contains(b))

        if (nodesArray.size == 1) {
            return false
        }

        if (indexOfA < nodesArray.size - 1 && nodesArray[indexOfA + 1] == b) {
            return true
        }

        if (indexOfA > 0 && !isOneway && nodesArray[indexOfA - 1] == b) {
            return true
        }

        if (indexOfA == 0 && !isOneway && !isOpen && nodesArray[nodesArray.size - 1] == b) {
            return true
        }

        return indexOfA == nodesArray.size - 1 && !isOpen && nodesArray[0] == b
    }

    /**
     * Check if two [Node] are neighbors in this [Way]
     *
     * A [Node] is not a neighbor of a [Node] if you have to go against a one-way street to get to
     * the other [Node].
     */
    fun areNeighbors(a: Node, b: Node): Boolean {
        assert(nodesArray.contains(a))
        assert(nodesArray.contains(b))

        return areNeighbors(nodesArray.indexOf(a), b)
    }

    /**
     * Get neighbors of [Node] in this [Way]
     *
     * @see areNeighbors
     */
    fun getNeighborsOf(node: Node): Array<Node> {
        assert(nodesArray.contains(node))

        val nodeIndex = nodesArray.indexOf(node)

        val prev = if (nodeIndex == 0) {
            nodesArray[nodesArray.size - 1]
        } else {
            nodesArray[nodeIndex - 1]
        }

        val next = if (nodeIndex == nodesArray.size - 1) {
            nodesArray[0]
        } else {
            nodesArray[nodeIndex + 1]
        }

        return if (areNeighbors(nodeIndex, prev)) {
            if (next == prev) {
                arrayOf(prev)
            } else {
                if (areNeighbors(nodeIndex, next)) {
                    arrayOf(prev, next)
                } else {
                    arrayOf(prev)
                }
            }
        } else if (areNeighbors(nodeIndex, next)) {
            arrayOf(next)
        } else {
            emptyArray()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }

        if (other == null || other !is Way) {
            return false
        }

        return id == other.id
    }

    override fun hashCode(): Int {
        return id.toInt()
    }

    override fun toString(): String {
        return "Way(id=$id, isOpen=$isOpen, highway=$highway, name=$name, bicycle=$bicycle " +
                "surface=$surface service=$service nodes=${nodes.map { it.id }})"
    }
}

/**
 * Move along the [Node]s and call [block] every [step] absolute 0-zoom-map pixels.
 */
fun Array<Node>.interpolateAlong(
    zoom: Float,
    stepAbsolute: Double,
    block: (loc: MapView.AbsoluteCoordinates, bearing: Double) -> Unit
) {
    val step = stepAbsolute * 2.0.pow(zoom.toDouble())
    var distance = 0.0

    forEachIndexed { i, _ ->
        if (i != 0) {
            val startAbsolute = this[i - 1].toAbsolute(zoom)
            val endAbsolute = this[i].toAbsolute(zoom)
            val xDist = endAbsolute.x - startAbsolute.x
            val yDist = endAbsolute.y - startAbsolute.y
            val distanceAfterSegment = distance + sqrt(xDist * xDist + yDist * yDist)

            var bearing: Double? = null

            var distanceStepped = max(step, (ceil(distance / step) * step))
            while (distanceStepped <= distanceAfterSegment) {
                if (bearing == null) {
                    bearing = atan2(yDist, xDist) * 180 / PI + 90
                }

                val progress = (distanceStepped - distance) / (distanceAfterSegment - distance)
                block(
                    MapView.AbsoluteCoordinates(
                        startAbsolute.x + xDist * progress,
                        startAbsolute.y + yDist * progress,
                    ), bearing
                )
                distanceStepped += step
            }

            distance = distanceAfterSegment
        }
    }
}