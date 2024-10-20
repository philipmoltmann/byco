package lib.gpx

import java.math.BigDecimal

/** A location that does not loose precision. */
abstract class PreciseLocation(val lat: BigDecimal, val lon: BigDecimal) {
    abstract val scale: Int

    override fun hashCode(): Int {
        // Cannot use [BigDecimal.hashcode] as this also encodes the scale.
        return lat.unscaledValue().hashCode() + lon.unscaledValue().hashCode()
    }

    override fun toString(): String {
        return "$lat/$lon"
    }

    override fun equals(other: Any?): Boolean {
        if (other !is PreciseLocation) {
            return false
        }

        // Cannot use [BigDecimal.equals] as this would also compare the scale.
        return lat.compareTo(other.lat) == 0 && lon.compareTo(other.lon) == 0
    }

    operator fun component1(): BigDecimal = lat
    operator fun component2(): BigDecimal = lon

    fun toBasicLocation(): BasicLocation =
        BasicLocation(
            lat.toDouble(),
            lon.toDouble()
        )
}