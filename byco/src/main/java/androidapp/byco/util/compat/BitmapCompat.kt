package androidapp.byco.util.compat

import android.graphics.Bitmap
import android.os.Build

val WEBP_COMPAT = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    Bitmap.CompressFormat.WEBP_LOSSY
} else {
    @Suppress("DEPRECATION")
    Bitmap.CompressFormat.WEBP
}
