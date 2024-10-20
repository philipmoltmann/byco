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

import androidx.annotation.GuardedBy
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import lib.gpx.DebugLog
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.random.Random

interface DiskCacheKey {
    fun toDirName(): String
}

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
@OptIn(DelicateCoroutinesApi::class)
class DiskCache<K : DiskCacheKey, V>(
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
    private val lock = Mutex()

    @GuardedBy("lock")
    private val waiters = mutableSetOf<Mutex>()
    private val dataModifiedTrigger = Trigger(GlobalScope)

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


    private suspend fun Mutex.wait(owner: Any? = null) {
        assert(isLocked)
        val waiter = Mutex()
        waiter.lock(waiters)

        waiters.add(waiter)
        try {
            unlock(owner)
            try {
                // Lock waiter again. This blocks unless [signal] has already or will eventually
                // unlock it.
                waiter.lock("wait")
            } finally {
                withContext(NonCancellable) {
                    lock(owner)
                }
            }
        } finally {
            waiters.remove(waiter)
        }
    }

    private fun Mutex.signal() {
        assert(isLocked)

        // Unlock all waiters added in [wait].
        waiters.forEach {
            it.unlock(waiters)
        }
        waiters.clear()
    }

    /**
     * Always grab a lockKey before accessing data with key. These locks are not fair, but super
     * granular.
     */
    private suspend fun <T> withKeyLocked(
        key: K,
        skipIfLocked: Boolean = false,
        block: suspend () -> T
    ): T {
        val lockOwner = Any() to key

        lock.withLock(owner = lockOwner) {
            while (allLockRequested || lockedKeys.contains(key)) {
                if (skipIfLocked) {
                    throw SkippedBecauseLockedException("Already locked")
                }
                lock.wait(owner = lockOwner)
            }

            lockedKeys.add(key)
        }
        try {
            return block()
        } finally {
            withContext(NonCancellable) {
                lock.withLock(owner = lockOwner) {
                    lockedKeys.remove(key)
                    lock.signal()
                }
            }
        }
    }

    class SkippedBecauseLockedException(message: String) : Exception(message)

    /**
     * Clear all data from cache.
     */
    @VisibleForTesting
    suspend fun clear() {
        val clearOwner = Any() to "clear"

        withContext(IO) {
            lock.withLock(owner = clearOwner) {
                while (allLockRequested) {
                    lock.wait(owner = clearOwner)
                }

                allLockRequested = true
                try {
                    // Wait until all individual lock holders are gone
                    while (lockedKeys.isNotEmpty()) {
                        lock.wait(owner = clearOwner)
                    }
                } catch (t: Throwable) {
                    allLockRequested = false
                    lock.signal()

                    throw t
                }
            }

            try {
                location.listFiles()?.forEach { cachedDataDir ->
                    cachedDataDir.deleteRecursively()
                }
            } finally {
                lock.withLock(owner = clearOwner) {
                    allLockRequested = false
                    lock.signal()
                }
            }

            dataModifiedTrigger.trigger()
        }
    }

    /**
     * Most likely load the value for the key into the cache.
     *
     * This does not guarantee that the data is loaded as `key.toDirName` collisions are treated as
     * already loaded values.
     *
     * @return the cached or new value
     */
    suspend fun preFetch(key: K, skipIfLocked: Boolean = false) {
        if (hasData(key).first()) {
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
     * `Flow` whether the data for a certain key is very likely in the cache. This is not a 100%
     * guarantee, but very likely.
     */
    fun hasData(key: K) = dataModifiedTrigger.flow.map {
        File(location, key.toDirName()).exists()
    }

    /**
     * Get cached value of [key] or retrieve and cache a new value via [computer].
     *
     * @return the cached or new value (`null` if isCancelable and canceled)
     */
    suspend fun get(key: K, skipIfLocked: Boolean = false): V? {
        val cachedDataFileDir = File(location, key.toDirName())

        return withContext(IO) {
            withKeyLocked(key, skipIfLocked) {
                if (cachedDataFileDir.exists()) {
                    try {
                        cachedDataFileDir.listFiles()?.forEach { candidateFile ->
                            try {
                                candidateFile.inputStream().buffered().use { candidateStream ->
                                    val (candidateKey, value) = reader(candidateStream)

                                    if (candidateKey == key) {
                                        return@withKeyLocked value
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
                            launch { preFetch(key) }
                        }
                    }
                }

                val newValue = paralellismLimiter.withPermit {
                    computer(key)
                }
                cachedDataFileDir.mkdirs()

                // Don't partially write files.
                withContext(NonCancellable) {
                    File.createTempFile(
                        "cached-",
                        ".dat",
                        cachedDataFileDir
                    ).outputStream().buffered().use { newStream ->
                        writer(newStream, key, newValue)
                    }

                    dataModifiedTrigger.trigger()
                }

                newValue
            }
        }
    }
}

fun <K : DiskCacheKey, V> diskCacheOf(
    location: File,
    maxCacheAgeMs: Long,
    retriever: suspend CoroutineScope.(K) -> V,
    writer: suspend CoroutineScope.(OutputStream, K, V) -> Unit,
    reader: suspend CoroutineScope.(InputStream) -> (Pair<K, V>),
) = DiskCache(location, maxCacheAgeMs, 16, retriever, writer, reader)

