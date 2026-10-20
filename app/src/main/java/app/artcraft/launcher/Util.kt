package app.artcraft.launcher

import java.util.Locale

/** Host of the launcher UI itself. Each app gets its own origin: https://<id>.appassets.androidplatform.net */
const val SHELL_ID = "shell"
const val HOST_SUFFIX = ".appassets.androidplatform.net"

fun originOf(id: String) = "https://$id$HOST_SUFFIX"

private val ID_RE = Regex("^[a-z0-9][a-z0-9-]{0,39}$")
fun isValidId(id: String) = ID_RE.matches(id)

/** Turns arbitrary text into a safe app id (usable as a hostname label and a directory name). */
fun sanitizeId(raw: String): String {
    val s = raw.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
    return if (s.length > 40) s.substring(0, 40).trim('-') else s
}

/** Numeric, dotted version comparison ("0.10.0" > "0.9.2"). Pre-release suffixes are ignored. */
fun compareVersions(a: String, b: String): Int {
    fun parts(v: String) = v.removePrefix("v").split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toLongOrNull() ?: 0L }
    val pa = parts(a)
    val pb = parts(b)
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val x = pa.getOrElse(i) { 0L }
        val y = pb.getOrElse(i) { 0L }
        if (x != y) return x.compareTo(y)
    }
    return 0
}

fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
    "html", "htm" -> "text/html"
    "js", "mjs" -> "text/javascript"
    "css" -> "text/css"
    "wasm" -> "application/wasm"
    "json", "map" -> "application/json"
    "webmanifest" -> "application/manifest+json"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "ico" -> "image/x-icon"
    "woff2" -> "font/woff2"
    "woff" -> "font/woff"
    "ttf" -> "font/ttf"
    "otf" -> "font/otf"
    "txt", "md" -> "text/plain"
    "mp3" -> "audio/mpeg"
    "wav" -> "audio/wav"
    "ogg" -> "audio/ogg"
    "mp4" -> "video/mp4"
    "webm" -> "video/webm"
    else -> "application/octet-stream"
}
