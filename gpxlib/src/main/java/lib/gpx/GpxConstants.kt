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

const val GPX_MIME_TYPE = "application/gpx+xml"
const val GPX_FILE_EXTENSION = ".gpx"
const val GPX_ZIP_FILE_EXTENSION = ".gpx.zip"

const val TRACK_ZIP_ENTRY = "track.gpx"

const val GPX_TAG = "gpx"
const val GPX_NS_ATTR = "xmlns"
const val GPX_NS = "http://www.topografix.com/GPX/1/1"
const val GPX_SCHEMA_INSTANCE_ATTR = "xmlns:xsi"
const val GPX_SCHEMA_INSTANCE = "http://www.w3.org/2001/XMLSchema-instance"
const val GPX_SCHEMA_LOCATION_ATTR = "xsi:schemaLocation"
const val GPX_SCHEMA_LOCATION =
    "http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd"
const val CREATOR_ATTR = "creator"
const val VERSION_ATTR = "version"
const val GPX_SCHEMA_VERSION = "1.1"
const val METADATA_TAG = "metadata"
const val TIME_TAG = "time"
const val TRK_TAG = "trk"
const val NAME_TAG = "name"
const val TRKSEG_TAG = "trkseg"
const val TRKPT_TAG = "trkpt"
const val LAT_ATTR = "lat"
const val LON_ATTR = "lon"
const val ELE_TAG = "ele"
const val DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss'Z'"