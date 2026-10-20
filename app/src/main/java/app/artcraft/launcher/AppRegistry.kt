package app.artcraft.launcher

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class AppInfo(
    val id: String,
    val name: String,
    val version: String,
    /** Path of the icon inside the app folder, or null. */
    val icon: String?,
    /** A copy lives in app storage (downloaded / imported). It wins over a bundled copy. */
    val installed: Boolean,
    /** A copy ships inside the APK. */
    val bundled: Boolean,
    val bundledVersion: String?,
)

/**
 * Apps come from two places:
 *  - assets/apps/<id>/   shipped inside the APK (read-only)
 *  - filesDir/apps/<id>/ installed at runtime (downloads, imported zips, updates)
 * Both have a manifest.json: {"id","name","version","icon"?}. An installed copy overrides a bundled one.
 */
class AppRegistry(private val ctx: Context) {
    val root = File(ctx.filesDir, "apps").apply { mkdirs() }

    fun dirOf(id: String) = File(root, id)

    private fun readInstalled(id: String): JSONObject? {
        val d = dirOf(id)
        val m = File(d, "manifest.json")
        if (!File(d, "index.html").isFile || !m.isFile) return null
        return try {
            JSONObject(m.readText())
        } catch (e: Exception) {
            null
        }
    }

    private fun readBundled(id: String): JSONObject? = try {
        ctx.assets.open("apps/$id/manifest.json").use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
    } catch (e: Exception) {
        null
    }

    private fun bundledIds(): List<String> = try {
        ctx.assets.list("apps")?.toList() ?: emptyList()
    } catch (e: IOException) {
        emptyList()
    }

    private fun installedIds(): List<String> =
        root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.map { it.name } ?: emptyList()

    fun find(id: String): AppInfo? {
        if (!isValidId(id)) return null
        val inst = readInstalled(id)
        val bund = readBundled(id)
        val m = inst ?: bund ?: return null
        val icon = m.optString("icon", "")
        return AppInfo(
            id = id,
            name = m.optString("name", id).ifEmpty { id },
            version = m.optString("version", ""),
            icon = if (icon.isEmpty()) null else icon,
            installed = inst != null,
            bundled = bund != null,
            bundledVersion = bund?.optString("version", ""),
        )
    }

    fun list(): List<AppInfo> =
        (bundledIds() + installedIds()).toSortedSet().mapNotNull { find(it) }.sortedBy { it.name.lowercase() }

    /** Removes the installed copy. A bundled copy (if any) becomes active again. */
    fun uninstall(id: String): Boolean {
        if (!isValidId(id)) return false
        return dirOf(id).deleteRecursively()
    }

    /** Deletes half-finished installs left behind by a crash or kill. */
    fun cleanTemp() {
        root.listFiles()?.filter { it.name.startsWith(".") }?.forEach { it.deleteRecursively() }
        ctx.cacheDir.listFiles()?.filter { it.name.startsWith("dl-") || it.name.startsWith("save-") }?.forEach { it.delete() }
    }
}
