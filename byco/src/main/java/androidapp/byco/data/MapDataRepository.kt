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
import androidapp.byco.LowPriority
import androidapp.byco.data.MapDataRepository.Companion.TILE_SCALE
import androidapp.byco.ui.views.DISPLAYED_HIGHWAY_TYPES
import androidapp.byco.util.DiskCache
import androidapp.byco.util.DiskCacheKey
import androidapp.byco.util.Repository
import androidapp.byco.util.SelfCleaningCache
import androidapp.byco.util.SingleParameterSingletonOf
import androidapp.byco.util.TWO
import androidapp.byco.util.forBigDecimal
import androidapp.byco.util.mapBigDecimal
import androidapp.byco.util.newEntry
import androidapp.byco.util.readBigDecimal
import androidapp.byco.util.writeBigDecimal
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import lib.gpx.BasicLocation
import lib.gpx.DebugLog
import lib.gpx.MapArea
import lib.gpx.PreciseLocation
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.BigDecimal.ONE
import java.math.BigDecimal.TEN
import java.math.BigDecimal.ZERO
import java.math.RoundingMode.HALF_UP
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

private typealias MapTile = SharedFlow<Pair<List<ParsedNode>, List<ParsedWay>>?>

class MapTileKey(lat: BigDecimal, lon: BigDecimal) : PreciseLocation(lat, lon), DiskCacheKey {
    override val scale = TILE_SCALE

    init {
        assert(lat.scale() == scale)
        assert(lon.scale() == scale)
    }

    override fun toDirName() = "${lat.setScale(scale, HALF_UP)}_${lon.setScale(scale, HALF_UP)}"
}

typealias MapData = Set<Way>

