package androidapp.byco.util.compat

import android.os.Build
import android.os.FileObserver
import java.io.File

fun createFileObserverCompat(
    path: File,
    onEvent: ((event: Int, path: String?) -> Unit)
): FileObserver {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        object : FileObserver(path) {
            override fun onEvent(event: Int, path: String?) {
                onEvent(event, path)
            }
        }
    } else {
        @Suppress("DEPRECATION")
        object : FileObserver(path.absolutePath) {
            override fun onEvent(event: Int, path: String?) {
                onEvent(event, path)
            }
        }
    }
}