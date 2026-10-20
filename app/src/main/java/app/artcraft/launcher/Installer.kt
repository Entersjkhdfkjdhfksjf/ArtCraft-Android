package app.artcraft.launcher

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/**
 * Installs web builds ("<name>-web-<version>.zip", as published on GitHub Releases) into app storage.
 * All public methods return immediately; work happens on a pool and is reported through [Listener]
 * (callbacks arrive on worker threads).
 */
class Installer(
    private val ctx: Context,
    private val registry: AppRegistry,
    private val listener: Listener,
    private val isRunning: (String) -> Boolean,
) {
    interface Listener {
        fun onProgress(key: String, phase: String, done: Long, total: Long)
        fun onLatest(repo: String, version: String?, error: String?)
        fun onInstalled(key: String, app: AppInfo)
        fun onError(key: String, message: String)
    }

    private class HttpException(val code: Int) : IOException("HTTP $code")

    private data class Release(val tag: String, val version: String, val assetUrl: String)

    private val pool: ExecutorService = Executors.newFixedThreadPool(3)
    private val busy = ConcurrentHashMap.newKeySet<String>()

    init {
        pool.execute { registry.cleanTemp() }
    }

    // ---- public API -------------------------------------------------------------------------

    fun checkLatest(repo: String) {
        pool.execute {
            try {
                requireRepo(repo)
                listener.onLatest(repo, resolveLatest(repo).version, null)
            } catch (e: Exception) {
                listener.onLatest(repo, null, friendly(e))
            }
        }
    }

    /** Installs or updates from the latest GitHub release of "owner/name". */
    fun installFromRepo(key: String, repo: String) = async(key) {
        requireRepo(repo)
        val id = sanitizeId(repo.substringAfter('/'))
        ensureNotRunning(id)
        listener.onProgress(key, "resolve", 0, 0)
        val rel = resolveLatest(repo)
        val zip = tempZip()
        try {
            try {
                download(rel.assetUrl, zip, key)
            } catch (e: HttpException) {
                if (e.code != 404) throw e
                val alt = scrapeAsset(repo, rel.tag)
                    ?: throw IOException("Release ${rel.tag} of $repo has no web build (a *-web-*.zip file).")
                download(alt, zip, key)
            }
            finishInstall(key, zip, id, rel.version, null)
        } finally {
            zip.delete()
        }
    }

    /** Installs from a direct https link to a web build zip. */
    fun installFromUrl(key: String, url: String) = async(key) {
        val u = Uri.parse(url)
        if (u.scheme != "https" || u.host.isNullOrEmpty()) throw IOException("Only https:// links are supported.")
        val zip = tempZip()
        try {
            download(url, zip, key)
            finishInstall(key, zip, null, null, u.lastPathSegment)
        } finally {
            zip.delete()
        }
    }

    /** Installs from a zip the user picked with the system file picker. */
    fun installFromUri(key: String, uri: Uri) = async(key) {
        val zip = tempZip()
        try {
            var name: String? = null
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
            val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open that file.")
            input.use { copyWithProgress(it, zip, key, "download", -1L) }
            finishInstall(key, zip, null, null, name)
        } finally {
            zip.delete()
        }
    }

    // ---- internals --------------------------------------------------------------------------

    private fun async(key: String, block: () -> Unit) {
        if (!busy.add(key)) return
        pool.execute {
            try {
                block()
            } catch (e: Exception) {
                listener.onError(key, friendly(e))
            } finally {
                busy.remove(key)
            }
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is UnknownHostException, is ConnectException, is SocketTimeoutException ->
            "Couldn't reach the server. Check your internet connection and try again."
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun requireRepo(repo: String) {
        if (!Regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$").matches(repo)) throw IOException("\"$repo\" isn't a GitHub repository (use owner/name).")
    }

    private fun ensureNotRunning(id: String) {
        if (isRunning(id)) {
            val name = registry.find(id)?.name ?: id
            throw IOException("$name is open. Close it from the launcher, then try again.")
        }
    }

    private fun tempZip() = File(ctx.cacheDir, "dl-${System.nanoTime()}.zip")

    private fun open(url: String, method: String = "GET", follow: Boolean = true): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.instanceFollowRedirects = follow
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "ArtcraftLauncher/1.0")
        return c
    }

    /** Uses the /releases/latest redirect, which (unlike the REST API) isn't rate limited. */
    private fun resolveLatest(repo: String): Release {
        val c = open("https://github.com/$repo/releases/latest", "HEAD", follow = false)
        try {
            val code = c.responseCode
            val loc = c.getHeaderField("Location")
            if (code !in 300..399 || loc == null) {
                throw IOException(if (code == 404) "$repo has no published releases." else "GitHub answered HTTP $code.")
            }
            val tag = Uri.decode(loc.substringAfterLast("/tag/", ""))
            if (tag.isEmpty()) throw IOException("$repo has no published releases.")
            val version = tag.removePrefix("v")
            val name = repo.substringAfter('/')
            return Release(tag, version, "https://github.com/$repo/releases/download/$tag/$name-web-$version.zip")
        } finally {
            c.disconnect()
        }
    }

    /** Fallback if a repo doesn't follow the "<name>-web-<version>.zip" naming. */
    private fun scrapeAsset(repo: String, tag: String): String? {
        val c = open("https://github.com/$repo/releases/expanded_assets/${Uri.encode(tag)}")
        return try {
            if (c.responseCode != 200) return null
            val body = c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            val m = Regex("href=\"(/[^\"]*/releases/download/[^\"]*-web-[^\"]*\\.zip)\"").find(body)
            m?.let { "https://github.com" + it.groupValues[1] }
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, dest: File, key: String) {
        val c = open(url)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw HttpException(code)
            c.inputStream.use { copyWithProgress(it, dest, key, "download", c.contentLengthLong) }
        } finally {
            c.disconnect()
        }
    }

    private fun copyWithProgress(input: InputStream, dest: File, key: String, phase: String, total: Long) {
        if (total > MAX_BYTES) throw IOException("That download is too large.")
        val buf = ByteArray(64 * 1024)
        var done = 0L
        var last = 0L
        FileOutputStream(dest).use { out ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                if (done > MAX_BYTES) throw IOException("That download is too large.")
                val now = System.currentTimeMillis()
                if (now - last > 120) {
                    last = now
                    listener.onProgress(key, phase, done, total)
                }
            }
        }
        listener.onProgress(key, phase, done, if (total > 0) total else done)
    }

    private fun extract(zip: File, dest: File, key: String) {
        dest.mkdirs()
        val destCanon = dest.canonicalPath
        ZipFile(zip).use { zf ->
            val entries = zf.entries().toList()
            val total = entries.sumOf { maxOf(it.size, 0L) }
            if (total > MAX_BYTES) throw IOException("This archive is too large.")
            if (dest.usableSpace < total + 64L * 1024 * 1024) throw IOException("Not enough free storage on this device.")
            var done = 0L
            var last = 0L
            val buf = ByteArray(64 * 1024)
            for (e in entries) {
                val name = e.name.replace('\\', '/')
                if (name.startsWith("__MACOSX/") || name.contains("/__MACOSX/")) continue
                val out = File(dest, name).canonicalFile
                if (out.path != destCanon && !out.path.startsWith(destCanon + File.separator)) {
                    throw IOException("Unsafe path in archive: ${e.name}")
                }
                if (e.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                zf.getInputStream(e).use { input ->
                    FileOutputStream(out).use { o ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            if (done > MAX_BYTES) throw IOException("This archive is too large.")
                            val now = System.currentTimeMillis()
                            if (now - last > 120) {
                                last = now
                                listener.onProgress(key, "extract", done, total)
                            }
                        }
                    }
                }
            }
            listener.onProgress(key, "extract", total, total)
        }
    }

    /** The folder that holds index.html: the archive root, or its single top-level folder. */
    private fun findRoot(staging: File): File? {
        if (File(staging, "index.html").isFile) return staging
        val dirs = staging.listFiles()?.filter { it.isDirectory } ?: return null
        return dirs.singleOrNull { File(it, "index.html").isFile }
    }

    private val nameRe = Regex("^(.+?)-web-v?(\\d[0-9A-Za-z.+_-]*?)(?:\\.zip)?$")

    private fun finishInstall(key: String, zip: File, idHint: String?, versionHint: String?, fileNameHint: String?) {
        val staging = File(registry.root, ".staging-${System.nanoTime()}")
        try {
            extract(zip, staging, key)
            val appRoot = findRoot(staging)
                ?: throw IOException("This archive has no index.html, so it isn't a web build.")
            val html = File(appRoot, "index.html").readText()

            val folder = if (appRoot == staging) null else appRoot.name
            val inferred = listOfNotNull(folder, fileNameHint).firstNotNullOfOrNull { nameRe.find(it) }
            val title = Regex("<title>\\s*([^<]*?)\\s*</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

            val id = sanitizeId(idHint ?: inferred?.groupValues?.get(1) ?: title ?: "app-${System.nanoTime() % 100000}")
            if (id.isEmpty() || id == SHELL_ID || id == "icon") throw IOException("Couldn't work out a name for this app.")
            val rawVersion = versionHint ?: inferred?.groupValues?.get(2) ?: "0"
            // uploads/downloads sometimes turn dots into underscores ("0_5_0")
            val version = if (!rawVersion.contains('.') && rawVersion.contains('_')) rawVersion.replace('_', '.') else rawVersion
            val name = title ?: id
            ensureNotRunning(id)

            val manifest = JSONObject().put("id", id).put("name", name).put("version", version)
            findIcon(appRoot, html)?.let { manifest.put("icon", it) }
            File(appRoot, "manifest.json").writeText(manifest.toString())

            val target = registry.dirOf(id)
            val backup = File(registry.root, ".old-$id-${System.nanoTime()}")
            if (target.exists() && !target.renameTo(backup)) throw IOException("Couldn't replace the existing copy of $name.")
            if (!appRoot.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                throw IOException("Couldn't move $name into place.")
            }
            backup.deleteRecursively()

            val info = registry.find(id) ?: throw IOException("Installed, but the app didn't register.")
            listener.onInstalled(key, info)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun findIcon(root: File, html: String): String? {
        val patterns = listOf(
            Regex("<link[^>]*\\brel=[\"']icon[\"'][^>]*\\bhref=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
            Regex("<link[^>]*\\bhref=[\"']([^\"']+)[\"'][^>]*\\brel=[\"']icon[\"']", RegexOption.IGNORE_CASE),
        )
        for (p in patterns) {
            val href = p.find(html)?.groupValues?.get(1)?.removePrefix("./")?.substringBefore('?') ?: continue
            if (!href.startsWith("/") && !href.contains("..") && !href.contains(":") && File(root, href).isFile) return href
        }
        return root.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".png") && it.name.startsWith("ai.") }?.name
    }

    companion object {
        private const val MAX_BYTES = 1_500_000_000L
    }
}
