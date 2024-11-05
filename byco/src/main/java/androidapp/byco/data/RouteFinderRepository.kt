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

import android.os.SystemClock
import android.util.Log
import androidapp.byco.BycoApplication
import androidapp.byco.data.OsmDataProvider.BicycleType.DESIGNATED
import androidapp.byco.data.OsmDataProvider.BicycleType.DISMOUNT
import androidapp.byco.data.OsmDataProvider.HighwayType.BRIDALWAY
import androidapp.byco.data.OsmDataProvider.HighwayType.CYCLEWAY
import androidapp.byco.data.OsmDataProvider.HighwayType.FOOTWAY
import androidapp.byco.data.OsmDataProvider.HighwayType.GENERATED
import androidapp.byco.data.OsmDataProvider.HighwayType.LIVING_STEET
import androidapp.byco.data.OsmDataProvider.HighwayType.MOTORWAY
import androidapp.byco.data.OsmDataProvider.HighwayType.MOTORWAY_LINK
import androidapp.byco.data.OsmDataProvider.HighwayType.PATH
import androidapp.byco.data.OsmDataProvider.HighwayType.WATERWAY
import androidapp.byco.data.OsmDataProvider.HighwayType.PEDESTRIAN
import androidapp.byco.data.OsmDataProvider.HighwayType.PRIMARY
import androidapp.byco.data.OsmDataProvider.HighwayType.PRIMARY_LINK
import androidapp.byco.data.OsmDataProvider.HighwayType.RESIDENTIAL
import androidapp.byco.data.OsmDataProvider.HighwayType.ROAD
import androidapp.byco.data.OsmDataProvider.HighwayType.SECONDARY
import androidapp.byco.data.OsmDataProvider.HighwayType.SECONDARY_LINK
import androidapp.byco.data.OsmDataProvider.HighwayType.SERVICE
import androidapp.byco.data.OsmDataProvider.HighwayType.TERTIARY
import androidapp.byco.data.OsmDataProvider.HighwayType.TERTIARY_LINK
import androidapp.byco.data.OsmDataProvider.HighwayType.TRACK
import androidapp.byco.data.OsmDataProvider.HighwayType.TRUNK
import androidapp.byco.data.OsmDataProvider.HighwayType.TRUNK_LINK
import androidapp.byco.data.OsmDataProvider.HighwayType.UNCLASSIFIED
import androidapp.byco.data.OsmDataProvider.ServiceType.ALLEY
import androidapp.byco.util.CountryCode
import androidapp.byco.util.Repository
import androidapp.byco.util.SingleParameterSingletonOf
import androidapp.byco.util.areBicyclesAllowedByDefault
import androidapp.byco.util.plus
import androidapp.byco.util.stateIn
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import lib.gpx.BasicLocation
import lib.gpx.DebugLog
import lib.gpx.MapArea
import lib.gpx.toBasicLocation
import java.math.BigDecimal
import java.math.RoundingMode.CEILING
import java.math.RoundingMode.FLOOR
import java.math.RoundingMode.HALF_UP
import java.util.PriorityQueue
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.math.pow
import kotlin.math.sqrt

/** @see MAP_TILE_SIZE */
private val MAP_TILE_MULT = 10.0.pow(MapDataRepository.TILE_SCALE)

/**
 * The searching is one tile at a time. Once all nodes in a tile have been checked, the next tile
 * is searched. Hence the bigger the tile is, the more unnecessary data is loaded, but the less
 * tile-switching needs to be done.
 *
 * I.e. the bigger this is, the less unnecessary re-loading is done, but the more sub-par nodes are
 * searched.
 */
private val MAP_TILE_SIZE = 1 / MAP_TILE_MULT

/**
 * Size of the area searched for when finding ways from the current user location to the closes way
 * on the map.
 */
private val GENERATED_WAYS_MAP_TILE_SIZE = 1 / MAP_TILE_MULT

// Representation of a tile without the overhead of BigDecimals
private typealias TileKey = Long

