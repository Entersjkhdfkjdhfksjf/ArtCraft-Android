package app.artcraft.launcher

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small in-memory log (also sent to logcat) so problems on a real device can be inspected from the launcher. */
object Diag {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun d(tag: String, msg: String) {
        Log.d("Artcraft/$tag", msg)
        lines.addLast("${fmt.format(Date())} [$tag] $msg")
        while (lines.size > 300) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = if (lines.isEmpty()) "(nothing logged yet)" else lines.joinToString("\n")
}
