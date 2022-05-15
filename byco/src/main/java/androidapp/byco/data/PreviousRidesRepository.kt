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

import android.os.FileObserver
import android.os.FileObserver.CLOSE_WRITE
import android.os.FileObserver.DELETE
import android.os.FileObserver.MOVED_FROM
import android.os.FileObserver.MOVED_TO
import androidapp.byco.BycoApplication
import androidapp.byco.RECORDINGS_DIRECTORY
import androidapp.byco.util.Repository
import androidapp.byco.util.SelfCleaningCache
import androidapp.byco.util.SingleParameterSingletonOf
import androidapp.byco.util.Trigger
import androidapp.byco.util.compat.createFileObserverCompat
import androidapp.byco.util.newEntry
import androidapp.byco.util.plus
import androidapp.byco.util.stateIn
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import lib.gpx.BasicLocation
import lib.gpx.DebugLog
import lib.gpx.GPX_ZIP_FILE_EXTENSION
import lib.gpx.MapArea
import lib.gpx.TRACK_ZIP_ENTRY
import lib.gpx.Track
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.lang.ref.WeakReference
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.time.Duration.Companion.seconds

/** Management of previously recorded rides */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class PreviousRidesRepository private constructor(
    private val app: BycoApplication,
    private val MAX_CLOSEST_AREA_LAT_LON: Double = 0.02, // in degrees
    private val MAX_CLOSEST_DISTANCE: Float = 1000f // m
) : Repository(app) {
    private val TAG = PreviousRidesRepository::class.java.simpleName
    private val MAX_PREVIOUS_RIDES_UPDATE = 5.seconds

    private val tracksMutex = Mutex()
    private val tracks = SelfCleaningCache<PreviousRide, StateFlow<Track?>>(1)

    /** Track - i.e. - the actual locations of a [ride] */
    suspend fun getTrack(ride: PreviousRide): SharedFlow<Track?> {
        var v = tracksMutex.withLock { tracks[ride] }

        if (v == null) {
            v = flow {
                emit(
                    try {
                        Track.parseFrom(ride.file)
                    } catch (e: IOException) {
                        null
                    }
                )
            }.stateIn(null)

            tracksMutex.withLock {
                tracks[ride] = v
            }
        }
        return v
    }

    /** Send a `Unit` to force an update to [previousRides]. */
    internal val previousRidesUpdateTrigger = Trigger(repositoryScope)

    private val changeToRecordingsDirectory = callbackFlow {
        val directoryObserver = createFileObserverCompat(
            File(
                app.filesDir,
                RECORDINGS_DIRECTORY
            )
        ) { event: Int, _: String? ->
            if (event in setOf(CLOSE_WRITE, MOVED_TO, MOVED_FROM, DELETE)) {
                launch {
                    send(event)
                }
            }
        }

        directoryObserver.startWatching()

        awaitClose { directoryObserver.stopWatching() }
    }.stateIn(FileObserver.CREATE)

    /** All previous recorded (and added) rides */
    val previousRides = flow {
        var previouslyParsed = listOf<PreviousRide>()

        (previousRidesUpdateTrigger.flow + changeToRecordingsDirectory).collect {
            val concurrencyLimit = Semaphore(8)

            val newParsed = coroutineScope {
                File(app.filesDir, RECORDINGS_DIRECTORY).listFiles()
                    ?.map { file ->
                        async(IO) {
                            previouslyParsed.find {
                                it.file.name == file.name && it.lastModified == file.lastModified()
                            } ?: run {
                                return@async try {
                                    concurrencyLimit.withPermit {
                                        PreviousRide.parseFrom(file)
                                    }
                                } catch (e: Exception) {
                                    if (isActive) {
                                        DebugLog.e(TAG, "Cannot parse $file, deleting", e)
                                        file.delete()
                                    }

                                    null
                                }
                            }
                        }
                    }?.mapNotNull { it.await() }?.sortedBy { it.title }
                    ?.sortedByDescending { it.time }
                    ?: emptyList()
            }

            previouslyParsed = newParsed
            emit(previouslyParsed)
        }
    }.stateIn(emptyList())

    /**
     * Ride to be shown on map.
     *
     * @see showOnMap
     */
    val rideShownOnMap = MutableStateFlow<PreviousRide?>(null)

    /**
     * Track (@see [getTrack]) to be shown on map.
     *
     * @see rideShownOnMap
     * @see showOnMap
     */
    val visibleTrack = rideShownOnMap.flatMapLatest {
        it?.let { getTrack(it) } ?: flowOf(null)
    }.stateIn(null)

    /**
     * Closest location to current location of [visibleTrack].
     *
     * Format: track progress at closest location, closes location
     *
     * Maximum [MAX_CLOSEST_AREA_LAT_LON] away
     */
    val closestLocationOnTrack = flow {
        var reducedVisibleTrackArea: MapArea? = null
        var reducedVisibleTrackSrc: WeakReference<Track>? = null
        var reducedVisibleTrack: TrackAsNodes? = null

        (visibleTrack + LocationRepository[app].smoothedLocation).collect {
            val location =
                LocationRepository[app].smoothedLocation.value?.location ?: run {
                    emit(null)
                    return@collect
                }
            val visibleTrack = visibleTrack.value ?: run {
                emit(null)
                return@collect
            }

            val newVisibleTrackArea = MapArea(
                floor((location.latitude - MAX_CLOSEST_AREA_LAT_LON) / MAX_CLOSEST_AREA_LAT_LON) * MAX_CLOSEST_AREA_LAT_LON,
                floor((location.longitude - MAX_CLOSEST_AREA_LAT_LON) / MAX_CLOSEST_AREA_LAT_LON) * MAX_CLOSEST_AREA_LAT_LON,
                ceil((location.latitude + MAX_CLOSEST_AREA_LAT_LON) / MAX_CLOSEST_AREA_LAT_LON) * MAX_CLOSEST_AREA_LAT_LON,
                ceil((location.longitude + MAX_CLOSEST_AREA_LAT_LON) / MAX_CLOSEST_AREA_LAT_LON) * MAX_CLOSEST_AREA_LAT_LON
            )

            if (newVisibleTrackArea != reducedVisibleTrackArea
                || reducedVisibleTrackSrc?.get() != visibleTrack
            ) {
                reducedVisibleTrack =
                    visibleTrack.restrictTo(newVisibleTrackArea, computeProgress = true)

                reducedVisibleTrackSrc = WeakReference(visibleTrack)
                reducedVisibleTrackArea = newVisibleTrackArea
            }

            var absoluteClosest: BasicLocation? = null
            var absoluteClosestDistance = Float.POSITIVE_INFINITY
            var absoluteClosestProgress: Float? = null

            reducedVisibleTrack!!.forEach { (progressAtSegmentStart, segment) ->
                var progress = progressAtSegmentStart

                coroutineScope {
                    ensureActive()
                }

                segment.forEachIndexed { i, _ ->
                    if (i > 0) {
                        val closest = location.closestNodeOn(segment[i - 1], segment[i])
                        val distance = segment[i - 1].distanceTo(segment[i])
                        val closestDistance = location.distanceTo(closest)

                        if (closestDistance < absoluteClosestDistance
                            && closestDistance < MAX_CLOSEST_DISTANCE
                        ) {
                            val distanceToStart = closest.distanceTo(segment[i - 1])
                            val distanceToEnd = closest.distanceTo(segment[i])

                            absoluteClosest = closest
                            absoluteClosestDistance = closestDistance
                            absoluteClosestProgress =
                                progress * (distanceToEnd / distance) +
                                        (progress + distance) * (distanceToStart / distance)
                        }

                        progress += distance
                    }
                }
            }

            emit(absoluteClosest?.let {
                absoluteClosestProgress!! to absoluteClosest!!
            })
        }
    }.stateIn(null)

    /**
     * Set ride to be shown on the map.
     *
     * `null` to show no ride.
     *
     * @see rideShownOnMap
     */
    suspend fun showOnMap(ride: PreviousRide?) {
        rideShownOnMap.emit(ride)
    }

    /**
     * Add a ride to the repository of previous rides.
     *
     * This ride is persisted on disk.
     *
     * @return The newly added ride
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun addRide(
        newRideIs: InputStream,
        fileName: String? = null,
        progressCallback: suspend (Float?) -> Unit
    ): PreviousRide? {
        val file = if (fileName != null) {
            File(app.filesDir, fileName)
        } else {
            File.createTempFile("import-", GPX_ZIP_FILE_EXTENSION, app.filesDir)
        }

        // Is there a better way to guess the size of the input?
        val estimatedSize = newRideIs.available()

        file.outputStream().buffered().use { os ->
            ZipOutputStream(os).use { zipOs ->
                zipOs.newEntry(TRACK_ZIP_ENTRY) {
                    var numTotalRead = 0L
                    val buffer = ByteArray(16384)

                    var numRead = newRideIs.read(buffer)
                    while (numRead >= 0) {
                        zipOs.write(buffer, 0, numRead)
                        numTotalRead += numRead
                        numRead = newRideIs.read(buffer)

                        if (estimatedSize <= 0 || numTotalRead >= estimatedSize) {
                            progressCallback(null)
                        } else {
                            progressCallback(numTotalRead.toFloat() / estimatedSize)
                        }
                    }
                }
            }
        }

        File(app.filesDir, RECORDINGS_DIRECTORY).mkdirs()

        // atomic rename to never not have partial files (due to crashes) in repository.
        file.renameTo(File(File(app.filesDir, RECORDINGS_DIRECTORY), file.name))

        // Request update of [previousRides] as fileobserver can be unreliable
        previousRidesUpdateTrigger.trigger()

        // Find new [PreviousRide]
        return previousRides.filter { rides -> rides.find { ride -> ride.file.name == file.name } != null }
            // If ride cannot be found, give up after [MAX_PREVIOUS_RIDES_UPDATE].
            .timeout(MAX_PREVIOUS_RIDES_UPDATE).catch { e ->
                if (e is TimeoutCancellationException) {
                    emit(emptyList())
                }
            }.first().find { ride -> ride.file.name == file.name }
    }

    fun delete(ride: PreviousRide) {
        ride.file.delete()

        // Instantly update [previousRides] as file observer can be unreliable
        previousRidesUpdateTrigger.trigger()
    }

    /**
     * Change title of a [ride]
     */
    suspend fun changeTitle(ride: PreviousRide, newTitle: String) {
        withContext(IO) {
            val ins = PipedInputStream()
            val out = PipedOutputStream(ins)

            // receive data from pipe and add as ride
            launch {
                ins.buffered().use { addRide(it, ride.file.name) {} }
            }

            // Re-serialize ride into pipe
            out.buffered().use {
                it.writePreviousRide(app, ride, newTitle)
            }
        }

        previousRidesUpdateTrigger.trigger()
    }

    companion object :
        SingleParameterSingletonOf<BycoApplication, PreviousRidesRepository>({
            PreviousRidesRepository(
                it
            )
        })
}
