package androidapp.byco.util.compat

import android.location.Address
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun Geocoder.getFromLocationCompat(
    latitude: Double,
    longitude: Double,
    maxResults: Int
): List<Address>? {
    return withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        getFromLocation(latitude, longitude, maxResults)
    }
}