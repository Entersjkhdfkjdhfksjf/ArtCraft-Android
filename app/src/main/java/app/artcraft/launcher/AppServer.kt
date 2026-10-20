package app.artcraft.launcher

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Serves everything the WebViews load. Nothing is ever fetched from a real server:
 *   https://shell.appassets.androidplatform.net/...   -> assets/shell/...   (the launcher UI)
 *   https://<id>.appassets.androidplatform.net/...    -> filesDir/apps/<id>/... or assets/apps/<id>/...
 * Serving it ourselves gives the right MIME type for .wasm (required for streaming compile), keeps the
 * https "secure context" that WebGPU/clipboard need, and gives every app its own storage origin.
 */
class AppServer(private val ctx: Context, private val registry: AppRegistry) {

    private val shim: String by lazy {
        try {
            ctx.assets.open("shim/save-shim.js").use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: IOException) {
            ""
        }
    }

    fun serve(uri: Uri): WebResourceResponse {
        val host = uri.host ?: return notFound()
        val id = host.removeSuffix(HOST_SUFFIX)
        val segs = (uri.path ?: "/").split('/').filter { it.isNotEmpty() }
        if (segs.any { it == ".." || it == "." || it.contains('\\') }) return notFound()
        var rel = segs.joinToString("/")
        if (rel.isEmpty()) rel = "index.html"

        if (id == SHELL_ID) {
            if (segs.size == 2 && segs[0] == "icon") return serveIcon(segs[1])
            return try {
                response(mimeFor(rel), ctx.assets.open("shell/$rel"))
            } catch (e: IOException) {
                notFound()
            }
        }

        if (!isValidId(id) || registry.find(id) == null) return notFound()
        val stream = openAppFile(id, rel) ?: return notFound()

        if (rel == "index.html") {
            val html = stream.use { it.readBytes() }.toString(Charsets.UTF_8)
            return response("text/html", ByteArrayInputStream(injectShim(html).toByteArray(Charsets.UTF_8)))
        }
        return response(mimeFor(rel), stream)
    }

    private fun serveIcon(appId: String): WebResourceResponse {
        val info = registry.find(appId) ?: return notFound()
        val icon = info.icon ?: return notFound()
        val stream = openAppFile(appId, icon) ?: return notFound()
        return response(mimeFor(icon), stream)
    }

    /** An installed copy fully replaces a bundled one, so files are never mixed between versions. */
    private fun openAppFile(id: String, rel: String): InputStream? {
        val dir = registry.dirOf(id)
        if (dir.isDirectory) {
            val f = File(dir, rel).canonicalFile
            if (!f.path.startsWith(dir.canonicalPath + File.separator)) return null
            return if (f.isFile) FileInputStream(f) else null
        }
        return try {
            ctx.assets.open("apps/$id/$rel", AssetManager.ACCESS_STREAMING)
        } catch (e: IOException) {
            null
        }
    }

    private fun injectShim(html: String): String {
        if (shim.isEmpty()) return html
        val tag = "<script>$shim</script>"
        val m = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
        return if (m != null) html.substring(0, m.range.last + 1) + tag + html.substring(m.range.last + 1) else tag + html
    }

    private fun response(mime: String, data: InputStream): WebResourceResponse {
        val textual = mime.startsWith("text/") || mime == "application/json"
        return WebResourceResponse(
            mime,
            if (textual) "utf-8" else null,
            200,
            "OK",
            mapOf("X-Content-Type-Options" to "nosniff"),
            data,
        )
    }

    private fun notFound() = WebResourceResponse(
        "text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream("Not found".toByteArray()),
    )

    companion object {
        fun blocked() = WebResourceResponse(
            "text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)),
        )
    }
}
