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
import android.os.FileObserver.*
import androidapp.byco.RECORDINGS_DIRECTORY
import androidapp.byco.util.AsyncLiveData
import androidapp.byco.util.SelfCleaningCache
import androidapp.byco.util.SingleParameterSingletonOf
import androidapp.byco.util.compat.createFileObserverCompat
import androidapp.byco.util.newEntry
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import lib.gpx.*
import java.io.*
import java.lang.ref.WeakReference
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlin.math.floor

/** Management of previously recorded rides */
class PreviousRidesRepository private constructor(
    private val app: Application,
    private val MAX_CLOSEST_AREA_LAT_LON: Double = 0.02, // in degrees
    private val MAX_CLOSEST_DISTANCE: Float = 1000f // m
) {
    private val TAG = PreviousRidesRepository::class.java.simpleName

    private val tracks = SelfCleaningCache<PreviousRide, LiveData<Track>>(1)

    /** Track - i.e. - the actual locations of a [ride] */
    fun getTrack(ride: PreviousRide): LiveData<Track> {
        var v = tracks[ride]

        if (v == null) {
            v = object : AsyncLiveData<Track>(IO) {
                override suspend fun update(): Track? {
                    return try {
                        Track.parseFrom(ride.file)
                    } catch (e: IOException) {
                        null
                    }
                }
            }

            tracks[ride] = v
        }
        return v
    }

    inner class PreviousRides : AsyncLiveData<List<PreviousRide>>() {
        private val directoryObserver = createFileObserverCompat(
            File(
                app.filesDir,
                RECORDINGS_DIRECTORY
            )
        ) { event: Int, _: String? ->
            if (event in setOf(CLOSE_WRITE, MOVED_TO, MOVED_FROM, DELETE)) {
                requestUpdate()
            }
        }

        init {
            addSource(RideRecordingRepository[app].isRideBeingRecorded) {
                requestUpdate()
            }
        }

        override fun onActive() {
            super.onActive()
            directoryObserver.startWatching()
            requestUpdate()
        }

        override suspend fun update(): List<PreviousRide> {
            val previouslyParsed = value ?: emptyList()
            val concurrencyLimit = Semaphore(8)

            return coroutineScope {
                File(app.filesDir, RECORDINGS_DIRECTORY).listFiles()
                    ?.map { file ->
                        async(IO) {
                            previouslyParsed.find {
                                it.file.name == file.name && it.lastModified == file.lastModified()
                            } ?: run {
                                try {
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
                    ?.sortedByDescending { it.time } ?: emptyList()
            }
        }

        override fun onInactive() {
            super.onInactive()
            directoryObserver.stopWatching()
        }
    }

    /** All previous recorded (and added) rides */
    val previousRides = PreviousRides()

    /**
     * Ride to be shown on map.
     *
     * @see showOnMap
     */
    val rideShownOnMap = MutableLiveData<PreviousRide?>()

    /**
     * Track (@see [getTrack]) to be shown on map.
     *
     * @see rideShownOnMap
     * @see showOnMap
     */
    val visibleTrack = object : MediatorLiveData<Track?>() {
        private var currentlyShownRide: PreviousRide? = null
        private var trackLiveData: LiveData<Track>? = null

        init {
            addSource(rideShownOnMap) { ride ->
                if (ride == currentlyShownRide) {
                    return@addSource
                }
                currentlyShownRide = ride

                trackLiveData?.let { removeSource(it) }
                if (ride != null) {
                    trackLiveData = getTrack(ride).also { addSource(it) { value = it } }
                } else {
                    value = null
                }
            }
        }
    }

    /**
     * Closest location to current location of [visibleTrack].
     *
     * Format: track progress at closest location, closes location
     *
     * Maximum [MAX_CLOSEST_AREA_LAT_LON] away
     */
    val closestLocationOnTrack =
        object : AsyncLiveData<Pair<Float, BasicLocation>>(setNullValues = true) {
            private var reducedVisibleTrackArea: MapArea? = null
            private var reducedVisibleTrackSrc: WeakReference<Track>? = null
            private var reducedVisibleTrack: TrackAsNodes? = null

            init {
                requestUpdateIfChanged(visibleTrack, LocationRepository[app].location)
            }

            override suspend fun update(): Pair<Float, BasicLocation>? {
                val location = LocationRepository[app].location.value?.toBasicLocation() ?: return null
                val visibleTrack = visibleTrack.value ?: return null

                val newVisibleTrackArea = MapArea(
                    floor((location.latitude - MAX_CLOSEST_AREA_LAT_LON) / MAX_CLOSEST_AREA_LAT_LON) * MAX_CLOSEST_AREA_LAT_LON,
                    floor((location.longitude - MAX_CLOSEST_AREA_LAT_LON) / MAX_CLOSEST_AREA_LAT_LON) * MAX_CLOSEST_AREA_LAT_LON,
                    ceil((location.latitude + MAX_CLOSEST_AREA_LAT_LON) / MAX_CLOSEST_AREA_LAT_LON) * MAX_CLOSEST_AREA_LAT_LON,
                    ceil((location.longitude + MAX_CLOSEST_AREA_LAT_LON) / MAX_CLOSEST_AREA_LAT_LON) * MAX_CLOSEST_AREA_LAT_LON
                )

                if (newVisibleTrackArea != reducedVisibleTrackArea
                    || reducedVisibleTrackSrc?.get() != visibleTrack) {

                    reducedVisibleTrack = visibleTrack.restrictTo(newVisibleTrackArea, computeProgress = true)

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

                return absoluteClosest?.let {
                    absoluteClosestProgress!! to absoluteClosest!!
                }
            }
        }

    /**
     * Set ride to be shown on the map.
     *
     * `null` to show no ride.
     *
     * @see rideShownOnMap
     */
    fun showOnMap(ride: PreviousRide?) {
        rideShownOnMap.value = ride
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

        // Instantly and synchronous update [previousRides]
        withContext(Main) {
            previousRides.value = previousRides.update()
        }

        return previousRides.value?.find { ride -> ride.file.name == file.name }
    }

    fun delete(ride: PreviousRide) {
        ride.file.delete()

        // Instantly update live data as file observer can be unreliable
        previousRides.requestUpdate()
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
    }

    companion object :
        SingleParameterSingletonOf<Application, PreviousRidesRepository>({
            PreviousRidesRepository(
                it
            )
        })
}
