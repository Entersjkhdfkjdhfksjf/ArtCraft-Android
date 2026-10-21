package app.artcraft.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * One Activity, many WebViews:
 *  - the "shell" WebView is the launcher UI (app grid + store), assets/shell/index.html
 *  - every opened app gets its own WebView that stays alive while you go back to the launcher,
 *    so switching is instant and unsaved work isn't lost.
 */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()

    private lateinit var registry: AppRegistry
    private lateinit var server: AppServer
    private lateinit var installer: Installer
    private lateinit var prefs: SharedPreferences

    private lateinit var rootView: LinearLayout
    private lateinit var bar: LinearLayout
    private lateinit var barTitle: TextView
    private lateinit var container: FrameLayout

    private var shell: WebView? = null
    private val sessions = LinkedHashMap<String, WebView>()
    private val running: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var foreground: String? = null
    private var immersive = false
    private var imeShown = false      // API 30+: real keyboard visibility from insets
    private var kbdToggle = false     // older devices: our own idea of it

    // file chooser / save / permission state
    private var pendingChooser: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null
    private var pendingGrant: List<String> = emptyList()
    private class PendingSave(val token: String, val name: String, val mime: String, val file: File)
    private val saves = ConcurrentHashMap<String, PendingSave>()
    private val saveQueue = ArrayDeque<PendingSave>()
    private var saveInFlight: PendingSave? = null

    private val installerListener = object : Installer.Listener {
        override fun onProgress(key: String, phase: String, done: Long, total: Long) {
            emit(JSONObject().put("type", "progress").put("key", key).put("phase", phase).put("done", done).put("total", total))
        }

        override fun onLatest(repo: String, version: String?, error: String?) {
            val o = JSONObject().put("type", "latest").put("repo", repo)
            if (version != null) o.put("version", version)
            if (error != null) o.put("error", error)
            emit(o)
        }

        override fun onInstalled(key: String, app: AppInfo) {
            emit(JSONObject().put("type", "installed").put("key", key).put("id", app.id).put("name", app.name).put("version", app.version))
        }

        override fun onError(key: String, message: String) {
            emit(JSONObject().put("type", "error").put("key", key).put("message", message))
        }
    }

    // ---- lifecycle --------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registry = AppRegistry(this)
        server = AppServer(this, registry)
        installer = Installer(this, registry, installerListener) { id -> running.contains(id) }
        prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        immersive = prefs.getBoolean("immersive", false)

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) WebView.setWebContentsDebuggingEnabled(true)

        buildUi()
        createShell()
    }

    override fun onPause() {
        super.onPause()
        currentWebView()?.onPause()
    }

    override fun onResume() {
        super.onResume()
        currentWebView()?.onResume()
    }

    override fun onDestroy() {
        sessions.values.toList().forEach { destroyWebView(it) }
        sessions.clear()
        shell?.let { destroyWebView(it) }
        shell = null
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        if (foreground != null) {
            showShell()
            return
        }
        val s = shell
        if (s == null) {
            moveTaskToBack(true)
            return
        }
        // Let the launcher UI close a menu / sub-page first.
        s.evaluateJavascript("window.__launcher && window.__launcher.onBack ? window.__launcher.onBack() : false") { r ->
            if (r != "true") moveTaskToBack(true)
        }
    }

    private fun currentWebView(): WebView? = foreground?.let { sessions[it] } ?: shell

    // ---- UI ---------------------------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF14151A.toInt())
        }

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1B1C22.toInt())
            visibility = View.GONE
        }
        bar.addView(barButton("\u2039  Launcher") { showShell() })
        barTitle = TextView(this).apply {
            setTextColor(0xFFE8E8EA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        bar.addView(barTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(barButton("Keyboard") { toggleKeyboard() })
        bar.addView(barButton("Close") { foreground?.let { closeApp(it) } })
        rootView.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))

        container = FrameLayout(this)
        rootView.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(rootView)

        rootView.setOnApplyWindowInsetsListener { v, insets ->
            var l: Int
            var t: Int
            var r: Int
            var b: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                l = bars.left; t = bars.top; r = bars.right; b = maxOf(bars.bottom, ime.bottom)
                val visible = insets.isVisible(WindowInsets.Type.ime())
                if (imeShown && !visible) onImeHidden()
                imeShown = visible
            } else {
                @Suppress("DEPRECATION")
                run {
                    l = insets.systemWindowInsetLeft; t = insets.systemWindowInsetTop
                    r = insets.systemWindowInsetRight; b = insets.systemWindowInsetBottom
                }
            }
            v.setPadding(l, t, r, b)
            insets
        }
    }

    private fun barButton(label: String, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.setTextColor(0xFFE8E8EA.toInt())
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(16), 0, dp(16), 0)
        val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        tv.background = ta.getDrawable(0)
        ta.recycle()
        tv.setOnClickListener { onClick() }
        tv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        return tv
    }

    private fun applyImmersive(active: Boolean) {
        bar.visibility = if (foreground != null && !active) View.VISIBLE else View.GONE
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (active) {
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsets.Type.systemBars())
            } else {
                c.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (active) {
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    // ---- WebView creation -------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(client: WebViewClient, chrome: WebChromeClient): WebView {
        val wv = WebView(this)
        wv.setBackgroundColor(0xFF14151A.toInt())
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        // Needed so files picked with the system file picker can be read by the page.
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.textZoom = 100
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        wv.webViewClient = client
        wv.webChromeClient = chrome
        return wv
    }

    @SuppressLint("JavascriptInterface")
    private fun createShell() {
        val wv = newWebView(ShellClient(), ShellChrome())
        wv.addJavascriptInterface(ShellBridge(), "Launcher")
        wv.setOnLongClickListener { true }
        container.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        shell = wv
        wv.loadUrl(originOf(SHELL_ID) + "/index.html")
        if (foreground != null) wv.visibility = View.GONE
    }

    @SuppressLint("JavascriptInterface")
    private fun createSession(id: String): WebView {
        val wv = newWebView(AppClient(id), AppChrome())
        wv.addJavascriptInterface(SaveBridge(id), "ArtcraftHost")
        wv.setOnLongClickListener { true }   // no text-selection popups over a canvas
        wv.isHapticFeedbackEnabled = false
        container.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sessions[id] = wv
        running.add(id)
        wv.loadUrl(originOf(id) + "/index.html")
        return wv
    }

    private fun destroyWebView(wv: WebView) {
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.stopLoading()
        wv.destroy()
    }

    // ---- navigation between launcher and apps -----------------------------------------------

    private fun showShell() {
        foreground?.let { id ->
            sessions[id]?.let {
                it.visibility = View.GONE
                it.onPause()
            }
        }
        foreground?.let { sessions[it]?.evaluateJavascript("window.__acKeyboard&&window.__acKeyboard(false)", null) }
        hideKeyboard()
        foreground = null
        shell?.let {
            it.visibility = View.VISIBLE
            it.onResume()
            it.requestFocus()
        }
        applyImmersive(false)
        emit(JSONObject().put("type", "apps"))
    }

    private fun openApp(id: String) {
        val info = registry.find(id)
        if (info == null) {
            toast("That app isn't installed.")
            return
        }
        val wv = sessions[id] ?: createSession(id)
        shell?.let {
            it.visibility = View.GONE
            it.onPause()
        }
        for ((otherId, other) in sessions) {
            if (otherId != id) {
                other.visibility = View.GONE
                other.onPause()
            }
        }
        wv.visibility = View.VISIBLE
        wv.onResume()
        wv.requestFocus()
        foreground = id
        barTitle.text = info.name
        applyImmersive(immersive)
    }

    private fun closeApp(id: String) {
        sessions.remove(id)?.let { destroyWebView(it) }
        running.remove(id)
        if (foreground == id) showShell() else emit(JSONObject().put("type", "apps"))
    }

    private fun removeApp(id: String) {
        val info = registry.find(id) ?: return
        if (!info.installed) {
            toast("Built-in apps can't be removed.")
            return
        }
        closeApp(id)
        registry.uninstall(id)
        emit(JSONObject().put("type", "apps"))
    }

    private fun handleRendererGone(id: String, view: WebView, crashed: Boolean) {
        main.post {
            if (id == SHELL_ID) {
                if (shell === view) {
                    destroyWebView(view)
                    shell = null
                    createShell()
                }
                return@post
            }
            if (sessions[id] === view) {
                val name = registry.find(id)?.name ?: id
                sessions.remove(id)
                running.remove(id)
                destroyWebView(view)
                toast(if (crashed) "$name crashed and was closed." else "$name was closed because the device ran low on memory.")
                if (foreground == id) showShell()
            }
        }
    }

    private fun openExternal(uri: Uri) {
        val scheme = uri.scheme
        if (scheme != "http" && scheme != "https" && scheme != "mailto") return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            toast("No app can open that link.")
        }
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    // ---- WebView clients --------------------------------------------------------------------

    private abstract inner class BaseClient(val id: String) : WebViewClient() {
        private val ownHost = id + HOST_SUFFIX

        abstract fun foreign(uri: Uri): WebResourceResponse?

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val uri = request.url
            val scheme = uri.scheme
            if (scheme != "https" && scheme != "http") return null   // blob:, data:, about:
            if (scheme == "https" && uri.host == ownHost) return server.serve(uri)
            return foreign(uri)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (uri.host == ownHost) return false
            openExternal(uri)
            return true
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Diag.d("webview", "renderer gone for $id (crashed=${detail.didCrash()})")
            handleRendererGone(id, view, detail.didCrash())
            return true
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            Diag.d("http", "$id: ${errorResponse.statusCode} ${request.url}")
        }
    }

    /** Apps are offline sandboxes: anything outside their own origin is blocked. */
    private inner class AppClient(id: String) : BaseClient(id) {
        override fun foreign(uri: Uri): WebResourceResponse? = AppServer.blocked()
    }

    /** The launcher UI may only load store icons from GitHub. */
    private inner class ShellClient : BaseClient(SHELL_ID) {
        override fun foreign(uri: Uri): WebResourceResponse? =
            if (uri.host == "raw.githubusercontent.com") null else AppServer.blocked()
    }

    private inner class ShellChrome : WebChromeClient() {
        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
            Log.d("Launcher", "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
            return true
        }
    }

    private inner class AppChrome : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            Diag.d("open", "page asked for a file: mode=${params.mode} accept=${params.acceptTypes.joinToString(",")}")
            pendingChooser?.onReceiveValue(null)
            pendingChooser = callback
            val multiple = params.mode == FileChooserParams.MODE_OPEN_MULTIPLE
            // "*/*" on purpose: the apps validate extensions themselves, while filtering by MIME type hides
            // perfectly good .docx / .deckcraft files on many devices.
            val primary = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            try {
                startActivityForResult(primary, REQ_FILE_CHOOSER)
            } catch (e: Exception) {
                Diag.d("open", "OPEN_DOCUMENT failed: $e; trying GET_CONTENT")
                try {
                    val alt = Intent(Intent.ACTION_GET_CONTENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
                    startActivityForResult(Intent.createChooser(alt, "Open file"), REQ_FILE_CHOOSER)
                } catch (e2: Exception) {
                    Diag.d("open", "GET_CONTENT failed too: $e2")
                    pendingChooser = null
                    callback.onReceiveValue(null)
                    toast("No file picker is available on this device.")
                }
            }
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            main.post { handlePermission(request) }
        }

        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
            val lvl = m.messageLevel()
            if (lvl == ConsoleMessage.MessageLevel.ERROR || lvl == ConsoleMessage.MessageLevel.WARNING) {
                Diag.d("js", "${lvl.name.lowercase()}: ${m.message().take(300)} (${m.sourceId()?.substringAfterLast('/')}:${m.lineNumber()})")
            }
            return true
        }
    }

    // ---- mic / camera -----------------------------------------------------------------------

    private fun permissionFor(resource: String): String? = when (resource) {
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
        else -> null
    }

    private fun handlePermission(req: PermissionRequest) {
        val host = req.origin.host
        if (host == null || !host.endsWith(HOST_SUFFIX) || host.removeSuffix(HOST_SUFFIX) == SHELL_ID) {
            req.deny()
            return
        }
        val wanted = req.resources.filter { permissionFor(it) != null }
        if (wanted.isEmpty()) {
            req.deny()
            return
        }
        val missing = wanted.mapNotNull { permissionFor(it) }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            req.grant(wanted.toTypedArray())
        } else {
            pendingPermission?.deny()
            pendingPermission = req
            pendingGrant = wanted
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        val req = pendingPermission ?: return
        pendingPermission = null
        val ok = pendingGrant.filter { r -> permissionFor(r)?.let { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } == true }
        if (ok.isEmpty()) req.deny() else req.grant(ok.toTypedArray())
    }

    // ---- activity results: file chooser, zip picker, save-as --------------------------------

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_FILE_CHOOSER -> {
                val cb = pendingChooser
                pendingChooser = null
                val uris = if (resultCode == RESULT_OK && data != null) collectUris(data) else null
                Diag.d("open", "picker returned: result=$resultCode files=${uris?.size ?: 0}")
                cb?.onReceiveValue(uris)
            }
            REQ_PICK_ZIP -> {
                val uri = if (resultCode == RESULT_OK) data?.data else null
                if (uri != null) installer.installFromUri("file", uri)
                else emit(JSONObject().put("type", "cancelled").put("key", "file"))
            }
            REQ_SAVE -> {
                val s = saveInFlight
                saveInFlight = null
                if (s != null) completeSave(s, if (resultCode == RESULT_OK) data?.data else null)
                pumpSaves()
            }
        }
    }

    private fun collectUris(d: Intent): Array<Uri>? {
        val clip = d.clipData
        if (clip != null && clip.itemCount > 0) return Array(clip.itemCount) { clip.getItemAt(it).uri }
        return d.data?.let { arrayOf(it) }
    }

    @Suppress("DEPRECATION")
    private fun pickZip() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        try {
            startActivityForResult(i, REQ_PICK_ZIP)
        } catch (e: Exception) {
            toast("No file picker is available.")
        }
    }

    private fun enqueueSave(s: PendingSave) {
        saveQueue.addLast(s)
        pumpSaves()
    }

    @Suppress("DEPRECATION")
    private fun pumpSaves() {
        if (saveInFlight != null) return
        val s = saveQueue.removeFirstOrNull() ?: return
        saveInFlight = s
        val size = s.file.length()
        Diag.d("save", "asking where to save ${s.name} (${s.mime}, $size bytes)")
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(s.mime.ifEmpty { "application/octet-stream" })
            .putExtra(Intent.EXTRA_TITLE, s.name)
        try {
            startActivityForResult(i, REQ_SAVE)
        } catch (e: Exception) {
            Diag.d("save", "Save-as dialog failed ($e); saving to Downloads instead")
            saveInFlight = null
            saveToDownloads(s)
            pumpSaves()
        }
    }

    private fun completeSave(s: PendingSave, uri: Uri?) {
        saves.remove(s.token)
        if (uri == null) {
            Diag.d("save", "cancelled: ${s.name}")
            s.file.delete()
            toast("Save cancelled.")
            return
        }
        io.execute {
            try {
                val out = contentResolver.openOutputStream(uri, "w") ?: throw IOException("Couldn't open the destination.")
                out.use { o -> s.file.inputStream().use { it.copyTo(o) } }
                Diag.d("save", "saved ${s.name} to $uri")
                toast("Saved ${s.name}")
                s.file.delete()
            } catch (e: Exception) {
                Diag.d("save", "writing to $uri failed: $e; saving to Downloads instead")
                saveToDownloads(s)
            }
        }
    }

    /** Fallback that needs no dialog: Downloads/Artcraft. */
    private fun saveToDownloads(s: PendingSave) {
        io.execute {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val v = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, s.name)
                        put(MediaStore.Downloads.MIME_TYPE, s.mime.ifEmpty { "application/octet-stream" })
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Artcraft")
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                        ?: throw IOException("Downloads isn't available.")
                    val out = contentResolver.openOutputStream(uri) ?: throw IOException("Couldn't write to Downloads.")
                    out.use { o -> s.file.inputStream().use { it.copyTo(o) } }
                    toast("Saved to Downloads/Artcraft/${s.name}")
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: throw IOException("No storage available.")
                    dir.mkdirs()
                    val dest = File(dir, s.name)
                    s.file.inputStream().use { i -> dest.outputStream().use { i.copyTo(it) } }
                    toast("Saved to ${dest.absolutePath}")
                }
                Diag.d("save", "saved ${s.name} to Downloads")
            } catch (e: Exception) {
                Diag.d("save", "Downloads fallback failed: $e")
                toast("Couldn't save ${s.name}: ${e.message}")
            } finally {
                s.file.delete()
                saves.remove(s.token)
            }
        }
    }

    // ---- keyboard ---------------------------------------------------------------------------

    private fun showKeyboardFor(wv: WebView) {
        wv.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(wv, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(rootView.windowToken, 0)
        kbdToggle = false
    }

    private fun toggleKeyboard() {
        val wv = foreground?.let { sessions[it] } ?: return
        val on = !(if (Build.VERSION.SDK_INT >= 30) imeShown else kbdToggle)
        kbdToggle = on
        Diag.d("keyboard", "toggle -> $on")
        wv.evaluateJavascript("window.__acKeyboard&&window.__acKeyboard($on)", null)
        if (on) showKeyboardFor(wv) else hideKeyboard()
    }

    /** The user dismissed the keyboard (e.g. with Back): let the page stop holding its text input. */
    private fun onImeHidden() {
        kbdToggle = false
        foreground?.let { sessions[it] }?.evaluateJavascript("window.__acKeyboard&&window.__acKeyboard(false)", null)
    }

    // ---- bridges ----------------------------------------------------------------------------

    private fun emit(o: JSONObject) {
        val json = o.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        val js = "window.__launcher&&window.__launcher.onEvent($json)"
        main.post { shell?.evaluateJavascript(js, null) }
    }

    private fun appsJson(): JSONArray {
        val arr = JSONArray()
        for (a in registry.list()) {
            val o = JSONObject()
                .put("id", a.id).put("name", a.name).put("version", a.version)
                .put("source", if (a.installed) "installed" else "bundled")
                .put("hasBuiltIn", a.bundled)
                .put("running", running.contains(a.id))
                .put("touchScroll", prefs.getBoolean("touchscroll.${a.id}", true))
            if (a.icon != null) o.put("icon", "/icon/${a.id}")
            if (a.bundledVersion != null) o.put("builtInVersion", a.bundledVersion)
            arr.put(o)
        }
        return arr
    }

    /** Exposed to the launcher UI only (window.Launcher). */
    inner class ShellBridge {
        @JavascriptInterface fun apps(): String = appsJson().toString()
        @JavascriptInterface fun open(id: String) { main.post { openApp(id) } }
        @JavascriptInterface fun close(id: String) { main.post { closeApp(id) } }
        @JavascriptInterface fun remove(id: String) { main.post { removeApp(id) } }
        @JavascriptInterface fun latest(repo: String) { installer.checkLatest(repo) }
        @JavascriptInterface fun install(key: String, repo: String) { installer.installFromRepo(key, repo) }
        @JavascriptInterface fun installUrl(key: String, url: String) { installer.installFromUrl(key, url) }
        @JavascriptInterface fun pickZip() { main.post { this@MainActivity.pickZip() } }
        @JavascriptInterface fun getTouchScroll(id: String): Boolean = prefs.getBoolean("touchscroll.$id", true)
        @JavascriptInterface fun setTouchScroll(id: String, on: Boolean) {
            prefs.edit().putBoolean("touchscroll.$id", on).apply()
        }
        @JavascriptInterface fun diag(): String = Diag.dump()
        @JavascriptInterface fun copy(text: String) {
            main.post {
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Artcraft diagnostics", text))
                toast("Copied")
            }
        }
        @JavascriptInterface fun getImmersive(): Boolean = immersive
        @JavascriptInterface fun setImmersive(on: Boolean) {
            main.post {
                immersive = on
                prefs.edit().putBoolean("immersive", on).apply()
            }
        }
    }

    /** Exposed to apps (window.ArtcraftHost): lets them "download" blobs through a Save-as dialog. */
    inner class SaveBridge(private val appId: String) {
        @JavascriptInterface fun touchScroll(): Boolean = prefs.getBoolean("touchscroll.$appId", true)

        @JavascriptInterface fun showKeyboard() {
            main.post {
                if (foreground == appId) sessions[appId]?.let { showKeyboardFor(it) }
            }
        }

        @JavascriptInterface fun hideKeyboard() {
            main.post { if (foreground == appId) this@MainActivity.hideKeyboard() }
        }

        @JavascriptInterface fun beginSave(name: String, mime: String): String {
            Diag.d("save", "$appId wants to save $name ($mime)")
            val token = UUID.randomUUID().toString()
            val f = File(cacheDir, "save-$token.part")
            f.createNewFile()
            saves[token] = PendingSave(token, name.ifBlank { "download" }, mime, f)
            return token
        }

        @JavascriptInterface fun writeChunk(token: String, b64: String): Boolean {
            val s = saves[token] ?: return false
            return try {
                FileOutputStream(s.file, true).use { it.write(Base64.decode(b64, Base64.DEFAULT)) }
                true
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface fun finishSave(token: String) {
            val s = saves[token]
            if (s == null) {
                Diag.d("save", "finishSave: unknown token")
                return
            }
            main.post { enqueueSave(s) }
        }

        @JavascriptInterface fun abortSave(token: String) {
            saves.remove(token)?.file?.delete()
        }
    }

    companion object {
        private const val REQ_FILE_CHOOSER = 1
        private const val REQ_PICK_ZIP = 2
        private const val REQ_SAVE = 3
        private const val REQ_PERMS = 4
    }
}
