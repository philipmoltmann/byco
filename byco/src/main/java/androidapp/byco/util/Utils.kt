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
import android.content.res.Configuration
import android.view.View
import android.view.View.VISIBLE
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

val TWO = BigDecimal(2)

/**
 * Is current [Locale] using miles. The locale is set by the user on the phone and might not match
 * the common usage in the current country.
 *
 * @see androidapp.byco.data.LocationRepository.isUsingMiles
 */
fun isLocaleUsingMiles(): Boolean {
    return isCountryUsingMiles(Locale.getDefault().country)
}

fun View.makeVisibleIf(makeVisible: Boolean, invisibility: Int = View.GONE) {
    visibility = if (makeVisible) {
        VISIBLE
    } else {
        invisibility
    }
}

/** Add a new entry to a [ZipOutputStream] with the content written in [block] */
@Suppress("BlockingMethodInNonBlockingContext")
suspend fun ZipOutputStream.newEntry(name: String, block: suspend ZipOutputStream.() -> (Unit)) {
    putNextEntry(ZipEntry(name))
    try {
        block()
    } finally {
        closeEntry()
    }
}

fun forBigDecimal(
    from: BigDecimal,
    toExcluding: BigDecimal,
    step: BigDecimal,
    block: (v: BigDecimal) -> Unit
) {
    var v = from
    while (v < toExcluding) {
        block(v)

        v += step
    }
}

suspend fun forBigDecimalSuspend(
    from: BigDecimal,
    toExcluding: BigDecimal,
    step: BigDecimal,
    block: suspend (v: BigDecimal) -> Unit
) {
    var v = from
    while (v < toExcluding) {
        block(v)

        v += step
    }
}

fun <R> mapBigDecimal(
    from: BigDecimal,
    toExcluding: BigDecimal,
    step: BigDecimal,
    block: (v: BigDecimal) -> R
): List<R> {
    val ret = mutableListOf<R>()

    forBigDecimal(from, toExcluding, step) {
        ret += block(it)
    }

    return ret
}

suspend fun <R> mapBigDecimalSuspend(
    from: BigDecimal,
    toExcluding: BigDecimal,
    step: BigDecimal,
    block: suspend (v: BigDecimal) -> R
): List<R> {
    val ret = mutableListOf<R>()

    forBigDecimalSuspend(from, toExcluding, step) {
        ret += block(it)
    }

    return ret
}

/**
 * Write [BigDecimal] to [DataOutputStream].
 *
 * Assumes unscaled value of [BigDecimal] fits into a [Long].
 */
fun DataOutputStream.writeBigDecimal(d: BigDecimal) {
    assert(
        d.unscaledValue() in
                BigInteger.valueOf(Long.MIN_VALUE)..BigInteger.valueOf(Long.MAX_VALUE)
    )

    writeLong(d.unscaledValue().toLong())
    writeInt(d.scale())
}

/**
 * Read a [BigDecimal] from [DataInputStream]. This [BigDecimal] must have been previously written
 * to the file by [writeBigDecimal].
 */
fun DataInputStream.readBigDecimal(): BigDecimal {
    return BigDecimal.valueOf(readLong(), readInt())
}

/**
 * Smallest rotation to map one bearing onto another (`-180` to `180`)
 */
fun rotationBetweenBearings(a: Float, b: Float): Float {
    val reg = b - a
    val bSpin = (b + 360) - a
    val aSpin = b - (a + 360)

    return if (abs(reg) < abs(bSpin)) {
        if (abs(reg) < abs(aSpin)) {
            reg
        } else {
            aSpin
        }
    } else {
        if (abs(bSpin) < abs(aSpin)) {
            bSpin
        } else {
            aSpin
        }
    }
}

/**
 * Return bearing in between `-180` and `180`
 */
fun canonicalizeBearing(bearing: Float): Float {
    var adjustedBearing = bearing
    while (adjustedBearing < 0) {
        adjustedBearing += 360
    }

    adjustedBearing %= 360

    return if (adjustedBearing > 180) {
        adjustedBearing - 360
    } else {
        adjustedBearing
    }
}

/* increase/decrease value to be in at or in between min and max */
fun Double.restrictTo(min: Double, max: Double): Double {
    return min(max, max(min, this))
}

/* increase/decrease value to be in at or in between min and max */
fun Double.restrictTo(min: Int, max: Int): Double {
    return restrictTo(min.toDouble(), max.toDouble())
}

/** Average over a list */
fun <L> List<L>.averageOf(getAverageField: (L) -> Double): Double {
    return sumOf { getAverageField(it) } / size
}

/** Is this context current using dark UI mode? */
fun Context.isDarkMode(): Boolean {
    return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_YES) != 0
}

