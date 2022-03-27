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

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.os.Build
import androidapp.byco.data.OsmDataProvider.HighwayType
import androidapp.byco.data.OsmDataProvider.HighwayType.*

/**
 * Country code according to ISO 3166 (or null if there is no country)
 *
 * https://en.wikipedia.org/wiki/List_of_ISO_3166_country_codes
 */
typealias CountryCode = String?

// Cache last country code in case [getCountryCode] cannot compute new value
private var lastCountryCode: CountryCode = null

/**
 * Get country code for given location
 */
fun getCountryCode(
    context: Context,
    location: Location?,
    isCurrentLocation: Boolean = true
): CountryCode {
    if (Geocoder.isPresent() && location != null) {
        return try {
            Geocoder(context).getFromLocation(
                location.latitude,
                location.longitude,
                1
            )?.get(0)?.countryCode
                .also {
                    if (isCurrentLocation) {
                        lastCountryCode = it
                    }
                }
        } catch (ignored: Exception) {
            null
        } ?: if (isCurrentLocation) {
            // Unlikely that the user moved quickly, hence re-use last country code if Geocoder did
            // not work
            lastCountryCode
        } else {
            null
        }
    } else {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0].country
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale.country
        }
    }
}

/**
 * Is a country using miles instead of km for road speed and distances.
 *
 * https://en.wikipedia.org/wiki/Miles_per_hour.
 */
fun isCountryUsingMiles(countryCode: CountryCode): Boolean {
    return when (countryCode) {
        // Some comment about freedom, but ... meh
        "AG", "BS", "BZ", "DM", "GD", /* Liberia only occasionally, hence don't include it */ "MH",
        "FM", "PW", "KN", "LC", "VC", "WS", "GB", "AI", "VG", "IO", "KY", "FK", "MS", "SH", "TC",
        "GG", "IM", "JE", "US", "AS", "GU", "US-GU", "MP", "US-MP", "PR", "US-PR", "VI", "US-VI" ->
            true
        else -> false
    }
}

/**
 * In a certain country certain [HighwayType]s are either allowed, not allowed or unspecified unless
 * overridden by specific tags.
 */
private data class DefaultPathAllowances(
    val allowed: Set<HighwayType>,
    val disallowed: Set<HighwayType>
)

/*
 * Based on https://wiki.openstreetmap.org/wiki/OSM_tags_for_routing/Access_restrictions
 */
