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

import android.util.Log
import android.util.Xml
import androidapp.byco.data.OsmDataProvider.BicycleType
import androidapp.byco.data.OsmDataProvider.HighwayType
import androidapp.byco.data.OsmDataProvider.OneWayType
import androidapp.byco.data.OsmDataProvider.ServiceType
import androidapp.byco.data.OsmDataProvider.SurfaceType
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import lib.gpx.DebugLog
import org.xmlpull.v1.XmlPullParser
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/** Data source for [OSM](https://www.openstreetmap.org/) data */
object OsmDataProvider {
    private const val MAX_CONNECT_TIMEOUT: Long = 5_000
    private const val READ_TIMEOUT: Int = 30_000
    private val DATA_SOURCE: Array<String> = arrayOf(
        "https://lz4.overpass-api.de/api/interpreter",
        "https://z.overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    private val TAG = OsmDataProvider::class.java.simpleName

    /**
     * Overpass query string to get nodes of a certain area and all ways + relations using the nodes
     */
    private fun getQuery(
        minLat: BigDecimal,
        minLon: BigDecimal,
        maxLat: BigDecimal,
        maxLon: BigDecimal
    ): String {
        return """<osm-script>
  <query type="way">
    <has-kv k="highway"/>
    <bbox-query s="$minLat" w="$minLon" n="$maxLat" e="$maxLon"/>
  </query>
  <union>
    <item />
    <recurse type="way-node"/>
  </union>
  <print order="quadtile"/>
</osm-script>"""
    }

    /**
     * Get raw XML OSM data for a certain area.
     */
    @VisibleForTesting
    suspend fun getOSMData(
        minLat: BigDecimal,
        minLon: BigDecimal,
        maxLat: BigDecimal,
        maxLon: BigDecimal,
    ): String {
        return withContext(IO) {
            var data: String? = null
            var incrementalBackoffTimeout = 20L

            while (data == null) {
                val dataSource = DATA_SOURCE.random()
                val urlConnection =
                    (URL(dataSource).openConnection() as HttpsURLConnection).apply {
                        doOutput = true
                        connectTimeout = MAX_CONNECT_TIMEOUT.toInt()
                        readTimeout = READ_TIMEOUT
                    }

                try {
                    urlConnection.connect()

                    try {
                        urlConnection.outputStream.bufferedWriter(Charsets.UTF_8)
                            .use { it.write(getQuery(minLat, minLon, maxLat, maxLon)) }
                        data = urlConnection.inputStream.bufferedReader(Charsets.UTF_8)
                            .use { it.readText() }
                    } finally {
                        urlConnection.disconnect()
                    }
                } catch (e: IOException) {
                    if (!coroutineContext.isActive) {
                        Log.e(TAG, "Cannot communicate with $dataSource; obsolete", e)

                        throw e
                    } else {
                        incrementalBackoffTimeout = min(
                            MAX_CONNECT_TIMEOUT,
                            incrementalBackoffTimeout * (1 + abs(Random.nextInt() % 3))
                        )

                        DebugLog.w(
                            TAG,
                            "Cannot communicate with $dataSource($minLat, $minLon); will "
                                    + "retry in $incrementalBackoffTimeout ms: ${e.message}. "
                        )

                        delay(incrementalBackoffTimeout)
                    }
                } finally {
                    try {
                        urlConnection.inputStream.close()
                    } catch (_: IOException) {
                    }
                    try {
                        urlConnection.outputStream.close()
                    } catch (_: IOException) {
                    }
                }
            }

            data
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    suspend fun getParsedOsmData(
        minLat: BigDecimal,
        minLon: BigDecimal,
        maxLat: BigDecimal,
        maxLon: BigDecimal,
    ): Pair<List<ParsedNode>, List<ParsedWay>> {
        return getOSMData(
            minLat,
            minLon,
            maxLat,
            maxLon
        ).reader().use { osmReader ->
            withContext(Default) {
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(osmReader)

                parser.parseQueryResult()
            }
        }
    }

    /**
     * Surface attribute of a [Way].
     *
     * https://wiki.openstreetmap.org/wiki/Key:surface
     */
    enum class SurfaceType(
        private val osmValue: String,
        val isPaved: Boolean,
        val isCycleable: Boolean = isPaved
    ) {
        PAVED("paved", true),
        ASPHALT("asphalt", true),
        CONCRETE("concrete", true),
        CONCRETE_LANES("concrete:lanes", false, isCycleable = true),
        CONCRETE_PLATES("concrete:plates", true),
        PAVING_STONES("paving_stones", true),
        SETT("sett", true),
        UNHEWN_COBBLESTONE("unhewn_cobblestone", true),
        COBBLESTONE("cobblestone", true),
        METAL("metal", true),
        WOOD("wood", true),
        STEPPING_STONES("stepping_stones", false),
        UNPAVED("unpaved", false),
        COMPACTED("compacted", false, true),
        FINE_GRAVEL("fine_gravel", false, true),
        GRAVEL("gravel", false, true),
        ROCK("rock", false),
        PEPPLESTONE("pebblestone", false),
        GROUND("ground", false),
        DIRT("dirt", false),
        EARTH("earth", false),
        GRASS("grass", false),
        GRASS_PAVER("grass_paver", false),
        MUD("mud", false),
        SAND("sand", false),
        WOODCHIPS("woodchips", false),
        SNOW("snow", false),
        ICE("ice", false),
        SALT("salt", false);

        companion object {
            private val valueToEnumMap = enumValues<SurfaceType>().associateBy {
                it.osmValue
            }

            internal operator fun get(osmValue: String): SurfaceType? {
                return valueToEnumMap[osmValue]
            }
        }
    }

    enum class OneWayType {
        FORWARDS,
        BACKWARDS
    }

    /**
     * Bicycle attribute of a [Way].
     *
     * https://wiki.openstreetmap.org/wiki/Key:bicycle
     */
    enum class BicycleType(
        private val osmValue: String,
        val isAllowed: Boolean
    ) {
        YES("yes", true),
        NO("no", false),
        PERMISSIVE("permissive", true),
        DESIGNATED("designated", true),
        USE_SIDEPATH("use_sidepath", false),
        DISMOUNT("dismount", true),
        PRIVATE("private", false),
        CUSTOMERS("customers", false),
        DESTINATION("destination", true);

        companion object {
            private val valueToEnumMap = enumValues<BicycleType>().associateBy {
                it.osmValue
            }

            internal operator fun get(osmValue: String): BicycleType? {
                return valueToEnumMap[osmValue]
            }
        }
    }

    /**
     * Type of service road
     *
     * https://wiki.openstreetmap.org/wiki/Tag:highway%3Dservice
     */
    enum class ServiceType(
        private val osmValue: String,
        val shouldBeShown: Boolean
    ) {
        PARKING_AISLE("parking_aisle", false),
        DRIVEWAY("driveway", false),
        ALLEY("alley", true),
        EMERGENCY_ACCESS("emergency_access", false),
        DRIVE_THROUGH("drive-through", true);

        companion object {
            private val valueToEnumMap = enumValues<ServiceType>().associateBy {
                it.osmValue
            }

            internal operator fun get(osmValue: String): ServiceType? {
                return valueToEnumMap[osmValue]
            }
        }
    }

    /**
     * Type of [Way] as per Open Street Map specification
     *
     * https://wiki.openstreetmap.org/wiki/Key:highway
     */
    enum class HighwayType(private val osmValue: String?) {
        // Roads
        MOTORWAY("motorway"),
        TRUNK("trunk"),
        PRIMARY("primary"),
        SECONDARY("secondary"),
        TERTIARY("tertiary"),
        UNCLASSIFIED("unclassified"),
        RESIDENTIAL("residential"),

        // Link Roads
        MOTORWAY_LINK("motorway_link"),
        TRUNK_LINK("trunk_link"),
        PRIMARY_LINK("primary_link"),
        SECONDARY_LINK("secondary_link"),
        TERTIARY_LINK("tertiary_link"),

        // Special road types
        LIVING_STEET("living_street"),
        SERVICE("service"),
        PEDESTRIAN("pedestrian"),
        TRACK("track"),
        BUS_GUIDEWAY("bus_guideway"),
        ESCAPE("escape"),
        RACEWAY("raceway"),
        ROAD("road"),

        // Paths
        FOOTWAY("footway"),
        BRIDALWAY("bridalway"),
        STEPS("steps"),
        CORRIDOR("corridor"),
        PATH("path"),

        // When cycleway is drawn as its own way (see Bicycle)
        CYCLEWAY("cycleway"),

        // Lifecycle
        PROPOSED("proposed"),
        CONSTRUCTION("construction"),

        // Non-OSM types
        RESIDENTIAL_DESIGNATED(null),
        BAD_PATH(null),

        /** Not a existing [Way]. Use to e.g. indicate the best way to get to a real [Way] */
        GENERATED(null);

        companion object {
            private val valueToEnumMap = enumValues<HighwayType>().mapNotNull { wayType ->
                wayType.osmValue?.let { osmValue -> osmValue to wayType }
            }.toMap()

            internal operator fun get(osmValue: String): HighwayType? {
                return valueToEnumMap[osmValue]
            }
        }
    }
}

private fun XmlPullParser.parseNode(): ParsedNode {
    var id: Long? = null
    var latitude: Double? = null
    var longitude: Double? = null

    for (i in 0 until attributeCount) {
        when (getAttributeName(i)) {
            "id" -> {
                id = getAttributeValue(i).toLong()
            }
            "lat" -> {
                latitude = getAttributeValue(i).toDouble()
            }
            "lon" -> {
                longitude = getAttributeValue(i).toDouble()
            }
        }
    }
    return ParsedNode(id!!, latitude!!, longitude!!).also {
        // Some node tags have nested tags. Hence skip forward until node tag ends
        while (!(next() == XmlPullParser.END_TAG && name == "node")) {
            // empty
        }
    }
}

private fun XmlPullParser.parseWayTag(): Pair<String, String> {
    var key: String? = null
    var value: String? = null

    for (i in 0 until attributeCount) {
        when (getAttributeName(i)) {
            "k" -> {
                key = getAttributeValue(i)
            }
            "v" -> {
                value = getAttributeValue(i)
            }
        }
    }

    return key!! to value!!
}

private fun XmlPullParser.parseWayNd(): Long {
    return getAttributeValue(null, "ref").toLong()
}

private fun XmlPullParser.parseWay(): ParsedWay {
    val id = getAttributeValue(null, "id").toLong()
    var wayName: String? = null
    var wayHighway: HighwayType? = null
    var wayBicycle: BicycleType? = null
    var oneway: OneWayType? = null
    var waySurface: SurfaceType? = null
    var wayService: ServiceType? = null
    val wayNodeRef = mutableListOf<Long>()

    while (!(nextTag() == XmlPullParser.END_TAG && name == "way")) {
        if (eventType != XmlPullParser.START_TAG) {
            continue
        }

        when (name) {
            "nd" -> {
                wayNodeRef.add(parseWayNd())
            }
            "tag" -> {
                val newTag = parseWayTag()
                when (newTag.first) {
                    "name" -> {
                        wayName = newTag.second
                    }
                    "highway" -> {
                        wayHighway = HighwayType[newTag.second]
                    }
                    "bicycle" -> {
                        wayBicycle = BicycleType[newTag.second]
                    }
                    "surface" -> {
                        waySurface = SurfaceType[newTag.second]
                    }
                    "service" -> {
                        wayService = ServiceType[newTag.second]
                    }
                    "access" -> {
                        // See https://wiki.openstreetmap.org/wiki/Key:access
                        if (newTag.second in setOf("no", "private", "customers", "delivery")
                            && wayBicycle == null
                        ) {
                            // certain access=* tags imply bicycle=no unless there is a separate
                            // bicycle tag
                            wayBicycle = BicycleType.NO
                        }
                    }
                    "motorroad" -> {
                        if (newTag.second == "yes") {
                            // motorroad=yes implies bicycle=no
                            wayBicycle = BicycleType.NO
                        }
                    }
                    "oneway" -> {
                        oneway = when (newTag.second) {
                            "yes", "1", "true" -> OneWayType.FORWARDS
                            "-1", "reverse" -> OneWayType.BACKWARDS
                            else -> null
                        }
                    }
                }
            }
        }
    }

    return ParsedWay(
        id,
        true,
        oneway != null,
        wayHighway,
        wayName,
        wayBicycle,
        waySurface,
        wayService,
        // Apply reversed one-way here to avoid having to deal with it in the rest of the code
        if (oneway == OneWayType.BACKWARDS) {
            wayNodeRef.asReversed().toLongArray()
        } else {
            wayNodeRef.toLongArray()
        }
    )
}

private fun XmlPullParser.parseQueryResult()
        : Pair<List<ParsedNode>, List<ParsedWay>> {
    while (eventType != XmlPullParser.END_DOCUMENT && !(next() == XmlPullParser.START_TAG && name == "osm")) {
        // Skip preamble
    }

    val nodes = mutableListOf<ParsedNode>()
    val ways = mutableListOf<ParsedWay>()

    while (!(next() == XmlPullParser.END_TAG && name == "osm")) {
        if (eventType != XmlPullParser.START_TAG) {
            continue
        }

        when (name) {
            "node" -> {
                val node = parseNode()
                nodes += node
            }
            "way" -> {
                val newWay = parseWay()

                if (newWay.highway != null) {
                    ways += newWay
                }
            }
        }
    }

    return nodes to ways
}

/**
 * [Node] that is read from the raw OSM, but has not yet been added into the final map data
 */
@VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
class ParsedNode(
    val id: Long,
    val latitude: Double,
    val longitude: Double,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }

        if (other == null || other !is ParsedNode) {
            return false
        }

        return id == other.id
    }

    override fun hashCode(): Int {
        return id.toInt()
    }

    fun toNode(): Node {
        return Node(id, latitude, longitude)
    }
}

internal fun DataInputStream.readParsedNode(): ParsedNode {
    return ParsedNode(readLong(), readDouble(), readDouble())
}

internal fun DataOutputStream.writeParsedNode(node: ParsedNode) {
    writeLong(node.id)
    writeDouble(node.latitude)
    writeDouble(node.longitude)
}

/**
 * [Way] that is read from the raw OSM, but has not yet been added into the final map data
 *
 * @param nodeRefs Id's of the [ParsedNode]s making up this way
 */
@VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
class ParsedWay(
    val id: Long,
    val isOpen: Boolean,
    val isOneway: Boolean,
    val highway: HighwayType?,
    val name: String?,
    val bicycle: BicycleType?,
    val surface: SurfaceType?,
    val service: ServiceType?,
    val nodeRefs: LongArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }

