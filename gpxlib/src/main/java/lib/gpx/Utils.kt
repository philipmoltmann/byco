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

import java.nio.charset.Charset
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

val UTC: TimeZone = TimeZone.getTimeZone("UTC")
val UTF8: Charset = Charset.forName("UTF-8")

/** Map through all entries of a [ZipInputStream] */
inline fun <V> ZipInputStream.findFirstNotNull(block: ZipInputStream.(ZipEntry) -> (V)): V? {
    var entry = nextEntry
    while (entry != null) {
        val v = block(entry)
        if (v != null) {
            return v
        }

        try {
            entry = nextEntry
        } catch (e: Exception) {
            break
        }
    }

    return null
}
