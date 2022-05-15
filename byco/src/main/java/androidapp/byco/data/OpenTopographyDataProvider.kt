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
import android.content.pm.PackageManager
import android.util.Log
import androidapp.byco.util.SingleParameterSingletonOf
import androidapp.byco.util.compat.getApplicationInfoCompat
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Default
import lib.gpx.DebugLog
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.Reader
import java.math.BigDecimal
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/** Data source for [OpenTopography](https://opentopography.org/) data */
class OpenTopographyDataProvider private constructor(private val app: Application) {
    private val MAX_CONNECT_TIMEOUT: Long = 5_000
    val READ_TIMEOUT: Long = 30_000
    private val DATA_SOURCE = "https://portal.opentopography.org/API"

    private val TAG = OpenTopographyDataProvider::class.java.simpleName

    /**
     * OpenTopography URL to get elevations of a certain area
     */
    private fun getQuery(
        minLat: BigDecimal,
        minLon: BigDecimal,
        maxLat: BigDecimal,
        maxLon: BigDecimal
    ): String {
        return "$DATA_SOURCE/globaldem?demtype=NASADEM&south=$minLat&north=$maxLat&west=$minLon" +
                "&east=$maxLon&outputFormat=AAIGrid&API_Key=${app.packageManager.getApplicationInfoCompat(app.packageName, PackageManager.GET_META_DATA).metaData.getString("otk")!!.reversed()}"
    }

    /** Get raw OpenTopography data for a certain area. */
    private suspend fun getOpenTopographyData(
        minLat: BigDecimal,
        minLon: BigDecimal,
        maxLat: BigDecimal,
        maxLon: BigDecimal,
    ): String {
        return withContext(Dispatchers.IO) {
            var data: String? = null
            var incrementalBackoffTimeout = 20L

            while (data == null) {
                val urlConnection =
                    (URL(getQuery(minLat, minLon, maxLat, maxLon))
                        .openConnection() as HttpsURLConnection).apply {
                        doOutput = false
                        connectTimeout = MAX_CONNECT_TIMEOUT.toInt()
                        readTimeout = READ_TIMEOUT.toInt()
                    }

                try {
                    urlConnection.connect()

                    try {
                        data = urlConnection.inputStream.bufferedReader(Charsets.UTF_8)
                            .use { it.readText() }
                    } finally {
                        urlConnection.disconnect()
                    }
                } catch (e: IOException) {
                    if (!coroutineContext.isActive) {
                        Log.e(TAG, "Cannot communicate with $DATA_SOURCE; obsolete", e)

                        throw e
                    } else {
                        incrementalBackoffTimeout = min(
                            MAX_CONNECT_TIMEOUT,
                            incrementalBackoffTimeout * (1 + abs(Random.nextInt() % 3))
                        )

                        DebugLog.w(
                            TAG,
                            "Cannot communicate with $DATA_SOURCE($minLat, $minLon); will retry "
                                    + "in $incrementalBackoffTimeout ms: ${e.message}. "
                        )

                        delay(incrementalBackoffTimeout)
                    }
                } finally {
                    try {
                        urlConnection.inputStream.close()
                    } catch (_: IOException) {
                    }
                }
            }

            data
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    suspend fun getParsedOpenTopographyData(
        minLat: BigDecimal,
        minLon: BigDecimal,
        maxLat: BigDecimal,
        maxLon: BigDecimal,
    ): ParsedOpenTopographyData {
        return getOpenTopographyData(
            minLat,
            minLon,
            maxLat,
            maxLon
        ).reader().use { reader ->
            withContext(Default) {
                reader.parseQueryResult()
            }
        }
    }

    private fun Reader.parseQueryResult(): ParsedOpenTopographyData {
        var xllcorner = 0.0
        var yllcorner = 0.0
        var cellsize = 0.0
        var NODATA_value = Short.MIN_VALUE
        val elevations = mutableListOf<ShortArray>()

        forEachLine { line ->
            when (line.substringBefore(' ')) {
                "ncols", "nrows" -> {
                } // ignore
                "xllcorner" -> xllcorner = line.substringAfterLast(' ').toDouble()
                "yllcorner" -> yllcorner = line.substringAfterLast(' ').toDouble()
                "cellsize" -> cellsize = line.substringAfterLast(' ').toDouble()
                "NODATA_value" -> NODATA_value = line.substringAfterLast(' ').toShort()
                "" -> elevations.add(line.split(' ').mapNotNull {
                    try {
                        it.toShort()
                    } catch (e: NumberFormatException) {
                        null
                    }
                }.toShortArray())
            }
        }

        return ParsedOpenTopographyData(
            yllcorner,
            xllcorner,
            cellsize,
            NODATA_value,
            elevations.toTypedArray()
        )
    }

    companion object :
        SingleParameterSingletonOf<Application, OpenTopographyDataProvider>({ OpenTopographyDataProvider(it) })
}

/** A chunk of parsed OpenTopography data */
class ParsedOpenTopographyData(
    val minLat: Double,
    val minLon: Double,
    val cellSize: Double,
    val noDataValue: Short,
    val elevations: Array<ShortArray>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }

        if (javaClass != other?.javaClass) {
            return false
        }

        other as ParsedOpenTopographyData

        return minLat == other.minLat &&
                minLon == other.minLon &&
                cellSize == other.cellSize &&
                elevations.size == other.elevations.size &&
                elevations.getOrNull(0)?.size == other.elevations.getOrNull(0)?.size
    }

    override fun hashCode(): Int {
        var result = minLat.hashCode()
        result = 31 * result + minLon.hashCode()
        result = 31 * result + cellSize.hashCode()
        result = 31 * result + elevations.size
        result = 31 * result + (elevations.getOrNull(0)?.size ?: 0)

        return result
    }
}

@Suppress("BlockingMethodInNonBlockingContext")
internal suspend fun DataInputStream.readParsedOpenTopographyData(): ParsedOpenTopographyData {
    return ParsedOpenTopographyData(
        readDouble(),
        readDouble(),
        readDouble(),
        readShort(),
        Array(readInt()) {
            coroutineContext.ensureActive()

            ShortArray(readInt()) {
                readShort()
            }
        }
    )
}

@Suppress("BlockingMethodInNonBlockingContext")
internal suspend fun DataOutputStream.writeParsedOpenTopographyData(data: ParsedOpenTopographyData) {
    writeDouble(data.minLat)
    writeDouble(data.minLon)
    writeDouble(data.cellSize)
    writeShort(data.noDataValue.toInt())

    writeInt(data.elevations.size)
    data.elevations.forEach { line ->
        coroutineContext.ensureActive()

        writeInt(line.size)
        line.forEach { ele ->
            writeShort(ele.toInt())
        }
    }
}