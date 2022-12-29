package androidapp.byco.ui.views

import android.app.job.JobInfo
import android.os.Build

fun JobInfo.Builder.setRequiresBatteryNotLowCompat(requiresBatteryNotLow: Boolean): JobInfo.Builder {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        setRequiresBatteryNotLow(requiresBatteryNotLow)
    } else {
        // not available on this API version
    }

    return this
}

fun JobInfo.Builder.setRequiresStorageNotLowCompat(requiresStorageNotLow: Boolean): JobInfo.Builder {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        setRequiresStorageNotLow(requiresStorageNotLow)
    } else {
        // not available on this API version
    }

    return this
}