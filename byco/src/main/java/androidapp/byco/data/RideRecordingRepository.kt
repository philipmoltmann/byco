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

import android.content.ComponentName
import android.content.Context.BIND_AUTO_CREATE
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.util.Log
import androidapp.byco.BycoApplication
import androidapp.byco.background.IRideClient
import androidapp.byco.background.IRideRecorder
import androidapp.byco.background.RideRecorder
import androidapp.byco.util.Repository
import androidapp.byco.util.SingleParameterSingletonOf
import androidapp.byco.util.stateIn
import androidx.annotation.MainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import lib.gpx.BasicLocation
import lib.gpx.Distance
import lib.gpx.Duration
import java.io.File

/** State and method for a ongoing recording */
class RideRecordingRepository internal constructor(
    private val app: BycoApplication,
) : Repository(app) {
    private val TAG = RideRecordingRepository::class.java.simpleName

    private val ONGOING_RECORDING_FILE = "ongoing-recording-"

    /** Scope used to keep track of [onService] commands */
    private var serviceCommandScope = CoroutineScope(Job())

    /**
     * Id of the recording currently in progress, extracted from the ONGOING_RECORDING_FILE-name.
     * Set to `null` to end recording.
     */
    var recordingId: Long?
        get() {
            return try {
                app.filesDir.list()!!.first {
                    it.startsWith(ONGOING_RECORDING_FILE)
                }.splitToSequence("-").last().toLong()
            } catch (e: NoSuchElementException) {
                null
            }
        }
        set(value) {
            // There can only ever be one recording, hence delete all older ones.
            app.filesDir.list()!!.filter { it.startsWith(ONGOING_RECORDING_FILE) }
                .forEach { File(app.filesDir, it).delete() }

            if (value != null) {
                File(app.filesDir, "$ONGOING_RECORDING_FILE$value").createNewFile()
            }
        }

    /** File containing the ongoing recording */
    val recordingFile: File?
        get() = recordingId?.let { File(app.filesDir, "$ONGOING_RECORDING_FILE$it") }

    private val serviceData = callbackFlow {
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, newService: IBinder) {
                try {
                    IRideRecorder.Stub.asInterface(newService).also {
                        it.registerClient(object : IRideClient.Stub() {
                            override fun onRideChanged(
                                isRideBeingRecorded: Boolean, rideTime: Duration?,
                                rideStart: BasicLocation?, distance: Distance?
                            ) {
                                launch {
                                    if (isRideBeingRecorded) {
                                        send(Triple(rideTime, rideStart, distance))
                                    } else {
                                        send(Triple(null, null, null))
                                    }
                                }
                            }
                        })
                    }
                } catch (e: DeadObjectException) {
                    Log.e(TAG, "Could not connect to service", e)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // empty
            }
        }
        app.bindService(RideRecorder.getIntent(app), serviceConnection, BIND_AUTO_CREATE)

        awaitClose { app.unbindService(serviceConnection) }
    }.stateIn(Triple(null, null, null))

    /** How long it the recording already on going? */
    val rideTime = serviceData.map { (duration, _, _) -> duration }.distinctUntilChanged().onEach {
        if (it == null) {
            // A recording just stopped, trigger update.
            PreviousRidesRepository[app].previousRidesUpdateTrigger.trigger()
        }
    }.stateIn(null)

    /** Where did the ride start */
    val rideStart = serviceData.map { (_, start, _) -> start }.stateIn(null)

    /** How much distance of ride has been recorded */
    // val rideDistance = serviceData.map { (_, _, distance) -> distance }

    /** Is a ride currently be recorded */
    val isRideBeingRecorded =
        rideTime.map { it != null }.stateIn(recordingId != null)

    private fun onService(block: IRideRecorder.() -> (Unit)) {
        // Make a copy of the service scope at the time the onService method was called
        val scope = serviceCommandScope

        app.bindService(RideRecorder.getIntent(app), object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                scope.launch(Main.immediate) {
                    block(IRideRecorder.Stub.asInterface(service))
                }
                app.unbindService(this)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // ignore
            }
        }, BIND_AUTO_CREATE)
    }

    @MainThread
    fun startRecording(useForegroundService: Boolean) {
        // Cancel all in flight service commands
        serviceCommandScope.cancel()

        app.startService(
            RideRecorder.getIntent(app)
                .putExtra(RideRecorder.EXTRA_USE_FOREGROUND_SERVICE, useForegroundService)
        )

        // Create scope for future commands
        serviceCommandScope = CoroutineScope(Job())
    }

    fun stopRecording() {
        onService { stopRecording() }
    }

    companion object :
        SingleParameterSingletonOf<BycoApplication, RideRecordingRepository>({
            RideRecordingRepository(
                it
            )
        })
}