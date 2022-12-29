package androidapp.byco.util.compat

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Parcelable

fun <T : Parcelable> Intent.getParcelableExtraCompat(name: String, clazz: Class<T>): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, clazz)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }
}

fun <T : Parcelable> Intent.getParcelableArrayListExtraCompat(
    name: String,
    clazz: Class<T>
): ArrayList<T>? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(name, clazz)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayListExtra(name)
    }
}

fun Intent.putExcludeComponentsExtraCompat(components: Array<ComponentName>): Intent {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        // Prevent sending back to the this app
        putExtra(
            Intent.EXTRA_EXCLUDE_COMPONENTS,
            components
        )
    } else {
        // not available on this API version
    }

    return this
}