private val BICYCLES_ALLOWED_BY_DEFAULT = mapOf(
    null to DefaultPathAllowances(
        setOf(
            GENERATED,
            TRUNK, TRUNK_LINK,
            PRIMARY, PRIMARY_LINK, SECONDARY, SECONDARY_LINK, TERTIARY, TERTIARY_LINK,
            UNCLASSIFIED, RESIDENTIAL, LIVING_STEET, ROAD,
            PATH, CYCLEWAY,
            // Not specifically mentioned in wiki but still used
            TRACK, SERVICE
        ), setOf(PEDESTRIAN, BRIDALWAY, FOOTWAY,
            MOTORWAY, MOTORWAY_LINK)
    ),
    "AU" to DefaultPathAllowances(
        setOf(
            TRUNK, TRUNK_LINK,
            PRIMARY, PRIMARY_LINK, SECONDARY, SECONDARY_LINK, TERTIARY, TERTIARY_LINK,
            UNCLASSIFIED, RESIDENTIAL, ROAD, LIVING_STEET,
            PEDESTRIAN, PATH, BRIDALWAY, CYCLEWAY
        ), setOf(FOOTWAY,
            MOTORWAY, MOTORWAY_LINK)
    ),
    "AT" to DefaultPathAllowances(
        setOf(
            PRIMARY, PRIMARY_LINK, SECONDARY, SECONDARY_LINK, TERTIARY, TERTIARY_LINK,
            UNCLASSIFIED, RESIDENTIAL, ROAD, LIVING_STEET, SERVICE,
            CYCLEWAY, TRACK
        ), setOf(BRIDALWAY,
            TRUNK, TRUNK_LINK, MOTORWAY, MOTORWAY_LINK)
    ),
    "BE" to DefaultPathAllowances(
        setOf(
            PRIMARY, PRIMARY_LINK, SECONDARY, SECONDARY_LINK, TERTIARY, TERTIARY_LINK,
            UNCLASSIFIED, RESIDENTIAL, LIVING_STEET,
            PATH, CYCLEWAY, TRACK, PEDESTRIAN
        ), setOf(BRIDALWAY, FOOTWAY,
            TRUNK, TRUNK_LINK, MOTORWAY, MOTORWAY_LINK)
    ),
    "FR" to DefaultPathAllowances(
        setOf(
            PRIMARY, PRIMARY_LINK, SECONDARY, SECONDARY_LINK, TERTIARY, TERTIARY_LINK,
            UNCLASSIFIED, RESIDENTIAL, LIVING_STEET,
            PATH, CYCLEWAY, TRACK, PEDESTRIAN
        ), setOf(BRIDALWAY, FOOTWAY,
            TRUNK, TRUNK_LINK, MOTORWAY, MOTORWAY_LINK)
    ),
    "DE" to DefaultPathAllowances(
        setOf(
            TRUNK, TRUNK_LINK,
            PRIMARY, PRIMARY_LINK, SECONDARY, SECONDARY_LINK, TERTIARY, TERTIARY_LINK,
            UNCLASSIFIED, RESIDENTIAL, LIVING_STEET, ROAD, SERVICE,
            PATH, CYCLEWAY, TRACK
        ), setOf(BRIDALWAY,
            FOOTWAY, PEDESTRIAN)
    ),
    "IT" to DefaultPathAllowances(
        setOf(
            // Special treatment of motorroad not reflected here
            TRUNK, TRUNK_LINK,
            PRIMARY, PRIMARY_LINK, SECONDARY, SECONDARY_LINK, TERTIARY, TERTIARY_LINK,
            UNCLASSIFIED, RESIDENTIAL, LIVING_STEET, SERVICE,
            PATH, PEDESTRIAN, CYCLEWAY, TRACK
        ), setOf(FOOTWAY,
            MOTORWAY, MOTORWAY_LINK)
    ),
    "NL" to DefaultPathAllowances(
        setOf(
            PRIMARY, PRIMARY_LINK, SECONDARY, SECONDARY_LINK, TERTIARY, TERTIARY_LINK,
            UNCLASSIFIED, RESIDENTIAL, LIVING_STEET, SERVICE, ROAD,
            PATH, TRACK, CYCLEWAY
        ), setOf(FOOTWAY, PEDESTRIAN, BRIDALWAY,
            TRUNK, TRUNK_LINK, MOTORWAY, MOTORWAY_LINK)
    ),
    "ES" to DefaultPathAllowances(
        setOf(
            // Special treatment of motorroad not reflected here
            PRIMARY, PRIMARY_LINK, SECONDARY, SECONDARY_LINK, TERTIARY, TERTIARY_LINK,
            UNCLASSIFIED, RESIDENTIAL, LIVING_STEET,
            TRACK, PATH, CYCLEWAY, PEDESTRIAN
        ), setOf(FOOTWAY, BRIDALWAY,
            TRUNK, TRUNK_LINK, MOTORWAY, MOTORWAY_LINK)
    ),
    "CH" to DefaultPathAllowances(
        setOf(
            PRIMARY, PRIMARY_LINK, SECONDARY, SECONDARY_LINK, TERTIARY, TERTIARY_LINK,
            UNCLASSIFIED, RESIDENTIAL, LIVING_STEET, ROAD,
            TRACK, PATH, CYCLEWAY
        ), setOf(FOOTWAY, BRIDALWAY, PEDESTRIAN,
            TRUNK, TRUNK_LINK, MOTORWAY, MOTORWAY_LINK)
    ),
    "GB" to DefaultPathAllowances(
        setOf(
            TRUNK, TRUNK_LINK,
            PRIMARY, PRIMARY_LINK, SECONDARY, SECONDARY_LINK, TERTIARY, TERTIARY_LINK,
            UNCLASSIFIED, RESIDENTIAL, LIVING_STEET,
            PATH, BRIDALWAY, CYCLEWAY
        ), setOf(FOOTWAY, PEDESTRIAN,
            MOTORWAY, MOTORWAY_LINK)
    ),
    "US" to DefaultPathAllowances(
        setOf(
            TRUNK, TRUNK_LINK,
            PRIMARY, PRIMARY_LINK, SECONDARY, SECONDARY_LINK, TERTIARY, TERTIARY_LINK,
            UNCLASSIFIED, RESIDENTIAL, LIVING_STEET, ROAD,
            PEDESTRIAN, PATH, BRIDALWAY, CYCLEWAY
        ), setOf(FOOTWAY,
            MOTORWAY, MOTORWAY_LINK)
    ),
)

/**
 * Are bicycles allowed on the [HighwayType] if not otherwise annotated?
 */
fun HighwayType.areBicyclesAllowedByDefault(countryCode: CountryCode): Boolean {
    val default = BICYCLES_ALLOWED_BY_DEFAULT[countryCode]

    return when {
        default?.allowed?.contains(this) == true -> true
        default?.disallowed?.contains(this) == true -> false
        BICYCLES_ALLOWED_BY_DEFAULT[null]!!.allowed.contains(this) -> true
        BICYCLES_ALLOWED_BY_DEFAULT[null]!!.disallowed.contains(this) -> false
        else -> {
            throw IllegalStateException("Cannot determine if bike is allowed on highway type" +
                    "$this for country code $countryCode")
        }
    }
}
