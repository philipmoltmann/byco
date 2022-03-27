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

import android.text.format.DateFormat
import android.util.Xml
import androidapp.byco.lib.BuildConfig
import lib.gpx.*
import java.io.OutputStream
import java.util.*

/**
 * Serialize ride data to an output stream according to GPX format
 *
 * @see GpxParser
 */
class GpxSerializer(
    os: OutputStream,
    private val name: String?,
    private val time: Long? = System.currentTimeMillis()
): AutoCloseable {
    private val usLocale = Locale.US
    private val utcCalendar = Calendar.getInstance(UTC)
    private val writer = os.bufferedWriter(UTF8)
    private val xml = Xml.newSerializer().apply {
        setOutput(writer)
    }

    private fun start(tag: String, ns: String? = null) {
        xml.startTag(ns, tag)
    }

    private fun end(tag: String, ns: String? = null) {
        xml.endTag(ns, tag)
    }

    private fun tag(tag: String, ns: String? = null, block: () -> (Unit)) {
        start(tag, ns)
        block()
        end(tag, ns)
    }

    private fun attr(name: String, value: String, ns: String? = null) {
        xml.attribute(ns, name, value)
    }

    private fun text(text: String) {
        xml.text(text)
    }

    private fun time(time: Long) {
        tag(TIME_TAG) {
            text(
                DateFormat.format(DATE_FORMAT, utcCalendar.apply { timeInMillis = time }).toString()
            )
        }
    }

    init {
        xml.startDocument(UTF8.displayName(), false)

        start(GPX_TAG)
        attr(GPX_NS_ATTR, GPX_NS)
        attr(GPX_SCHEMA_INSTANCE_ATTR, GPX_SCHEMA_INSTANCE)
        attr(GPX_SCHEMA_LOCATION_ATTR, GPX_SCHEMA_LOCATION)
        attr(CREATOR_ATTR, BuildConfig.LIBRARY_PACKAGE_NAME)
        attr(VERSION_ATTR, GPX_SCHEMA_VERSION)

        if (time != null) {
            tag(METADATA_TAG) {
                time(time)
            }
        }

        start(TRK_TAG)

        if (name != null) {
            tag(NAME_TAG) {
                text(name)
            }
        }

        start(TRKSEG_TAG)
    }

    /** Add a new location to the ride */
    fun addPoint(location: RecordedLocation) {
        tag(TRKPT_TAG) {
            attr(LAT_ATTR, "%.6f".format(usLocale, location.latitude))
            attr(LON_ATTR, "%.6f".format(usLocale, location.longitude))
            tag(ELE_TAG) {
                text("%.2f".format(usLocale, location.elevation))
            }
            location.time?.let { time ->
                time(time)
            }
        }
    }

    /** Start a new sub-track inside the ride (e.g. after Location was lost for long time) */
    fun newSegment() {
        end(TRKSEG_TAG)
        start(TRKSEG_TAG)
    }

    override fun close() {
        end(TRKSEG_TAG)
        end(TRK_TAG)
        end(GPX_TAG)

        xml.endDocument()
        xml.flush()
        writer.flush()
    }
}