        if (other == null || other !is ParsedWay) {
            return false
        }

        return id == other.id
    }

    override fun hashCode(): Int {
        return id.toInt()
    }

    fun toWay(
        nodes: List<Node>,
        keepStreetName: Boolean = true
    ): Way {
        return Way(
            id, isOpen, isOneway, highway, when {
                keepStreetName -> {
                    name
                }
                name != null -> {
                    ""
                }
                else -> {
                    null
                }
            }, bicycle, surface, service, nodes
        )
    }
}

/** Read [ParsedWay] from input stream */
internal fun DataInputStream.readParsedWay(dataFormatVersion: Int): ParsedWay {
    fun <T> DataInputStream.readNullOr(block: (i: Int) -> T?): T? {
        return readInt().let { i ->
            if (i >= 0) {
                block(i)
            } else {
                null
            }
        }
    }

    return ParsedWay(
        readLong(),
        readBoolean(),
        if (dataFormatVersion == 2) {
            readBoolean()
        } else {
            false
        },
        readNullOr { ordinal ->
            HighwayType.values()[ordinal]
        },
        if (readBoolean()) {
            readUTF()
        } else {
            null
        },
        readNullOr { ordinal ->
            BicycleType.values()[ordinal]
        },
        readNullOr { ordinal ->
            SurfaceType.values()[ordinal]
        },
        readNullOr { ordinal ->
            ServiceType.values()[ordinal]
        }, run {
            val numNodes = readInt()
            val nodes = LongArray(numNodes)

            nodes.indices.forEach { i ->
                nodes[i] = readLong()
            }
            nodes
        })
}

/** Write parsed way to output stream */
internal fun DataOutputStream.writeParsedWay(way: ParsedWay) {
    writeLong(way.id)
    writeBoolean(way.isOpen)
    writeBoolean(way.isOneway)
    writeInt(way.highway?.ordinal ?: -1)
    if (way.name != null) {
        writeBoolean(true)
        writeUTF(way.name)
    } else {
        writeBoolean(false)
    }
    writeInt(way.bicycle?.ordinal ?: -1)
    writeInt(way.surface?.ordinal ?: -1)
    writeInt(way.service?.ordinal ?: -1)

    writeInt(way.nodeRefs.size)
    way.nodeRefs.forEach { nodeId ->
        writeLong(nodeId)
    }
}
