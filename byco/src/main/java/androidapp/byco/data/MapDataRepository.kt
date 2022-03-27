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

import android.app.Application
import android.util.Log
import androidapp.byco.FixedLowPriorityThreads
import androidapp.byco.FixedThreads
import androidapp.byco.ui.views.DISPLAYED_HIGHWAY_TYPES
import androidapp.byco.util.*
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.LiveData
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.channels.consume
import kotlinx.coroutines.sync.Semaphore
import lib.gpx.BasicLocation
import lib.gpx.DebugLog
import lib.gpx.MapArea
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.BigDecimal.*
import java.math.RoundingMode
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private typealias MapTile = LiveData<Pair<List<ParsedNode>, List<ParsedWay>>>
private typealias MapTileKey = Pair<BigDecimal, BigDecimal>

typealias MapData = Set<Way>

/** Source for map data */
class MapDataRepository private constructor(
    private val app: Application,
    CACHE_LOCATION: File = File(app.cacheDir, "MapData"),
    MAX_AGE: Long = TimeUnit.DAYS.toMillis(90),
    NUM_CACHED_TILES: Int = 64,
    private val CURRENT_DATA_FORMAT_VERSION: Int = 2
) {
    private val TAG = MapDataRepository::class.java.simpleName

    private val TILE_WIDTH = ONE.divide(TEN.pow(TILE_SCALE))

    /** Cache map data on disk so that it does not have to be loaded from network */
    @Suppress("BlockingMethodInNonBlockingContext")
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
                        dataOs.writeBigDecimal(key.first)
                        dataOs.writeBigDecimal(key.second)

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
            @Suppress("BlockingMethodInNonBlockingContext")
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

                                    (lat to lon) to (nodes to ways)
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
    private val mapDataTiles = SelfCleaningCache<MapTileKey, MapTile>(NUM_CACHED_TILES)

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    fun getMapDataTile(tileKey: MapTileKey): MapTile {
        var v = synchronized(mapDataTiles) {
            mapDataTiles[tileKey]
        }

        if (v == null) {
            v = object : AsyncLiveData<Pair<List<ParsedNode>, List<ParsedWay>>>() {
                override suspend fun update(): Pair<List<ParsedNode>, List<ParsedWay>>? {
                    return if (value != null) {
                        // No need to reload data if the liveData goes active again
                        null
                    } else {
                        try {
                            mapDataCache.get(tileKey)
                        } catch (e: Exception) {
                            Log.e(TAG, "Could not get tile", e)
                            null
                        }
                    }
                }
            }

            synchronized(mapDataTiles) {
                mapDataTiles[tileKey] = v
            }
        }

        return v
    }

    /** [LiveData] whether the map data of a certain file is very likely prefetched */
    fun isPrefetched(tile: MapTileKey) = mapDataCache.hasData(tile)

    /**
     * Prefetch a single map tile
     *
     * @see DiskCache.preFetch
     */
    suspend fun preFetchMapDataTile(tileKey: MapTileKey, skipIfLocked: Boolean = false) {
        coroutineScope {
            try {
                mapDataCache.preFetch(tileKey, skipIfLocked)
            } catch (e: Exception) {
                DebugLog.e(TAG, "Could not prefetch $tileKey", e)
            }
        }
    }

    /**
     * Prefetch square map data around a [center] to be later retrieved via [getMapData]
     * without need of the network.
     *
     * @see DiskCache.preFetch
     */
    suspend fun preFetchMapData(
        center: BasicLocation,
        maxDistance: Float,  // m
    ) {
        val centerLat =
            BigDecimal(center.latitude).setScale(TILE_SCALE, RoundingMode.HALF_UP)
        val centerLon =
            BigDecimal(center.longitude).setScale(TILE_SCALE, RoundingMode.HALF_UP)

        var distanceDeg = ZERO
        while (center.distanceTo(
                BasicLocation(
                    center.latitude + distanceDeg.toDouble(),
                    center.longitude
                )
            )
            < maxDistance
        ) {
            DebugLog.v(TAG, "Prefetching $distanceDeg degrees map data around $center")

            val parallelLoads = Semaphore(mapDataCache.numParallelLockedKeys * 2)

            forBigDecimal(centerLat - distanceDeg, centerLat + distanceDeg, TILE_WIDTH) { lat ->
                forBigDecimal(centerLon - distanceDeg, centerLon + distanceDeg, TILE_WIDTH) { lon ->
                    parallelLoads.acquire()

                    coroutineScope {
                        launch(IO) {
                            try {
                                mapDataCache.preFetch(lat to lon)
                            } catch (e: Exception) {
                                DebugLog.w(TAG, "Failed to prefetch map for $lat/$lon")
                            }
                            parallelLoads.release()
                        }
                    }
                }
            }

            distanceDeg += TILE_WIDTH * TWO
        }
    }

    /**
     * Get map data for a certain area
     *
     * Note: Performance sensitive as this is the top level entry point for all map data retrieval.
     */
    fun getMapData(
        mapArea: MapArea,
        returnPartialData: Boolean = false,
        loadStreetNames: Boolean = true,
        reuseNodes: Collection<Node> = emptyList(),
        lowPriority: Boolean = false
    ) = object : CoroutineAwareLiveData<MapData>() {
        private val thread = if (lowPriority) {
            FixedLowPriorityThreads.random()
        } else {
            FixedThreads.random()
        }
        private var retriever: Job? = null
        private var delayedValuePoster: Job? = null

        // Combines all the values from the individual tiles ([mapDataTiles]) and return the
        // intermediate and final result
        private fun update() {
            retriever?.cancel()

            if (value != null) {
                // Value is only loaded once
                return
            }

            retriever = liveDataScope.async(thread) {
                try {
                    // Limit parallel loads to not clog up mapDataCache's locking
                    val parallelLoads = Semaphore(mapDataCache.numParallelLockedKeys * 2)

                    val unprocessedTiles = mutableSetOf<Pair<BigDecimal, BigDecimal>>()
                    var allScheduled = false

                    // These are collecting the data. All the manipulation is done in the
                    // [SingleThread]
                    val processedNodes = reuseNodes.map { it.id to it }.toMap(mutableMapOf())
                    val processedWays = mutableSetOf<Way>()

                    forBigDecimal(mapArea.minLat, mapArea.maxLat, TILE_WIDTH) { lat ->
                        forBigDecimal(mapArea.minLon, mapArea.maxLon, TILE_WIDTH) { lon ->
                            // Make a local copy or lat and lon for the new tile
                            val tileKey = lat to lon

                            parallelLoads.acquire()
                            unprocessedTiles.add(tileKey)
                            if (lat + TILE_WIDTH >= mapArea.maxLat
                                && lon + TILE_WIDTH >= mapArea.maxLon
                            ) {
                                allScheduled = true
                            }

                            launch {
                                getMapDataTile(tileKey).observeAsChannel().consume {
                                    val (parsedNodes, parsedWays) = receive()

                                    parallelLoads.release()

                                    val parsedNodeMap = withContext(Default) {
                                        parsedNodes.associate { node -> node.id to node.toNode() }
                                    }

                                    withContext(thread) {
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
                                                    // If there is no parsed node the way
                                                    // extends over the edge of the map, hence
                                                    // just drop the node
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

                                        delayedValuePoster?.cancel()

                                        unprocessedTiles.remove(tileKey)
                                        val isCompletelyLoaded =
                                            allScheduled && unprocessedTiles.isEmpty()

                                        if (isCompletelyLoaded) {
                                            postValue(processedWays)
                                        } else if (returnPartialData) {
                                            // Avoid triggering too often for intermediate values
                                            delayedValuePoster = launch(thread) {
                                                delay(100)
                                                if (isActive) {
                                                    postValue(processedWays.toSet())
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    withContext(NonCancellable + Main) {
                        retriever = null
                    }
                }
            }
        }

        init {
            assert(mapArea.minLat < mapArea.maxLat)
            assert(mapArea.minLon < mapArea.maxLon)
            assert(mapArea.minLat.setScale(TILE_SCALE, ROUND_HALF_UP) == mapArea.minLat)
            assert(mapArea.minLon.setScale(TILE_SCALE, ROUND_HALF_UP) == mapArea.minLon)
        }

        override fun onActive() {
            super.onActive()
            update()
        }

        override fun onInactive() {
            super.onInactive()
            retriever?.cancel()
        }
    } as LiveData<MapData>

    companion object :
        SingleParameterSingletonOf<Application, MapDataRepository>({ MapDataRepository(it) }) {
        const val TILE_SCALE: Int = 2
    }
}
