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

package androidapp.byco.util

import android.os.FileObserver
import androidx.annotation.GuardedBy
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.LiveData
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import lib.gpx.DebugLog
import java.io.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.random.Random

/**
 * Generic on disk cache.
 *
 * Cache can contain ``[``key, value``]`` pairs of any serializable type.
 *
 * @param location directory the cache lives in. Deleting the dir during live-time of the cache
 * causes unspecified behavior. Use [clear] instead.
 * @param maxCacheAgeMs The maximum age of cached data (in milliseconds)
 * @param computer Get new values
 * @param writer Store values to disk
 * @param reader Read values from disk
 */
class DiskCache<K, V>(
    private val location: File,
    private val maxCacheAgeMs: Long,
    val numParallelLockedKeys: Int = 16,
    private val computer: suspend CoroutineScope.(K) -> V,
    private val writer: suspend CoroutineScope.(OutputStream, K, V) -> Unit,
    private val reader: suspend CoroutineScope.(InputStream) -> (Pair<K, V>),
) {
    private val TAG = DiskCache::class.java.simpleName

    class InvalidCachedDataFileException(message: String = "", cause: java.lang.Exception? = null) :
        Exception(message, cause) {
        constructor(cause: java.lang.Exception) : this("", cause)
    }

    /** Synchronize this class */
    private val paralellismLimiter = Semaphore(numParallelLockedKeys)
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()

    /** currently locked keys */
    @GuardedBy("lock")
    private val lockedKeys = mutableSetOf<K>()

    /** should all keys be locked */
    @GuardedBy("lock")
    private var allLockRequested = false

    init {
        if (maxCacheAgeMs < 0) {
            throw IllegalArgumentException("maxCacheAgeMs must be >= 0")
        }

        if (location.exists() && !location.isDirectory) {
            throw IllegalArgumentException("location must be a directory")
        }
    }

    /**
     * Always grab a lockKey before accessing data with key. These locks are not fair, but super
     * granular.
     *
     * TODO: Figure out a way to suspend when waiting
     */
    private fun withKeyLocked(key: K, skipIfLocked: Boolean = false) = object : AutoCloseable {
        init {
            lock.withLock {
                while (allLockRequested || lockedKeys.contains(key)) {
                    if (skipIfLocked) {
                        throw Exception("Already locked")
                    }
                    cond.await()
                }
                lockedKeys.add(key)
            }
        }

        override fun close() {
            lock.withLock {
                lockedKeys.remove(key)
                cond.signal()
            }
        }
    }

    /**
     * Clear all data from cache.
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    @VisibleForTesting
    suspend fun clear() {
        withContext(IO) {
            lock.withLock {
                while (allLockRequested) {
                    cond.await()
                }

                allLockRequested = true

                try {
                    // Wait until all individual lock holders are gone
                    while (lockedKeys.isNotEmpty()) {
                        cond.await()
                    }
                } catch (t: Throwable) {
                    lock.withLock {
                        allLockRequested = false
                        cond.signal()
                    }

                    throw t
                }
            }

            try {
                location.listFiles()?.forEach { cachedDataDir ->
                    cachedDataDir.deleteRecursively()
                }
            } finally {
                lock.withLock {
                    allLockRequested = false
                    cond.signal()
                }
            }
        }
    }

    /**
     * Most likely load the value for the key into the cache.
     *
     * This does not guarantee that the data is loaded as [key]-hashcode collisions are treated as
     * already loaded values.
     *
     * @return the cached or new value
     */
    suspend fun preFetch(key: K, skipIfLocked: Boolean = false) {
        if (File(location, key.hashCode().toString()).exists()) {
            return
        }

        while (true) {
            val v = get(key, skipIfLocked)
            if (v != null) {
                break
            } else {
                delay(abs(Random.nextLong()) % 1000)
            }
        }
    }

    /**
     * [LiveData] whether the data for a certain key is very likely in the cache. This is not a 100%
     * guarantee, but very likely.
     */
    fun hasData(key: K) = object : AsyncLiveData<Boolean>(dispatcher = IO) {
        // Support API23
        @Suppress("DEPRECATION")
        private val directoryObserver =
            object : FileObserver(location.absolutePath) {
                override fun onEvent(event: Int, path: String?) {
                    if (event in setOf(CLOSE_WRITE, MOVED_TO, MOVED_FROM, DELETE)) {
                        requestUpdate()
                    }
                }
            }

        override fun onActive() {
            super.onActive()

            directoryObserver.startWatching()
            requestUpdate()
        }

        override fun onInactive() {
            super.onInactive()

            directoryObserver.stopWatching()
        }

        override suspend fun update(): Boolean {
            return withKeyLocked(key).use {
                File(location, key.hashCode().toString()).exists()
            }
        }
    }

    /**
     * Get cached value of [key] or retrieve and cache a new value via [computer].
     *
     * @return the cached or new value (`null` if isCancelable and canceled)
     */
    suspend fun get(key: K, skipIfLocked: Boolean = false): V? {
        val cachedDataFileDir = File(location, key.hashCode().toString())

        @Suppress("BlockingMethodInNonBlockingContext")
        return withContext(IO) {
            withKeyLocked(key, skipIfLocked).use {
                if (cachedDataFileDir.exists()) {
                    try {
                        cachedDataFileDir.listFiles()?.forEach { candidateFile ->
                            try {
                                candidateFile.inputStream().buffered().use { candidateStream ->
                                    val (candidateKey, value) = reader(candidateStream)

                                    if (candidateKey == key) {
                                        return@withContext value
                                    }
                                }
                            } catch (e: InvalidCachedDataFileException) {
                                DebugLog.e(TAG, "Invalid cached data ${candidateFile.path}", e)
                                candidateFile.delete()
                            }
                        }
                    } finally {
                        // delete old data _after_ reading them and instantly trigger a prefetch
                        // This way is should be less likely to see stale data
                        if (cachedDataFileDir.lastModified() + maxCacheAgeMs
                            < System.currentTimeMillis()
                        ) {
                            cachedDataFileDir.deleteRecursively()

                            // Reload deleted data
                            launch(IO) { preFetch(key) }
                        }
                    }
                }

                val newValue = paralellismLimiter.withPermit {
                    computer(key)
                }
                cachedDataFileDir.mkdirs()

                File.createTempFile(
                    "cached-",
                    ".dat",
                    cachedDataFileDir
                ).outputStream().buffered().use { newStream ->
                    writer(newStream, key, newValue)
                }

                newValue
            }
        }
    }
}

fun <K, V> diskCacheOf(
    location: File,
    maxCacheAgeMs: Long,
    retriever: suspend CoroutineScope.(K) -> V,
    writer: suspend CoroutineScope.(OutputStream, K, V) -> Unit,
    reader: suspend CoroutineScope.(InputStream) -> (Pair<K, V>),
) = DiskCache(location, maxCacheAgeMs, 16, retriever, writer, reader)

