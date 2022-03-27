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
import androidapp.byco.ui.views.ElevationView
import androidapp.byco.util.*
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import lib.gpx.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.BigDecimal.*
import java.math.RoundingMode
import java.util.concurrent.TimeUnit.DAYS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.*

private typealias ElevationTile = LiveData<ParsedOpenTopographyData>
private typealias ElevationTileKey = Pair<BigDecimal, BigDecimal>

class ElevationDataRepository private constructor(
    private val app: Application,
    CACHE_LOCATION: File = File(app.cacheDir, "ElevationData"),
    MAX_AGE: Long = DAYS.toMillis(365 * 5),
    NUM_CACHED_TILES: Int = 9,
    private val MAX_SAT_VS_TRACK_OFFSET_DIFFERENCE: Float = 50F, // m
    private val CURRENT_DATA_FORMAT_VERSION: Int = 2
) {
    private val TAG = ElevationDataRepository::class.java.simpleName

    val TILE_WIDTH = ONE.divide(TEN.pow(TILE_SCALE))

    private val elevationDataCache = DiskCache(
        CACHE_LOCATION,
        MAX_AGE,
        1,
        { (minLat, minLon): ElevationTileKey ->
            withContext(Dispatchers.Default) {
                try {
                    OpenTopographyDataProvider[app].getParsedOpenTopographyData(
                        minLat,
                        minLon,
                        minLat + TILE_WIDTH,
                        minLon + TILE_WIDTH
                    )
                } catch (e: IOException) {
                    throw Exception("Cannot parse OpenTopography data", e)
                }
            }
        },
        { out, key, data ->
            @Suppress("BlockingMethodInNonBlockingContext")
            withContext(Dispatchers.IO) {
                ZipOutputStream(out).use { zipOs ->
                    zipOs.newEntry("mapData") {
                        val dataOs = DataOutputStream(zipOs.buffered())
                        dataOs.writeInt(CURRENT_DATA_FORMAT_VERSION)

                        dataOs.writeBigDecimal(key.first)
                        dataOs.writeBigDecimal(key.second)

                        dataOs.writeParsedOpenTopographyData(data)

                        dataOs.flush()
                    }
                }
            }
        },
        { ins ->
            @Suppress("BlockingMethodInNonBlockingContext")
            withContext(Dispatchers.IO) {
                try {
                    ZipInputStream(ins).use { zipIs ->
                        zipIs.nextEntry

                        DataInputStream(zipIs.buffered()).use { dataIs ->
                            when (val version = dataIs.readInt()) {
                                CURRENT_DATA_FORMAT_VERSION -> {
                                    val lat = dataIs.readBigDecimal()
                                    val lon = dataIs.readBigDecimal()

                                    (lat to lon) to dataIs.readParsedOpenTopographyData()
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
    private val elevationDataTiles =
        SelfCleaningCache<ElevationTileKey, ElevationTile>(NUM_CACHED_TILES)

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    fun getElevationDataTile(tileKey: ElevationTileKey): ElevationTile {
        var v = synchronized(elevationDataTiles) {
            elevationDataTiles[tileKey]
        }

        if (v == null) {
            v = object : AsyncLiveData<ParsedOpenTopographyData>() {
                override suspend fun update(): ParsedOpenTopographyData? {
                    return if (value != null) {
                        // No need to reload data if the liveData goes active again
                        null
                    } else {
                        return try {
                            elevationDataCache.get(tileKey)
                        } catch (e: Exception) {
                            Log.e(TAG, "Could not get tile", e)
                            null
                        }
                    }
                }
            }

            synchronized(elevationDataTiles) {
                elevationDataTiles[tileKey] = v
            }
        }

        return v
    }

    fun getElevationData(mapArea: MapArea) = object : AsyncLiveData<ElevationData>() {
        override suspend fun update(): ElevationData? {
            assert(mapArea.minLat.setScale(TILE_SCALE, ROUND_HALF_UP) == mapArea.minLat)
            assert(mapArea.minLon.setScale(TILE_SCALE, ROUND_HALF_UP) == mapArea.minLon)

            return mapBigDecimal(mapArea.minLat, mapArea.maxLat, TILE_WIDTH) { lat ->
                mapBigDecimal(mapArea.minLon, mapArea.maxLon, TILE_WIDTH) { lon ->
                    val data = getElevationDataTile(lat to lon)
                        .observeAsChannel().consumeAsFlow().first()

                    ElevationData(
                        MapArea(lat, lon, lat + TILE_WIDTH, lon + TILE_WIDTH),
                        data.minLat - lat.toDouble(),
                        data.minLon - lon.toDouble(),
                        data.cellSize,
                        if (data.noDataValue == ElevationData.NO_DATA) {
                            data.elevations
                        } else {
                            // Correct "no-data" value
                            data.elevations.map { line ->
                                line.map {
                                    if (it == data.noDataValue) {
                                        ElevationData.NO_DATA
                                    } else {
                                        it
                                    }
                                }.toShortArray()
                            }.toTypedArray()
                        },
                    )
                }.fold(null) { acc: ElevationData?, new ->
                    acc?.merge(new) ?: new
                }
            }.fold(null) { acc: ElevationData?, new ->
                new?.let { acc?.merge(it) ?: it }
            }
        }
    }

    /**
     * Prefetch square elevation data around a [center] to be later retrieved via [getElevationData]
     * without need of the network. Always prefetches at least one tile.
     *
     * @see DiskCache.preFetch
     */
    suspend fun preFetchElevationData(
        center: BasicLocation,
        maxDistance: Float,  // m
    ) {
        val centerLat =
            BigDecimal(center.latitude).setScale(TILE_SCALE, RoundingMode.HALF_UP)
        val centerLon =
            BigDecimal(center.longitude).setScale(TILE_SCALE, RoundingMode.HALF_UP)

        var distanceDeg = ZERO
        var tilesFetched = 0
        while (tilesFetched == 0 || center.distanceTo(
                BasicLocation(
                    center.latitude + distanceDeg.toDouble(),
                    center.longitude
                )
            )
            < maxDistance
        ) {
            DebugLog.v(TAG, "Prefetching $distanceDeg degrees elevation around $center")

            val parallelLoads = Semaphore(elevationDataCache.numParallelLockedKeys * 2)

            forBigDecimal(centerLat - distanceDeg, centerLat + distanceDeg, TILE_WIDTH) { lat ->
                forBigDecimal(centerLon - distanceDeg, centerLon + distanceDeg, TILE_WIDTH) { lon ->
                    parallelLoads.acquire()

                    coroutineScope {
                        launch(Dispatchers.IO) {
                            try {
                                elevationDataCache.preFetch(lat to lon)
                            } catch (e: Exception) {
                                DebugLog.w(TAG, "Failed to prefetch elevation for $lat/$lon")
                            }
                            parallelLoads.release()
                        }
                        tilesFetched++
                    }
                }
            }

            distanceDeg += TILE_WIDTH * TWO
        }
    }

    private val elevationProfile =
        object : AsyncLiveData<Array<ElevationProfileNode>>(setNullValues = true) {
            var lastVisibleTrack: Track? = null

            init {
                requestUpdateIfChanged(PreviousRidesRepository[app].visibleTrack)
            }

            override suspend fun update(): Array<ElevationProfileNode>? {
                val visibleTrack = PreviousRidesRepository[app].visibleTrack.value
                var distanceCovered = 0f
                var elevationData: ElevationData? = null
                var elevationArea: MapArea? = null

                val ele = visibleTrack?.segments?.flatMap { segment ->
                    var lastLoc: RecordedLocation? = null

                    segment.map { loc ->
                        val scale = TILE_SCALE
                        val factor = 10.0.pow(scale)

                        // Always load a little around the current location as elevation data at the
                        // very edge of the loaded area is not precise
                        val minLat = floor((loc.latitude - 0.01) * factor) / factor
                        val minLon = floor((loc.longitude - 0.01) * factor) / factor
                        val maxLat = ceil((loc.latitude + 0.01) * factor) / factor
                        val maxLon = ceil((loc.longitude + 0.01) * factor) / factor

                        // Elevation data from the track is usually imprecise enough to cause too many
                        // issues
                        if (elevationArea?.contains(minLat, minLon, maxLat, maxLon) != true) {
                            val newElevationArea = MapArea(
                                // Rounding should be unnecessary, but floating point errors might have
                                // happened
                                BigDecimal(minLat).setScale(scale, RoundingMode.HALF_UP),
                                BigDecimal(minLon).setScale(scale, RoundingMode.HALF_UP),
                                BigDecimal(maxLat).setScale(scale, RoundingMode.HALF_UP),
                                BigDecimal(maxLon).setScale(scale, RoundingMode.HALF_UP)
                            )

                            elevationData = ElevationDataRepository[app]
                                .getElevationData(newElevationArea).observeAsChannel()
                                .consumeAsFlow()
                                .first()
                            elevationArea = newElevationArea
                        }

                        val elevationPoint = lastLoc?.let {
                            distanceCovered += loc.distanceTo(it)
                            ElevationProfileNode(
                                loc.latitude,
                                loc.longitude,
                                elevationData!!.getElevation(loc),
                                distanceCovered
                            )
                        } ?: ElevationProfileNode(
                            loc.latitude,
                            loc.longitude,
                            elevationData!!.getElevation(loc),
                            distanceCovered
                        )

                        lastLoc = loc

                        elevationPoint
                    }.let { profile ->
                        // The elevation from the [ElevationDataRepository] is nice as it is always
                        // available and consistent all over the globe. As it is recorded by
                        // satellite it is only precise to 30m and sometimes confuses trees for the
                        // ground. Hence check if the original recorded track had elevation data and
                        // if the shape roughly matches the satellite data. If so, adjust the the
                        // track data by an offset to match the satellite data and use it instead.
                        if (segment.all { it.elevation != null }) {
                            var maxOffset = Double.NEGATIVE_INFINITY
                            var minOffset = Double.POSITIVE_INFINITY

                            segment.indices.forEach { i ->
                                maxOffset =
                                    max(maxOffset, segment[i].elevation!! - profile[i].elevation)
                                minOffset =
                                    min(minOffset, segment[i].elevation!! - profile[i].elevation)
                            }

                            if (maxOffset - minOffset < MAX_SAT_VS_TRACK_OFFSET_DIFFERENCE) {
                                profile.mapIndexed { i, orig ->
                                    ElevationProfileNode(
                                        orig.latitude,
                                        orig.longitude,
                                        (segment[i].elevation!! - (maxOffset + minOffset) / 2).toFloat(),
                                        orig.progress
                                    )
                                }
                            } else {
                                profile
                            }
                        } else {
                            profile
                        }
                    }
                }?.toTypedArray()

                lastVisibleTrack = visibleTrack

                return if (ele != null && ele.size >= 2) {
                    ele
                } else {
                    null
                }
            }
        }

    private val reverseElevationProfile =
        object : AsyncLiveData<Array<ElevationProfileNode>>(setNullValues = true) {
            init {
                requestUpdateIfChanged(elevationProfile)
            }

            override suspend fun update(): Array<ElevationProfileNode>? {
                return elevationProfile.value?.let { elevationProfile ->
                    elevationProfile.map {
                        ElevationProfileNode(
                            it.latitude,
                            it.longitude,
                            it.elevation,
                            elevationProfile.last().progress - it.progress
                        )
                    }.toTypedArray().apply { reverse() }
                }
            }
        }

    private val climbs = object : AsyncLiveData<Array<Climb>>(setNullValues = true) {
        private val MIN_CLIMB_GRADE = 3f // m/100m
        private val MIN_CLIMB_DISTANCE = 500 // m
        private val MIN_GAP_IN_BETWEEN_CLIMBS = 1000 // m

        private val MAX_RAMP_LENGTH = 300 // m
        private val MIN_RAMP_LENGTH = 200 // m

        init {
            requestUpdateIfChanged(elevationProfile, reverseElevationProfile)
        }

        override suspend fun update(): Array<Climb>? {
            val forward = elevationProfile.value ?: return null
            val reverse = reverseElevationProfile.value ?: return null

            return listOf(
                // Get ranges in both directions
                forward,
                reverse
            ).flatMap { elevations ->
                // Votes how many times an elevation point was considered a climb and not
                val isPartOfClimbVotes = IntArray(elevations.size)

                // Start of individual ramp
                // A ramp is a measured section of the track
                var rampStartIdx = 0

                elevations.indices.forEach { rampLastIdx ->
                    // Move start of ramp to keep ramp size in between min and max distance
                    while (true) {
                        if (rampStartIdx >= rampLastIdx - 1) {
                            break
                        }

                        if (elevations[rampLastIdx].progress - elevations[rampStartIdx].progress
                            < MAX_RAMP_LENGTH
                        ) {
                            if (rampStartIdx >= 1
                                && elevations[rampLastIdx].progress -
                                elevations[rampStartIdx - 1].progress
                                < MIN_RAMP_LENGTH
                            ) {
                                rampStartIdx--
                            }

                            break
                        }

                        rampStartIdx++
                    }

                    if (rampLastIdx > 0) {
                        val rampGrade =
                            (elevations[rampLastIdx].elevation -
                                    elevations[rampStartIdx].elevation) * 100 /
                                    (elevations[rampLastIdx].progress -
                                            elevations[rampStartIdx].progress)

                        if (rampGrade > MIN_CLIMB_GRADE) {
                            (rampStartIdx..rampLastIdx).forEach { i ->
                                isPartOfClimbVotes[i]++
                            }
                        } else {
                            (rampStartIdx..rampLastIdx).forEach { i ->
                                isPartOfClimbVotes[i]--
                            }
                        }
                    }
                }

                var currentClimbStart = -1

                isPartOfClimbVotes.indices.mapNotNull { i ->
                    if (isPartOfClimbVotes[i] > 0
                        // Always end climbs at last elevation point
                        && i < isPartOfClimbVotes.size - 1
                    ) {
                        if (currentClimbStart == -1) {
                            currentClimbStart = i
                        }
                        null
                    } else {
                        if (currentClimbStart != -1) {
                            Climb(
                                elevations[currentClimbStart].progress,
                                currentClimbStart,
                                elevations[i].progress - elevations[currentClimbStart].progress,
                                i - currentClimbStart,
                                elevations
                            ).also {
                                currentClimbStart = -1
                            }
                        } else {
                            null
                        }
                    }
                }.fold(mutableListOf<Climb>()) { previousClimbs, climb ->
                    // Fuse climbs that are close together into single climb
                    if (previousClimbs.isNotEmpty()
                        && climb.start - previousClimbs.last().end < MIN_GAP_IN_BETWEEN_CLIMBS
                    ) {
                        val prevClimb = previousClimbs.removeLast()
                        previousClimbs.add(prevClimb.combine(climb))
                    } else {
                        previousClimbs.add(climb)
                    }

                    previousClimbs
                }.filter {
                    // remove short climbs
                    it.length > MIN_CLIMB_DISTANCE
                }
            }.toTypedArray()
        }
    }

    private val climbElevationProfile =
        object : AsyncLiveData<Pair<Float, List<ElevationProgressNode>>>() {
            private var lastClimb: Climb? = null
            private var lastClimbAsProgressNode: List<ElevationProgressNode>? = null

            init {
                requestUpdateIfChanged(
                    LocationRepository[app].location,
                    PreviousRidesRepository[app].closestLocationOnTrack, climbs
                )
            }

            override suspend fun update(): Pair<Float, List<ElevationProgressNode>> {
                // How far of the track can you be and still be considered on it (in meters)
                val MAX_DISTANCE_FROM_TRACK = 50f

                val climbs = climbs.value ?: return 0f to ElevationView.NO_DATA
                val location = LocationRepository[app].location.value?.toBasicLocation()
                    ?: return 0f to ElevationView.NO_DATA
                val (progress, closest) = PreviousRidesRepository[app].closestLocationOnTrack.value
                    ?: return 0f to ElevationView.NO_DATA

                if (location.distanceTo(closest) > MAX_DISTANCE_FROM_TRACK) {
                    return 0f to ElevationView.NO_DATA
                }

                val closeLastClimb =
                    climbs.find { climb -> climb.start <= progress && progress <= climb.end}
                        ?: return 0f to ElevationView.NO_DATA

                return if (closeLastClimb == lastClimb) {
                    progress - closeLastClimb.start to lastClimbAsProgressNode!!
                } else {
                    lastClimb = closeLastClimb
                    // Convert to output format
                    lastClimbAsProgressNode = closeLastClimb.map { it.toProgressNode(closeLastClimb.start) }

                    progress - closeLastClimb.start to lastClimbAsProgressNode!!
                }
            }
        }

    val currentClimbElevationProfile = DelayedChangedLiveData(SECONDS.toMillis(5),
        object : MediatorLiveData<Pair<Float, List<ElevationProgressNode>>>() {
            private var lastProgress: Float = Float.NEGATIVE_INFINITY
            private var lastClimb: List<ElevationProgressNode>? = null

            init {
                addSources(climbElevationProfile, LocationRepository[app].isMoving) { update() }
            }

            private fun update() {
                if (LocationRepository[app].isMoving.value == true) {
                    climbElevationProfile.value?.let { (progress, climb) ->
                        // Need to progress at least 20 m forward on the profile to be shown. This
                        // way we don't accidentally show downhills as climbs
                        value = if (lastClimb == climb && (progress - lastProgress) > 20) {
                            progress to climb
                        } else {
                            0f to ElevationView.NO_DATA
                        }
                        if (lastClimb != climb) {
                            // Record start of using this profile
                            lastProgress = progress
                        }
                        lastClimb = climb
                    } ?: run {
                        value = 0f to ElevationView.NO_DATA
                    }
                }
            }
        }) { a, b -> a?.second == b?.second }

    val climbLeft = object : MediatorLiveData<Distance>() {
        init {
            addSource(currentClimbElevationProfile) { (progress, elevations) ->
                value = Distance(
                    max(
                        0f,
                        min(
                            elevations.last().progress,
                            elevations.last().progress - progress
                        )
                    )
                )
            }
        }
    }

    @MainThread
    companion object : SingleParameterSingletonOf<Application, ElevationDataRepository>({
        ElevationDataRepository(it)
    }) {
        const val TILE_SCALE: Int = 1
    }
}

/**
 * A section of the current visible track (forward or reverse) that represents a note worthy climb
 */
private class Climb(
    /** Progress (in meters) when the climb starts compared to start of [elevations] */
    val start: Float,
    /** Index into [elevations] where the climb starts */
    private val startIdx: Int,
    /** Length (in meters) of the climb */
    val length: Float,
    /** Num of nodes making up the climb */
    private val size: Int,
    /** Nodes of the track making up the climb */
    private val elevations: Array<ElevationProfileNode>
) : Iterable<ElevationProfileNode> {
    val end: Float
        get() {
            return start + length
        }

    init {
        assert(start >= 0)
        assert(elevations[startIdx].progress == start)
        assert(length >= 0)
        assert(abs(elevations[startIdx + size].progress - elevations[startIdx].progress) == length)
    }

    val indices = 0 until size

    operator fun get(i: Int): ElevationProfileNode {
        return elevations[i + startIdx]
    }

    fun combine(other: Climb): Climb {
        assert(elevations === other.elevations)

        val (a, b) = if (start < other.start && end < other.end) {
            this to other
        } else if (start > other.start && end > other.end) {
            other to this
        } else if (start < other.start && end > other.end) {
            this to this
        } else {
            other to other
        }

        return Climb(
            a.start,
            a.startIdx,
            b.end - a.start,
            b.startIdx + b.size - a.startIdx,
            elevations
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }

        if (javaClass != other?.javaClass) {
            return false
        }

        other as Climb

        return startIdx == other.startIdx && size == other.size
    }

    override fun hashCode(): Int {
        var result = startIdx
        result = 31 * result + size
        return result
    }

    override fun iterator(): Iterator<ElevationProfileNode> {
        return object : Iterator<ElevationProfileNode> {
            var i = startIdx

            override fun hasNext(): Boolean {
                return i <= startIdx + size
            }

            override fun next(): ElevationProfileNode {
                return elevations[i++]
            }
        }
    }
}

/**
 * A [Node] as part a [Track] or [Climb]
 */
private class ElevationProfileNode(
    latitude: Double,
    longitude: Double,
    /** Elevation of the node */
    val elevation: Float,
    /** Distance (in meters) since start of track */
    val progress: Float,
) : Node(0, latitude, longitude) {
    fun toProgressNode(climbStartProgress: Float): ElevationProgressNode {
        return ElevationProgressNode(
            elevation,
            progress - climbStartProgress
        )
    }
}

/**
 * A point of a climb profile
 */
class ElevationProgressNode(
    /** height of point in meters */
    val elevation: Float,
    /** Distance (in meters) since start of climb */
    val progress: Float,
)
