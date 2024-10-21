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

package androidapp.byco.background

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_ONE_SHOT
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.PendingIntent.getActivity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_SHUTDOWN
import android.content.IntentFilter
import android.location.Location
import android.os.Build
import android.os.DeadObjectException
import android.os.IBinder
import androidapp.byco.NotificationIds
import androidapp.byco.ONGOING_TRIP_RECORDINGS_NOTIFICATION_CHANNEL
import androidapp.byco.RECORDINGS_DIRECTORY
import androidapp.byco.data.GpxSerializer
import androidapp.byco.data.LocationRepository
import androidapp.byco.data.PhoneStateRepository
import androidapp.byco.data.PreviousRide
import androidapp.byco.data.RideRecordingRepository
import androidapp.byco.lib.R
import androidapp.byco.ui.RidingActivity
import androidapp.byco.ui.StopRideActivity
import androidapp.byco.util.BycoService
import androidx.annotation.GuardedBy
import androidx.annotation.MainThread
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.PRIORITY_LOW
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Observer
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import lib.gpx.BasicLocation
import lib.gpx.DebugLog
import lib.gpx.Distance
import lib.gpx.Duration
import lib.gpx.RecordedLocation
import lib.gpx.TRACK_ZIP_ENTRY
import lib.gpx.toBasicLocation
import lib.gpx.toRecordedLocation
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import java.util.concurrent.TimeUnit.HOURS
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Background service recorded a ride.
 *
 * Runs in separate process, hence use [RideRecordingRepository] to interact with this.
 */
class RideRecorder : BycoService() {
    private val TAG = RideRecorder::class.java.simpleName

    private lateinit var impl: RideRecorderImpl

    override fun onCreate() {
        super.onCreate()

        impl = RideRecorderImpl()
    }

    override fun onBind(intent: Intent?): IBinder {
        return impl
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        impl.startRecording(intent?.getBooleanExtra(EXTRA_USE_FOREGROUND_SERVICE, false) == true)

        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        super.onDestroy()

        // Doesn't start/stop [Prefetcher] as not running in main process
        impl.onDestroy()
    }

    private inner class RideRecorderImpl : IRideRecorder.Stub() {
        private val DATE_MARKER = 'd'
        private val NEW_SEGMENT_MARKER = 's'
        private val POINT_MARKER = 'p'

        private val recorderScope =
            CoroutineScope(
                app.appScope.coroutineContext + Job(app.appScope.coroutineContext.job) + CoroutineName(
                    RideRecorderImpl::class.simpleName!!
                )
            )
        private var recordingJob: Job? = null

        @GuardedBy("clients")
        private val clients = mutableListOf<IRideClient>()

        @GuardedBy("this")
        private var rideStartDate: Date? = null

        @GuardedBy("this")
        private var rideStartLocation: BasicLocation? = null

        @GuardedBy("this")
        private var rideDistance = 0f

        @GuardedBy("this")
        private var usesForegroundService = false

        @GuardedBy("this")
        private var lastLocation: Location? = null

        @GuardedBy("this")
        private var temporaryRecording: BufferedWriter? = null

        private val deathRecipient = IBinder.DeathRecipient {
            synchronized(clients) {
                clients.toList().forEach { client ->
                    if (!client.asBinder().isBinderAlive) {
                        clients.remove(client)
                    }
                }
            }
        }

        override fun registerClient(client: IRideClient) {
            synchronized(clients) {
                clients.add(client)
                client.asBinder().linkToDeath(deathRecipient, 0)
            }
        }

        override fun unregisterClient(client: IRideClient) {
            synchronized(clients) {
                clients.add(client)
                client.asBinder().unlinkToDeath(deathRecipient, 0)
            }
        }

        private fun notifyClients() {
            recorderScope.launch(Main.immediate) {
                synchronized(clients) {
                    clients.toList().forEach { client ->
                        synchronized(this) {
                            try {
                                if (rideStartDate == null) {
                                    client.onRideChanged(false, null, null, null)
                                } else {
                                    client.onRideChanged(
                                        true,
                                        Duration(Date().time - rideStartDate!!.time),
                                        rideStartLocation,
                                        Distance(rideDistance)
                                    )
                                }
                            } catch (e: DeadObjectException) {
                                clients.remove(client)
                            }
                        }
                    }
                }
            }
        }

        private fun RecordedLocation.serialize(): String {
            return "$POINT_MARKER $latitude $longitude ${
                if (elevation == null) {
                    "null"
                } else {
                    elevation
                }
            } $time"
        }

        private fun String.parseRecordedLocation(): RecordedLocation? {
            return try {
                val elements = split(" ")
                RecordedLocation(
                    elements[1].toDouble(),
                    elements[2].toDouble(),
                    elements[3].let {
                        if (it == "null") {
                            null
                        } else {
                            it.toDouble()
                        }
                    },
                    elements[4].toLong()
                )
            } catch (e: Exception) {
                null
            }
        }

