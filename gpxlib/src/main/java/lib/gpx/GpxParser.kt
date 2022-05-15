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

package lib.gpx

import android.annotation.SuppressLint
import android.util.Xml
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParser.*
import java.io.EOFException
import java.io.InputStream
import java.text.ParseException
import java.text.SimpleDateFormat
import kotlin.coroutines.coroutineContext

/**
 * Parse GPX files into [Track]s
 */
class GpxParser(private val ins: InputStream) {
    private val TAG = GpxParser::class.java.simpleName

    @SuppressLint("SimpleDateFormat")
    private val dateFormat = SimpleDateFormat(DATE_FORMAT).apply { timeZone = UTC }

    /** Locations of the ride */
    var track: Track? = null

    /** Name of the ride */
    var name: String? = null

    /** Date the ride claims to have been recorded */
    var time: Long? = null

    suspend fun parse() {
        withContext(IO) {
            val xml = Xml.newPullParser().apply {
                setInput(ins.bufferedReader(UTF8))
            }

            do {
                when (xml.eventType) {
                    START_DOCUMENT ->
                        xml.parseTag {
                            when (xml.eventType) {
                                START_TAG -> when (xml.name) {
                                    GPX_TAG -> xml.parseGpx()
                                }
                            }
                        }
                }

                try {
                    xml.next()
                } catch (e: EOFException) {
                    DebugLog.w(TAG, "Unexpected EOF in $ins")
                    break
                }
            } while (xml.eventType != END_DOCUMENT)
        }
    }

    private inline fun XmlPullParser.parseTag(block: () -> (Unit)) {
        val startDepth = depth
        do {
            try {
                next()
            } catch (e: EOFException) {
                DebugLog.w(TAG, "Unexpected EOF in $ins")
                break
            }
            block()
        } while (!(depth == startDepth && eventType == END_TAG)
            && eventType != END_DOCUMENT
        )
    }

    private suspend fun XmlPullParser.parseGpx() {
        parseTag {
            when (eventType) {
                START_TAG -> when (name) {
                    METADATA_TAG -> time = parseMetaData()
                    TIME_TAG -> time = parseTime() // not standard compliant but apparently used
                    TRK_TAG -> {
                        val (n, t) = parseTrack()
                        this@GpxParser.name = n
                        track = t
                    }
                }
            }
        }
    }

    private fun XmlPullParser.parseMetaData(): Long? {
        var time: Long? = null

        parseTag {
            when (eventType) {
                START_TAG -> when (name) {
                    TIME_TAG -> time = parseTime()
                }
            }
        }

        return time
    }

    private fun XmlPullParser.parseTime(): Long? {
        var time: Long? = null

        parseTag {
            when (eventType) {
                TEXT -> text?.let {
                    try {
                        // Surprisingly this is by far the most expensive operation
                        time = dateFormat.parse(it)?.time
                    } catch (e: ParseException) {
                        DebugLog.w(TAG, "Cannot parse time at $ins:$positionDescription", e)
                    }
                }
            }
        }

        return time
    }

    private suspend fun XmlPullParser.parseTrack(): Pair<String?, Track> {
        var n: String? = null
        val segments = mutableListOf<List<RecordedLocation>>()

        parseTag {
            when (eventType) {
                START_TAG -> when (name) {
                    NAME_TAG -> n = parseName()
                    TRKSEG_TAG -> segments += parseTrackSeg()
                }
            }
        }

        return Pair(n, Track(segments))
    }

    private fun XmlPullParser.parseName(): String? {
        var name: String? = null

        parseTag {
            when (eventType) {
                TEXT -> text?.let { name = it }
            }
        }

        return name
    }

    private suspend fun XmlPullParser.parseTrackSeg(): List<RecordedLocation> {
        val trackSeg = mutableListOf<RecordedLocation>()

        parseTag {
            when (eventType) {
                START_TAG -> {
                    coroutineContext.ensureActive()

                    when (name) {
                        TRKPT_TAG -> parseTrackPt()?.let { trackSeg += it }
                    }
                }
            }
        }

        return trackSeg
    }

    private fun XmlPullParser.parseTrackPt(): RecordedLocation? {
        try {
            val lat = getAttributeValue(null, LAT_ATTR).toDouble()
            val lon = getAttributeValue(null, LON_ATTR).toDouble()

            var readTime: Long? = null
            var ele: Double? = null

            parseTag {
                when (eventType) {
                    START_TAG -> when (name) {
                        ELE_TAG -> ele = parseElevation()
                        TIME_TAG -> readTime = parseTime()
                    }
                }
            }

            return RecordedLocation(lat, lon, ele, readTime)
        } catch (e: NumberFormatException) {
            DebugLog.e(TAG, "Cannot parse number in '$TRKPT_TAG' at $ins:$positionDescription", e)
            return null
        }
    }

    private fun XmlPullParser.parseElevation(): Double? {
        var ele: Double? = null

        parseTag {
            when (eventType) {
                TEXT -> try {
                    ele = text.toDouble()
                } catch (e: java.lang.NumberFormatException) {
                    DebugLog.e(
                        TAG,
                        "Cannot parse '$ELE_TAG' as double at $ins:$positionDescription",
                        e
                    )
                }
            }
        }

        return ele
    }
}