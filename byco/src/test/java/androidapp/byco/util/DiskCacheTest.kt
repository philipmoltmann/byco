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

import com.google.common.truth.Truth.assertThat
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.Serializable

class DiskCacheTest {
    private val testScope = CoroutineScope(Job())
    private val cacheLocation = File("testData", "cache")

    private val retreiver: suspend CoroutineScope.(Int) -> Long = spyk()
    private val writer: suspend CoroutineScope.(OutputStream, Int, Long) -> Unit =
        { out, key, value ->
            DataOutputStream(out).use {
                it.writeInt(key)
                it.writeLong(value)
            }
        }
    private val reader: suspend CoroutineScope.(InputStream) -> (Pair<Int, Long>) = { ins ->
        DataInputStream(ins).use {
            it.readInt() to it.readLong()
        }
    }
    private val cache = diskCacheOf(cacheLocation, 100000, retreiver, writer, reader)

    @Before
    fun setUpRetreiver() {
        coEvery { retreiver(any(), any()) } returns 23
    }

    @Before
    @After
    fun clearCache() {
        cacheLocation.deleteRecursively()
    }

    @After
    fun cancelTestCoroutines() {
        testScope.cancel()
    }

    @Test
    fun valuesGetCached() {
        runBlocking {
            val cachedVal = cache.get(1)

            assertThat(cache.get(1)).isEqualTo(cachedVal)
            coVerify(exactly = 1) { retreiver(any(), 1) }
        }
    }

    @Test
    fun newValueGetsReturned() {
        runBlocking {
            coEvery { retreiver(any(), 1) } returns 42
            assertThat(cache.get(1)).isEqualTo(42)
        }
    }

    @Test
    fun newValueGetsResolved() {
        runBlocking {
            cache.get(1)
            coVerify { retreiver(any(), 1) }
        }
    }

    @Test
    fun differentKeysReferToDifferentValues() {
        runBlocking {
            cache.get(1)

            coEvery { retreiver(any(), 2) } returns 42

            assertThat(cache.get(2)).isEqualTo(42)
            coVerify { retreiver(any(), 2) }
        }
    }

    @Test
    fun sameHashCodeDoesNotCauseConflictsIfKeysAreNotEqual() {
        class Key(val key: Int) : Serializable {
            override fun hashCode(): Int {
                return 0
            }

            override fun equals(other: Any?): Boolean {
                if (other == null || other !is Key) {
                    return false
                }

                return other.key == key
            }
        }

        val retreiver: suspend CoroutineScope.(Key) -> Long = spyk()
        coEvery { retreiver(any(), any()) } returns 23
        val writer: suspend CoroutineScope.(OutputStream, Key, Long) -> Unit = { out, key, value ->
            DataOutputStream(out).use {
                it.writeInt(key.key)
                it.writeLong(value)
            }
        }
        val reader: suspend CoroutineScope.(InputStream) -> (Pair<Key, Long>) = { ins ->
            DataInputStream(ins).use {
                Key(it.readInt()) to it.readLong()
            }
        }
        val cache = diskCacheOf(cacheLocation, 100000, retreiver, writer, reader)

        val key2 = Key(2)
        runBlocking {
            cache.get(Key(1))

            coEvery { retreiver(any(), key2) } returns 42

            assertThat(cache.get(key2)).isEqualTo(42)
            coVerify { retreiver(any(), key2) }
        }
    }

    @Test
    fun clearRemovesValues() {
        runBlocking {
            cache.get(1)

            cache.clear()
            clearAllMocks()
            coEvery { retreiver(any(), 1) } returns 42

            assertThat(cache.get(1)).isEqualTo(42)
            coVerify { retreiver(any(), 1) }
        }
    }

    @Test
    fun parallelGetCachedForSameKey() {
        val val1 = testScope.async { cache.get(1) }
        val val2 = testScope.async { cache.get(1) }

        runBlocking {
            assertThat(val1.await()).isEqualTo(val2.await())

            coVerify(exactly = 1) { retreiver(any(), any()) }
        }
    }

    @Test
    fun parallelGetCachedForDifferentKeys() {
        val val1 = testScope.async { cache.get(1) }
        val val2 = testScope.async { cache.get(2) }

        runBlocking {
            val1.await()
            val2.await()

            coVerify(exactly = 2) { retreiver(any(), any()) }
        }
    }

    @Test
    fun staleDataGetsReturned() {
        val cacheWithLowMaxAge = diskCacheOf(cacheLocation, 1, retreiver, writer, reader)

        runBlocking {
            cacheWithLowMaxAge.get(1)

            withContext(Dispatchers.IO) {
                Thread.sleep(100)
            }

            clearAllMocks()
            coEvery { retreiver(any(), 1) } returns 42

            // Stale value should be returned
            assertThat(cacheWithLowMaxAge.get(1)).isEqualTo(23)
        }
    }

    @Test
    fun staleDataGetsUpdated() {
        val cacheWithLowMaxAge = diskCacheOf(cacheLocation, 50, retreiver, writer, reader)

        runBlocking {
            cacheWithLowMaxAge.get(1)

            withContext(Dispatchers.IO) {
                Thread.sleep(100)
            }

            clearAllMocks()
            coEvery { retreiver(any(), 1) } returns 42

            // Read stale value -> should trigger an async update of value
            cacheWithLowMaxAge.get(1)
            // Async code should update value
            coVerify(timeout = 25) { retreiver(any(), 1) }

            // New value should be returned and not be re-retrieved
            clearAllMocks(answers = false)
            assertThat(cacheWithLowMaxAge.get(1)).isEqualTo(42)
            // No additional calls to retriever
            coVerify(exactly = 0) { retreiver(any(), 1) }
        }
    }

    @Test
    fun maxCacheAgeCanBeNull() {
        diskCacheOf(cacheLocation, 0, retreiver, writer, reader)
    }

    @Test(expected = IllegalArgumentException::class)
    fun maxCacheAgeCannotBeNegative() {
        diskCacheOf(cacheLocation, -1, retreiver, writer, reader)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cacheLocationCannotBeFile() {
        File("testData").mkdir()
        val badLocation = File("testData", "aFile")

        badLocation.createNewFile()
        try {
            diskCacheOf(badLocation, 1, retreiver, writer, reader)
        } finally {
            badLocation.delete()
        }
    }
}