        private fun File.forEachRecording(): List<List<RecordedLocation>> {
            val segments = mutableListOf<MutableList<RecordedLocation>>()
            var currentSegment = mutableListOf<RecordedLocation>()

            inputStream().bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    when {
                        line.startsWith(DATE_MARKER) -> {}
                        line.startsWith(NEW_SEGMENT_MARKER) -> {
                            if (currentSegment.isNotEmpty()) {
                                segments.add(currentSegment)
                            }
                            currentSegment = mutableListOf()
                        }

                        line.startsWith(POINT_MARKER) -> {
                            line.parseRecordedLocation()
                                ?.let { currentSegment.add(it) }
                        }
                    }
                }
            }

            if (currentSegment.isNotEmpty()) {
                segments.add(currentSegment)
            }

            return segments
        }

        private val locationObserver = Observer<Location?> { newLoc ->
            newLoc ?: return@Observer
            synchronized(this) {
                temporaryRecording?.apply {
                    write("${newLoc.toRecordedLocation().serialize()}\n")
                    flush()
                }

                var needsNotify = false
                if (rideStartLocation == null) {
                    rideStartLocation = newLoc.toBasicLocation()
                    needsNotify = true
                }

                lastLocation?.let {
                    rideDistance += it.distanceTo(newLoc)
                    needsNotify = true
                }

                if (needsNotify) {
                    notifyClients()
                }

                lastLocation = newLoc
            }
        }

        private val dateObserver = Observer<Date> {
            // Abort ride recording if user forgot to stop it
            rideStartDate?.let { rideStart ->
                if (System.currentTimeMillis() - rideStart.time > HOURS.toMillis(18)) {
                    stopRecording()
                }
            }

            notifyClients()
        }

        private val rideTimeObserver = Observer<Duration?> {
            it?.let { rideTime ->
                synchronized(this) {
                    if (usesForegroundService
                        && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                    ) {
                        getSystemService(NotificationManager::class.java).notify(
                            NotificationIds.RIDE_RECORDING.ordinal,
                            getNotification(rideTime)
                        )
                    }
                }
            }
        }

        private val shutdownReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // Quickly finish recording and properly close recording file to avoid corrupt
                // ongoing recording files when phone is shut down.
                pauseRecording()
            }
        }

        private fun getNotification(rideTime: Duration): Notification {
            NotificationManagerCompat.from(this@RideRecorder).createNotificationChannel(
                NotificationChannelCompat.Builder(
                    ONGOING_TRIP_RECORDINGS_NOTIFICATION_CHANNEL,
                    NotificationManagerCompat.IMPORTANCE_LOW
                ).setName(getString(R.string.ongoing_recording_notification_channel)).build()
            )

            return NotificationCompat.Builder(
                this@RideRecorder,
                ONGOING_TRIP_RECORDINGS_NOTIFICATION_CHANNEL
            )
                .setContentTitle(getString(R.string.recording_notification_title))
                .setContentText(
                    getString(
                        R.string.recording_notification_content,
                        rideTime.format(this@RideRecorder)
                    )
                )
                .setSmallIcon(R.drawable.ic_baseline_bike_24)
                .setContentIntent(
                    getActivity(
                        this@RideRecorder,
                        1,
                        Intent(this@RideRecorder, RidingActivity::class.java),
                        FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE
                    )
                )
                .addAction(
                    R.drawable.ic_baseline_stop_24,
                    getString(R.string.stop_ride),
                    getActivity(
                        this@RideRecorder,
                        0,
                        Intent(this@RideRecorder, StopRideActivity::class.java),
                        FLAG_ONE_SHOT or FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE
                    )
                )
                .setPriority(PRIORITY_LOW)
                .build()
        }

        @MainThread
        fun startRecording(useForegroundService: Boolean) {
            synchronized(this) {
                if (temporaryRecording != null) {
                    return
                }

                // If a ride was already getting recorded, continue this recording
                try {
                    if (RideRecordingRepository[app].recordingFile?.exists() == true) {
                        val line =
                            RideRecordingRepository[app].recordingFile!!.inputStream()
                                .bufferedReader().readLine()

                        if (line.startsWith(DATE_MARKER)) {
                            rideStartDate = Date(line.substring(1).toLong())
                        }
                    }
                } catch (e: Exception) {
                    DebugLog.e(
                        TAG,
                        "Cannot parse date from ${RideRecordingRepository[app].recordingFile}"
                    )
                }

                usesForegroundService = useForegroundService
                rideDistance = 0f
                rideStartLocation = null
                if (rideStartDate == null) {
                    // New recording
                    RideRecordingRepository[app].recordingId = System.currentTimeMillis()

                    rideStartDate = Date()
                    RideRecordingRepository[app].recordingFile!!.outputStream()
                        .bufferedWriter().use {
                            it.write("$DATE_MARKER${rideStartDate!!.time}\n")
                        }

                    DebugLog.i(
                        TAG,
                        "Starting new ride ${RideRecordingRepository[app].recordingId}"
                    )
                } else {
                    // Otherwise extract rideDistance and rideStartLocation from the existing data
                    RideRecordingRepository[app].recordingFile!!.forEachRecording()
                        .forEach { segment ->
                            var lastLocation = segment[0]

                            if (rideStartLocation == null) {
                                rideStartLocation = lastLocation
                            }

                            if (segment.size > 1) {
                                segment.subList(1, segment.size - 1).forEach {
                                    rideDistance += lastLocation.distanceTo(it)
                                    lastLocation = it
                                }
                            }
                        }

                    DebugLog.i(
                        TAG,
                        "Resuming ride ${RideRecordingRepository[app].recordingId}: " +
                                "date=$rideStartDate, distance=$rideDistance, startLoc=$rideStartLocation"
                    )
                }

                temporaryRecording =
                    FileOutputStream(RideRecordingRepository[app].recordingFile!!, true)
                        .bufferedWriter()

                // Add new segment marker
                temporaryRecording!!.apply {
                    write("$NEW_SEGMENT_MARKER\n")
                    flush()
                }

                recordingJob = recorderScope.launch {
                    launch {
                        LocationRepository[app].rawLocation.collect {
                            locationObserver.onChanged(it)
                        }
                    }
                    launch {
                        PhoneStateRepository[app].date.collect {
                            dateObserver.onChanged(it)
                        }
                    }
                    launch {
                        RideRecordingRepository[app].rideTime.collect {
                            rideTimeObserver.onChanged(it)
                        }
                    }
                }
                application.registerReceiver(shutdownReceiver, IntentFilter(ACTION_SHUTDOWN))

                if (usesForegroundService) {
                    startForeground(
                        NotificationIds.RIDE_RECORDING.ordinal,
                        getNotification(Duration(0L))
                    )
                }

                notifyClients()
            }
        }

        fun pauseRecording() {
            recorderScope.launch(Main) {
                pauseRecordingSync()
            }
        }

        /** Execute procedure to pause recording ride on this thread */
        private fun pauseRecordingSync(notifyAndStop: Boolean = true) {
            synchronized(this) {
                if (temporaryRecording == null) {
                    return
                }

                DebugLog.i(TAG, "Pausing ride ${RideRecordingRepository[app].recordingId}")

                recordingJob?.cancel()
                application.unregisterReceiver(shutdownReceiver)

                temporaryRecording = null

                rideStartDate = null
                rideStartLocation = null

                if (usesForegroundService) {
                    ServiceCompat.stopForeground(
                        this@RideRecorder,
                        ServiceCompat.STOP_FOREGROUND_REMOVE
                    )
                    getSystemService(NotificationManager::class.java)
                        .cancel(NotificationIds.RIDE_RECORDING.ordinal)
                }

                if (notifyAndStop) {
                    notifyClients()
                    stopSelf()
                }
            }
        }

        override fun stopRecording() {
            recorderScope.launch(Main) {
                stopRecordingSync()
            }
        }

        /** Execute procedure to stop recording ride on this thread */
        private fun stopRecordingSync() {
            synchronized(this) {
                if (temporaryRecording == null) {
                    return
                }

                pauseRecordingSync(false)

                DebugLog.i(TAG, "Stopping ride ${RideRecordingRepository[app].recordingId}")

                File(filesDir, RECORDINGS_DIRECTORY).mkdirs()

                // Convert temporary recording into gpx.zip
                File(
                    filesDir,
                    "${PreviousRide.RECORDING_FILE_NAME_PREFIX}${RideRecordingRepository[app].recordingId}.gpx.zip"
                ).apply {
                    outputStream().buffered().use { fileOs ->
                        ZipOutputStream(fileOs).use { zipOs ->
                            zipOs.putNextEntry(ZipEntry(TRACK_ZIP_ENTRY))

                            GpxSerializer(zipOs, null).use { gpxWriter ->
                                RideRecordingRepository[app].recordingFile!!.forEachRecording()
                                    .forEachIndexed { i, segment ->
                                        if (i != 0) {
                                            gpxWriter.newSegment()
                                        }

                                        segment.forEach {
                                            gpxWriter.addPoint(it)
                                        }
                                    }
                            }

                            zipOs.closeEntry()
                        }
                    }

                    // Move finished file into recordings dir
                    renameTo(File(File(filesDir, RECORDINGS_DIRECTORY), name))
                }

                // Finish this recording and delete temporary data
                RideRecordingRepository[app].recordingId = null

                notifyClients()
                stopSelf()
            }
        }

        fun onDestroy() {
            pauseRecordingSync()

            recorderScope.cancel()
        }
    }

    companion object {
        const val EXTRA_USE_FOREGROUND_SERVICE = "EXTRA_USE_FOREGROUND_SERVICE"

        fun getIntent(context: Context): Intent {
            return Intent(context, RideRecorder::class.java)
        }
    }
}