private fun TileKey.toLat(): Double {
    return ((this shr 32) - 180 * MAP_TILE_MULT) / MAP_TILE_MULT
}

private fun TileKey.toLon(): Double {
    return ((this and 0xFFFFFFFF) - 180 * MAP_TILE_MULT) / MAP_TILE_MULT
}

/** Get key of map tile the location is in */
private fun BasicLocation.toTileKey(): TileKey {
    return (((latitude + 180) * MAP_TILE_MULT).toLong() shl 32) +
            // use .toInt to mask to 32 bit
            ((longitude + 180) * MAP_TILE_MULT).toInt()
}

/**
 * [findRoute], e.g. from [rideStart].
 */
class RouteFinderRepository internal constructor(
    private val app: BycoApplication,
    private val MAX_OFF_PATH: Float = 200f, // m
) : Repository(app) {
    private val TAG = RouteFinderRepository::class.java.simpleName

    /**
     * Location of start of ride, preferrably [RideRecordingRepository.rideStart] but can also be
     * [RideEstimator.rideStart] is no ride is being recorded.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val rideStart =
        (RideEstimator.instance[app].flatMapLatest {
            it?.rideStart ?: flow { emit(null) }
        } + RideRecordingRepository[app].rideStart).map { (estimatedRideStart, recordedRideStart) ->
            recordedRideStart ?: estimatedRideStart
        }.stateIn(null)

    /** A simplified [Node] without ways */
    private class PreviousNode(val id: Long, latitude: Double, longitude: Double) :
        BasicLocation(latitude, longitude) {
        override fun equals(other: Any?): Boolean {
            if (other === this) {
                return true
            }

            if (other !is PreviousNode) {
                return false
            }

            return id == other.id
        }

        override fun hashCode(): Int {
            return id.toInt()
        }
    }

    /** Convert a [Node] into the simpler [PreviousNode] object */
    private fun Node.toPreviousNode(): PreviousNode {
        return PreviousNode(id, latitude, longitude)
    }

    /**
     * LiveData for the best route between [startLoc] and [goalLoc].
     *
     * @returns empty [List] if there is no route
     */
    fun findRoute(
        startLoc: BasicLocation,
        goalLoc: BasicLocation,
        providedCountryCode: CountryCode = null,
        postIntermediateUpdates: Boolean = true
    ) = flow {
        /** Round double to a [BigDecimal] so it is the edge of a map tile */
        fun Double.roundToBigDecimalMapTileScale(): BigDecimal {
            return toBigDecimal().setScale(MapDataRepository.TILE_SCALE, HALF_UP)
        }

        /** Round double to a [BigDecimal] so it is the lower edge of a map tile */
        fun Double.floorToBigDecimalMapTileScale(): BigDecimal {
            return toBigDecimal().setScale(MapDataRepository.TILE_SCALE, FLOOR)
        }

        /** Round double to a [BigDecimal] so it is the upper edge of a map tile */
        fun Double.ceilToBigDecimalMapTileScale(): BigDecimal {
            return toBigDecimal().setScale(MapDataRepository.TILE_SCALE, CEILING)
        }

        data class WaySegment(
            val start: Node,
            val end: Node,
            val way: Way
        )

        /** Get all `WaySegment`s that are connected a bikeable [Way] in this [MapData]. */
        fun MapData.asSegments(): List<WaySegment> {
            return filter { it.bicycle == null || it.bicycle.isAllowed }.flatMap { way ->
                way.nodesArray.indices.mapNotNull { i ->
                    if (i == 0) {
                        null
                    } else {
                        WaySegment(way.nodesArray[i - 1], way.nodesArray[i], way)
                    }
                }
            }
        }

        fun MapData.getClosestPointsOnSegments(loc: BasicLocation):
                List<Pair<WaySegment, Node>> {
            val node = loc.toNode()

            return asSegments().map { segment ->
                segment to node.closestNodeOn(segment.start, segment.end).toNode()
            }
        }

        /**
         * Don't compute real spherical distance. Just assume flat map. This is good enough for
         * any bikeable distance.
         */
        fun Node.mapDistanceTo(neighbor: Node): Double {
            val latDiff = latitude - neighbor.latitude
            val lonDiff = longitude - neighbor.longitude
            return sqrt(latDiff * latDiff + lonDiff * lonDiff)
        }

        /** Get travel effort from a [Node] to its direct [neighbor] on [way] */
        fun Node.to(neighbor: Node, way: Way): Double {
            // use country code of start as otherwise the resolving will really slow us down
            val wayFactor =
                if (!(way.bicycle?.isAllowed ?: (way.highway?.areBicyclesAllowedByDefault(
                        providedCountryCode
                    ) == true))
                ) {
                    return Double.POSITIVE_INFINITY
                } else if (way.bicycle == DESIGNATED) {
                    1.0
                } else if (way.bicycle == DISMOUNT) {
                    5.0
                } else if (way.surface?.isCycleable == false) {
                    // Assume cycleable unless specified otherwise
                    return Double.POSITIVE_INFINITY
                } else {
                    when (way.highway) {
                        // Discourage generated ways to force route onto real roads asap
                        GENERATED -> 100.0
                        CYCLEWAY ->
                            // Assume paved and cyclable unless specified otherwise
                            when {
                                way.surface?.isPaved != false -> 1.0
                                way.surface.isCycleable -> 3.0
                                else -> return Double.POSITIVE_INFINITY
                            }

                        MOTORWAY, TRUNK, PRIMARY, SECONDARY, LIVING_STEET,
                        MOTORWAY_LINK, TRUNK_LINK, PRIMARY_LINK, SECONDARY_LINK -> 1.6

                        TERTIARY, UNCLASSIFIED, RESIDENTIAL, TERTIARY_LINK, ROAD -> 1.2
                        PATH, PEDESTRIAN, TRACK, BRIDALWAY, FOOTWAY ->
                            when {
                                // Assume unpaved and uncyclable unless specified otherwise
                                way.surface?.isPaved == true -> 1.1
                                way.surface?.isCycleable == true -> 3.0
                                else -> return Double.POSITIVE_INFINITY
                            }

                        SERVICE ->
                            when (way.service?.shouldBeShown) {
                                (way.service == ALLEY) -> 1.6
                                // All other types other than alley are not good paths
                                (way.service != null) -> return Double.POSITIVE_INFINITY
                                // Unnamed service ways are often driveways and the like
                                (way.name == null) -> return Double.POSITIVE_INFINITY
                                // Named services way without service type ar often basically
                                // smaller streets. But as they are obviously not normal, rank
                                // them quite low
                                else -> 2.0
                            }

                        WATERWAY -> return Double.POSITIVE_INFINITY

                        else -> throw IllegalStateException("Unknown way type $way.highway")
                    }
                }

            val distance = mapDistanceTo(neighbor)

            return wayFactor * distance
        }

        /** Estimate the travel effort from a [Node] to an [other] node */
        fun Node.estTo(other: Node): Double {
            // By over-estimating the distance we can save time investigating very unlikely routes
            // as almost always there is some kind in the route between two points. Setting a factor
            // of 1.55 seems to save about 25% of runtime.
            //
            // But for now optimize for quality.
            return mapDistanceTo(other)
        }

        val startTime = SystemClock.elapsedRealtime()
        // Find nodes close to start
        val start = startLoc.toNode()
        val startA = coroutineScope {
            async {
                MapDataRepository[app].getMapData(
                    MapArea(
                        (startLoc.latitude - GENERATED_WAYS_MAP_TILE_SIZE / 2).floorToBigDecimalMapTileScale(),
                        (startLoc.longitude - GENERATED_WAYS_MAP_TILE_SIZE / 2).floorToBigDecimalMapTileScale(),
                        (startLoc.latitude + GENERATED_WAYS_MAP_TILE_SIZE / 2).ceilToBigDecimalMapTileScale(),
                        (startLoc.longitude + GENERATED_WAYS_MAP_TILE_SIZE / 2).ceilToBigDecimalMapTileScale()
                    )
                ).first().getClosestPointsOnSegments(startLoc)
                    .filter { (_, closesNode) ->
                        closesNode.distanceTo(start) < MAX_OFF_PATH
                    }
            }
        }

        // Find nodes close to goal
        val goal = goalLoc.toNode()
        val goalA = coroutineScope {
            async {
                MapDataRepository[app].getMapData(
                    MapArea(
                        (goalLoc.latitude - GENERATED_WAYS_MAP_TILE_SIZE / 2).floorToBigDecimalMapTileScale(),
                        (goalLoc.longitude - GENERATED_WAYS_MAP_TILE_SIZE / 2).floorToBigDecimalMapTileScale(),
                        (goalLoc.latitude + GENERATED_WAYS_MAP_TILE_SIZE / 2).ceilToBigDecimalMapTileScale(),
                        (goalLoc.longitude + GENERATED_WAYS_MAP_TILE_SIZE / 2).ceilToBigDecimalMapTileScale()
                    )
                ).first().getClosestPointsOnSegments(goalLoc)
                    .filter { (_, closesNode) ->
                        closesNode.distanceTo(goal) < MAX_OFF_PATH
                    }
            }
        }

        val startSegments = startA.await()
        val goalSegments = goalA.await()

        if (startSegments.isEmpty() || goalSegments.isEmpty()) {
            // Cannot find start or end node, hence cannot find route
            emit(emptyList())
            return@flow
        }

        fun generateWaysFromSegmentsToNode(
            segments: List<Pair<WaySegment, Node>>,
            node: Node,
            backwards: Boolean = false
        ): List<Way> {
            return segments.flatMap { (segment, closestNode) ->
                val segmentStart = segment.start
                val segmentEnd = segment.end

                fun getNodeArray(node1: Node, node2: Node): Array<Node> =
                    if (backwards) {
                        arrayOf(node2, node1)
                    } else {
                        arrayOf(node1, node2)
                    }

                when (closestNode) {
                    segmentStart -> {
                        listOf(
                            Way(
                                highway = GENERATED,
                                nodesArray = getNodeArray(segmentStart, node),
                                isOneway = true
                            )
                        )
                    }

                    segmentEnd -> {
                        listOf(
                            Way(
                                highway = GENERATED,
                                nodesArray = getNodeArray(segmentEnd, node),
                                isOneway = true
                            )
                        )
                    }

                    else -> {
                        val ways = mutableListOf(
                            Way(
                                highway = GENERATED,
                                nodesArray = getNodeArray(closestNode, node),
                                isOneway = true
                            )
                        )

                        val startToClosest = Way(
                            highway = segment.way.highway,
                            bicycle = segment.way.bicycle,
                            service = segment.way.service,
                            surface = segment.way.surface,
                            name = segment.way.name,
                            nodesArray = getNodeArray(segmentStart, closestNode),
                            isOneway = true
                        )

                        val endToClosest = Way(
                            highway = segment.way.highway,
                            bicycle = segment.way.bicycle,
                            service = segment.way.service,
                            surface = segment.way.surface,
                            name = segment.way.name,
                            nodesArray = getNodeArray(segmentEnd, closestNode),
                            isOneway = true
                        )

                        if (segment.way.isOneway) {
                            ways += if (backwards) {
                                endToClosest
                            } else {
                                startToClosest
                            }
                        } else {
                            ways += listOf(startToClosest, endToClosest)
                        }

                        ways
                    }
                }.onEach { way ->
                    way.nodesArray.forEach { node ->
                        node.mutableWays.add(way)
                    }
                }
            }
        }

        /** Generate ways from start to nearby real OSM nodes */
        val startWays = generateWaysFromSegmentsToNode(startSegments, start, backwards = true)

        /** Generate ways from start to nearby real OSM nodes */
        val goalWays = generateWaysFromSegmentsToNode(goalSegments, goal)

        // Best guess of the travel effort from start to node to goal
        val estStartToNodeToGoal = HashMap<Long, Double>()
        estStartToNodeToGoal[start.id] = start.estTo(goal)
        val hasLessEstStartToNodeToGoal =
            Comparator<Node> { a, b ->
                if (a == b) {
                    0
                } else {
                    val estA = estStartToNodeToGoal[a!!.id]!!
                    val estB = estStartToNodeToGoal[b!!.id]!!

                    when {
                        estA < estB -> -1
                        estB < estA -> 1
                        else -> 0
                    }
                }
            }

        // Set of nodes that might still be on a shorter path from start to goal
        val nodesToCheck =
            mutableMapOf(start.toTileKey() to
                    PriorityQueue(1, hasLessEstStartToNodeToGoal).apply {
                        add(start)
                    })

        // Previous node on the route from start to the node
        val bestPrevNode = HashMap<Long, PreviousNode>()

        /** Get path from start to node (based on `bestPrevNode`) */
        fun Node.getRouteFromStart(): List<BasicLocation> {
            // Found the best route
            var nodeId = id
            val routeBack = mutableListOf(BasicLocation(this.latitude, this.longitude))
            while (bestPrevNode.containsKey(nodeId)) {
                val prev = bestPrevNode[nodeId]!!
                nodeId = prev.id
                routeBack += prev
            }

            return routeBack.asReversed()
        }

        // Travel effort of best known route from start to node (get route via
        // [getRouteFromStart])
        val bestStartToNode = HashMap<Long, Double>()
        bestStartToNode[start.id] = 0.0

        // Time of last intermediate update of route
        var lastIntermediateUpdate = SystemClock.elapsedRealtime()
        // How far is the best known route away from the goal
        var bestRouteFromGoal = start.mapDistanceTo(goal)

        // Tile the [current] node is in
        var currentTile = -1L

        var checkedNodes = 0
        var checkedTiles = 0
        while (true) {
            // Find the node which represents the best estimation for the closest
            // path to goal.
            //
            // Always re-compute all estimations from the current tile first.
            // Loading new data is quite a bit more expensive than searching a
            // little more.
            val current = nodesToCheck[currentTile]?.firstOrNull()
                ?: nodesToCheck.values.mapNotNull { it.firstOrNull() }
                    .minByOrNull { estStartToNodeToGoal[it.id]!! } ?: break
            checkedNodes++

            // Occasionally post intermediate updates (to show search progress to user)
            if (postIntermediateUpdates
                && current.mapDistanceTo(goal) < bestRouteFromGoal
                && SystemClock.elapsedRealtime() - lastIntermediateUpdate
                > SECONDS.toMillis(1)
            ) {
                bestRouteFromGoal = current.mapDistanceTo(goal)
                lastIntermediateUpdate = SystemClock.elapsedRealtime()

                emit(current.getRouteFromStart())
            }

            fun MutableSet<Way>.keepOnlyGenerated() {
                if (isEmpty()) {
                    return
                }

                val generated = filter { !it.isRealWay() }
                clear()
                addAll(generated)
            }

            // Maybe change tile we are looking at, i.e.
            // - reload which nodes have connections to other nodes.
            // - update [currentTileElevation]
            val newTile = current.toTileKey()
            if (newTile != currentTile) {
                checkedTiles++
                // Remove ways from all nodes to avoid having all nodes connected which
                // would have made them non gc-able and will run us out of memory.
                // Keep generated ways as they are not too large.
                current.mutableWays.keepOnlyGenerated()
                start.mutableWays.keepOnlyGenerated()
                startWays.forEach { way ->
                    way.nodesArray.forEach { node ->
                        node.mutableWays.keepOnlyGenerated()
                    }
                }
                goal.mutableWays.keepOnlyGenerated()
                goalWays.forEach { way ->
                    way.nodesArray.forEach { node ->
                        node.mutableWays.keepOnlyGenerated()
                    }
                }
                nodesToCheck.forEach { (_, nodes) ->
                    nodes.forEach {
                        it.mutableWays.keepOnlyGenerated()
                    }
                }

                currentTile = newTile

                // Add ways back for nodes in current tile. We don't care about nodes in
                // other tiles as we are only searching in current tile until we exhausted
                // all nodes in the current tile.
                val latitude = currentTile.toLat()
                val longitude = currentTile.toLon()
                val mapTileArea = MapArea(
                    latitude.roundToBigDecimalMapTileScale(),
                    longitude.roundToBigDecimalMapTileScale(),
                    (latitude + MAP_TILE_SIZE).roundToBigDecimalMapTileScale(),
                    (longitude + MAP_TILE_SIZE).roundToBigDecimalMapTileScale()
                )
                val mapDataUpdate = coroutineScope {
                    async {
                        MapDataRepository[app].getMapData(
                            mapTileArea,
                            // As we reuse nodes, these reused nodes will gain
                            // connections to new nodes in this tile
                            reuseNodes = nodesToCheck[currentTile]!! +
                                    (startWays.flatMap { it.nodes } +
                                            goalWays.flatMap { it.nodes } +
                                            goal).filter {
                                        it.toTileKey() == currentTile
                                    }
                        ).first()
                    }
                }

                // Wait for data to update
                mapDataUpdate.await()
            }

            // Check if we found a route
            if (current == goal) {
                // current might just be the best in the current tile. Hence search if there
                // is a better node in a different tile
                val startToCurrentToGoal = estStartToNodeToGoal[current.id]!!
                val betterNode = nodesToCheck.values.mapNotNull { it.firstOrNull() }
                    .find {
                        it != current && estStartToNodeToGoal[it.id]!! < startToCurrentToGoal
                    }

                if (betterNode != null) {
                    // Try the maybe better tile
                    currentTile = betterNode.toTileKey()
                    continue
                } else {
                    if (DebugLog.isEnabled) {
                        DebugLog.i(
                            TAG,
                            "Searched from ${
                                start.toLocation().toBasicLocation()
                            } -> ${
                                goal.toLocation().toBasicLocation()
                            } in ${(SystemClock.elapsedRealtime() - startTime) / 1000.0}s, checked $checkedNodes nodes and loaded $checkedTiles tiles. Dataset ${estStartToNodeToGoal.size} nodes."
                        )
                    } else {
                        Log.i(
                            TAG,
                            "Searched in ${(SystemClock.elapsedRealtime() - startTime) / 1000.0}s while checking $checkedNodes nodes and loaded $checkedTiles tiles. Dataset ${estStartToNodeToGoal.size} nodes."
                        )
                    }
                    // Found the best route
                    emit(goal.getRouteFromStart())
                    return@flow
                }
            }

            // Check if any neighbor of current node is faster to reach though this node.
            // (This is a vanilla A* algorithm.)
            nodesToCheck[currentTile]!!.remove(current)
            current.getNeighbors().forEach { (neighbor, way) ->
                val startToCurrentToNeighbor =
                    bestStartToNode[current.id]!! + current.to(neighbor, way)

                if (startToCurrentToNeighbor <
                    (bestStartToNode[neighbor.id] ?: Double.POSITIVE_INFINITY)
                ) {
                    bestPrevNode[neighbor.id] = current.toPreviousNode()

                    bestStartToNode[neighbor.id] = startToCurrentToNeighbor
                    estStartToNodeToGoal[neighbor.id] =
                        startToCurrentToNeighbor + neighbor.estTo(goal)

                    // [neighbor] might be in different tile as [getMapData] returns all
                    // segments in the tile, including the ones that cross over the border.
                    var nodesToCheckThisTile = nodesToCheck[neighbor.toTileKey()]
                    if (nodesToCheckThisTile == null) {
                        nodesToCheckThisTile =
                            PriorityQueue(nodesToCheck.size, hasLessEstStartToNodeToGoal)
                        nodesToCheck[neighbor.toTileKey()] = nodesToCheckThisTile
                    }
                    nodesToCheckThisTile.add(neighbor)
                }
            }
        }

        // Cannot find route
        emit(emptyList())
    }.flowOn(Default)

    companion object :
        SingleParameterSingletonOf<BycoApplication, RouteFinderRepository>({
            RouteFinderRepository(
                it
            )
        })
}