/** Source for map data */
@OptIn(ExperimentalCoroutinesApi::class)
class MapDataRepository private constructor(
    private val app: BycoApplication,
    CACHE_LOCATION: File = File(app.cacheDir, "MapData"),
    MAX_AGE: Long = TimeUnit.DAYS.toMillis(90),
    NUM_CACHED_TILES: Int = 64,
    private val CURRENT_DATA_FORMAT_VERSION: Int = 2
) : Repository(app) {
    private val TAG = MapDataRepository::class.java.simpleName

    @VisibleForTesting
    constructor(app: BycoApplication, numCachedTiles: Int) : this(
        app,
        NUM_CACHED_TILES = numCachedTiles
    )

    private val TILE_WIDTH = ONE.divide(TEN.pow(TILE_SCALE))

    /** Cache map data on disk so that it does not have to be loaded from network */
    private val mapDataCache = DiskCache(
        CACHE_LOCATION,
        MAX_AGE,
        8,
        { (minLat, minLon): MapTileKey ->
            withContext(Default) {
                val (nodes, ways) = try {
                    OsmDataProvider.getParsedOsmData(
                        minLat,
                        minLon,
                        minLat + TILE_WIDTH,
                        minLon + TILE_WIDTH
                    )
                } catch (e: IOException) {
                    throw Exception("Cannot parse OSM data", e)
                }

                ensureActive()

                val displayedNodeIds = mutableSetOf<Long>()
                // Don't care about ways which are not displayed, hence filter out
                // early
                val displayedWays = ways.filter { way ->
                    way.highway in DISPLAYED_HIGHWAY_TYPES
                }

                displayedWays.forEach { way ->
                    way.nodeRefs.forEach {
                        displayedNodeIds += it
                    }
                }

                val displayedNodes = nodes.filter { node ->
                    displayedNodeIds.contains(node.id)
                }

                displayedNodes to displayedWays
            }
        },
        { out, key, data ->
            withContext(IO) {
                ZipOutputStream(out).use { zipOs ->
                    zipOs.newEntry("mapData") {
                        val dataOs = DataOutputStream(zipOs.buffered())

                        dataOs.writeInt(CURRENT_DATA_FORMAT_VERSION)
                        dataOs.writeBigDecimal(key.lat)
                        dataOs.writeBigDecimal(key.lon)

                        val nodes = data.first
                        dataOs.writeInt(nodes.size)
                        nodes.forEach { node ->
                            dataOs.writeParsedNode(node)
                        }

                        val ways = data.second
                        dataOs.writeInt(ways.size)
                        ways.forEach { way ->
                            dataOs.writeParsedWay(way)
                        }

                        dataOs.flush()
                    }
                }
            }
        },
        { ins ->
            withContext(IO) {
                try {
                    ZipInputStream(ins).use { zipIs ->
                        zipIs.nextEntry

                        DataInputStream(zipIs.buffered()).use { dataIs ->
                            when (val version = dataIs.readInt()) {
                                1, 2 -> {
                                    val lat = dataIs.readBigDecimal()
                                    val lon = dataIs.readBigDecimal()

                                    val nodes = (0 until dataIs.readInt()).map {
                                        dataIs.readParsedNode()
                                    }

                                    ensureActive()

                                    val ways = (0 until dataIs.readInt()).map {
                                        dataIs.readParsedWay(version)
                                    }

                                    MapTileKey(lat, lon) to (nodes to ways)
                                }

                                else -> throw DiskCache.InvalidCachedDataFileException("unsupported data file version $version")
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: java.lang.Exception) {
                    throw DiskCache.InvalidCachedDataFileException(e)
                }
            }
        })

    /**
     * Cache parsed data in memory as it is often reused when moving the center of a map a little
     * bit
     */
    private val mapDataTilesMutex = Mutex()
    private val mapDataTiles = SelfCleaningCache<MapTileKey, MapTile>(NUM_CACHED_TILES)

    private val prefetchLimiter = Semaphore(mapDataCache.numParallelLockedKeys * 2)

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    suspend fun getMapDataTile(tileKey: MapTileKey): MapTile {
        var v = mapDataTilesMutex.withLock {
            mapDataTiles[tileKey]
        }

        if (v == null) {
            v = flow {
                try {
                    emit(mapDataCache.get(tileKey))
                } catch (e: Exception) {
                    if (coroutineContext.isActive) {
                        Log.e(TAG, "Could not get tile", e)
                    }
                }
            }.shareIn(repositoryScope, SharingStarted.Lazily, 1)

            mapDataTilesMutex.withLock {
                mapDataTiles[tileKey] = v
            }
        }

        return v
    }

    /** `Flow` whether the map data of a certain file is very likely prefetched */
    fun isPrefetched(tile: MapTileKey) = mapDataCache.hasData(tile)

    /**
     * Prefetch a single map tile
     *
     * @see DiskCache.preFetch
     */
    suspend fun preFetchMapDataTile(tileKey: MapTileKey, skipIfLocked: Boolean = false): Boolean {
        return prefetchLimiter.withPermit {
            try {
                mapDataCache.preFetch(tileKey, skipIfLocked)

                return@withPermit true
            } catch (e: DiskCache.SkippedBecauseLockedException) {
                // pass

                return@withPermit false
            } catch (e: Exception) {
                DebugLog.e(TAG, "Could not prefetch map around $tileKey", e)

                return@withPermit false
            }
        }
    }

    /**
     * Get square map data around a [center].
     */
    fun getMapTilesAround(
        center: BasicLocation,
        maxDistance: Float,  // m
    ): List<MapTileKey> {
        val centerLat = BigDecimal(center.latitude).setScale(TILE_SCALE, HALF_UP)
        val centerLon = BigDecimal(center.longitude).setScale(TILE_SCALE, HALF_UP)

        val mapTiles = mutableListOf<MapTileKey>()
        var distanceDeg = ZERO
        while (center.distanceTo(
                BasicLocation(
                    center.latitude + distanceDeg.toDouble(),
                    center.longitude
                )
            )
            < maxDistance
        ) {
            forBigDecimal(centerLat - distanceDeg, centerLat + distanceDeg, TILE_WIDTH) { lat ->
                forBigDecimal(centerLon - distanceDeg, centerLon + distanceDeg, TILE_WIDTH) { lon ->
                    mapTiles.add(MapTileKey(lat, lon))
                }
            }

            distanceDeg += TILE_WIDTH * TWO
        }

        return mapTiles
    }

    /**
     * Get map data for a certain area
     *
     * Note: Performance sensitive as this is the top level entry point for all map data retrieval.
     */
    fun getMapData(
        mapArea: MapArea,
        returnPartialData: Boolean = false,
        loadStreetNames: Boolean = false,
        reuseNodes: Collection<Node> = emptyList(),
        lowPriority: Boolean = false
    ) = channelFlow {
        val processedNodes = reuseNodes.associateByTo(mutableMapOf()) { it.id }
        val processedWays = mutableSetOf<Way>()

        var partialDataReturn: Job? = null
        var nextPartialDataReturn = 0L

        mapBigDecimal(mapArea.minLat, mapArea.maxLat, TILE_WIDTH) { lat ->
            mapBigDecimal(mapArea.minLon, mapArea.maxLon, TILE_WIDTH) { lon ->
                MapTileKey(lat, lon)
            }
        }.flatten().asFlow().flatMapMerge(mapDataCache.numParallelLockedKeys) { key ->
            getMapDataTile(key).filterNotNull().take(1)
        }.collect { tile ->
            val (parsedNodes, parsedWays) = tile

            // Add the parsedWays from the tile to the processedWays.
            val parsedNodeMap =
                parsedNodes.associate { node -> node.id to node.toNode() }

            parsedWays.forEach { parsedWay ->
                // Get actual [Node]s the way goes through
                val wayNodes = ArrayList<Node>(parsedWay.nodeRefs.size)
                parsedWay.nodeRefs.forEach { nodeId ->
                    // Reuse already processed node
                    val processedNode = processedNodes[nodeId]
                    if (processedNode != null) {
                        wayNodes += processedNode
                    } else {
                        // Use newly parsed node
                        //
                        // If there is no parsed node the way extends over the edge of the map,
                        // hence just drop the node
                        parsedNodeMap[nodeId]?.let { parsedNode ->
                            parsedNode.also { node ->
                                processedNodes[nodeId] = node
                            }
                        }?.also {
                            wayNodes += it
                        }
                    }
                }

                processedWays += parsedWay.toWay(
                    wayNodes,
                    loadStreetNames
                ).also { way ->
                    // Link back from nodes -> way
                    wayNodes.forEach { node ->
                        // TODO: This modifies state that was previously
                        // returned and that is not deep copied
                        node.mutableWays.add(way)
                    }
                }
            }

            if (returnPartialData) {
                // processedWays might still be modified, hence deep copy it.
                val processedWaysCopy = processedWays.toSet()

                partialDataReturn?.cancelAndJoin()
                partialDataReturn = coroutineScope {
                    launch {
                        // Avoid triggering too often for intermediate values
                        delay(nextPartialDataReturn - SystemClock.elapsedRealtime())

                        send(processedWaysCopy)
                        nextPartialDataReturn = SystemClock.elapsedRealtime() + 100L
                    }
                }
            }
        }

        partialDataReturn?.cancelAndJoin()
        send(processedWays)
    }.flowOn(
        if (lowPriority) {
            LowPriority
        } else {
            Default
        }
    )

    companion object :
        SingleParameterSingletonOf<BycoApplication, MapDataRepository>({ MapDataRepository(it) }) {
        const val TILE_SCALE: Int = 2
    }
}
