package com.kurdistan.iptv

import android.app.PictureInPictureParams
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioManager
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.LruCache
import android.util.Rational
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    /** what radio mode's notification shows, and the session it is built around */
    companion object {
        @Volatile var liveSession: MediaSession? = null
        @Volatile var nowTitle: String = ""
    }

    /* Many IPTV / Xtream servers only answer to VLC's user agent... */
    private val UA = "VLC/3.0.20 LibVLC/3.0.20"

    /* ...but a panel behind Cloudflare blocks it, so fall back to a browser. */
    private val BROWSER_UA = "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /* Fake host used by the page to route downloads through native code,
       which is not subject to the WebView's CORS rules. */
    private val PROXY_HOST = "kiptv.local"

    /** every container the player can be asked for, in fallback order */
    private val ORDER: List<String?> =
        listOf(MimeTypes.APPLICATION_M3U8, null, MimeTypes.VIDEO_MP2T, MimeTypes.APPLICATION_MPD)

    /* a live channel that drops is picked up again and again, a little
       slower each time, so a server's hiccup never ends the picture */
    private val MAX_RECONNECTS = 15
    private val RECONNECT_DELAY_MS = 1200L

    /** how streams are reached: agents, certificates, DNS, the other output */
    private lateinit var net: StreamNet
    /** the ways to ask for the item on screen, which one is in use, which failed */
    private var ways: List<Way> = emptyList()
    private var wayIdx = 0
    private val waysTried = HashSet<String>()
    /** every way tried for the item on screen and what it got - shown with an error */
    private val waysTrail = ArrayList<String>()
    /** when the item on screen was first asked for: a server that is truly
     *  down gets its error in well under a minute, not after every way */
    private var itemStartAt = 0L
    private val WAYS_BUDGET_MS = 45_000L

    private lateinit var webView: WebView
    private lateinit var playerLayer: View
    private lateinit var playerView: PlayerView
    private lateinit var loading: ProgressBar
    private lateinit var errorBox: LinearLayout
    private lateinit var txtError: TextView
    private lateinit var txtTitle: TextView

    private val handler = Handler(Looper.getMainLooper())

    /* ---------- updating from inside the app ----------
       Two builds come out of the same code. The one from GitHub / Uptodown
       may install its own update (it carries REQUEST_INSTALL_PACKAGES); the
       one from Google Play may not - Play forbids it - so it asks Google
       Play for its own full-screen update instead. Either way the person
       never leaves the app to fetch a file. */
    private val playUpdates by lazy {
        com.google.android.play.core.appupdate.AppUpdateManagerFactory.create(this)
    }
    private val playUpdateLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { r -> if (r.resultCode != RESULT_OK) updToPage("cancel", 0, 0) }

    /** progress and outcome back to the page: window.kiptvUpd(state, done, total) */
    private fun updToPage(st: String, a: Long, b: Long) = runOnUiThread {
        if (::webView.isInitialized)
            webView.evaluateJavascript("window.kiptvUpd&&window.kiptvUpd('$st',$a,$b)", null)
    }

    /** true for the GitHub / Uptodown build, false for the Google Play one */
    private fun canSelfInstall(): Boolean = try {
        @Suppress("DEPRECATION")
        val p = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        p.requestedPermissions?.contains("android.permission.REQUEST_INSTALL_PACKAGES") == true
    } catch (e: Exception) { false }

    private fun playUpdate(resumeOnly: Boolean = false) {
        playUpdates.appUpdateInfo
            .addOnSuccessListener { info ->
                val avail = info.updateAvailability()
                val go = avail == com.google.android.play.core.install.model.UpdateAvailability.UPDATE_AVAILABLE ||
                    avail == com.google.android.play.core.install.model.UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
                if (resumeOnly && avail != com.google.android.play.core.install.model.UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS)
                    return@addOnSuccessListener
                if (go && info.isUpdateTypeAllowed(com.google.android.play.core.install.model.AppUpdateType.IMMEDIATE)) {
                    try {
                        playUpdates.startUpdateFlowForResult(
                            info, playUpdateLauncher,
                            com.google.android.play.core.appupdate.AppUpdateOptions
                                .newBuilder(com.google.android.play.core.install.model.AppUpdateType.IMMEDIATE).build()
                        )
                    } catch (e: Exception) { updToPage("noplay", 0, 0) }
                } else if (!resumeOnly) updToPage("noplay", 0, 0)
            }
            .addOnFailureListener { if (!resumeOnly) updToPage("noplay", 0, 0) }
    }

    private lateinit var topBar: View

    private var player: ExoPlayer? = null
    private var currentUrl: String? = null
    private var currentTitle: String? = null

    /** the queue the next / previous buttons walk through */
    private var urls: List<String> = emptyList()
    private var titles: List<String> = emptyList()

    /** some channels only answer to their own agent / referrer */
    private var uas: List<String?> = emptyList()
    /** films and episodes whose address does not say so ("v":1 from the page) */
    private var vodSet: Set<String> = emptySet()

    /** a subtitle file the page made ready for one stream (OpenSubtitles, or Kurdish by Claude) */
    private data class SubF(val path: String, val lang: String, val label: String, val def: Boolean)
    private var subsByUrl: Map<String, List<SubF>> = emptyMap()
    private var refs: List<String?> = emptyList()
    private var curUa: String? = null
    private var curRef: String? = null
    private var builtUa: String? = null
    private var builtRef: String? = null

    private var startAtMs = 0L
    private val posTicker = object : Runnable {
        override fun run() {
            try { reportPosition() } catch (e: Exception) { /* never crash on a tick */ }
            handler.postDelayed(this, 5000)
        }
    }

    private var plan: List<String?> = emptyList()
    private var lastIndex = -1
    private var step = 0
    private var hasPlayed = false
    private var reconnects = 0
    /** the address the stream really lives at, once we have followed it */
    private var probed = false
    private var resolvedUrl: String? = null
    /** set when we have given up: stops every retry loop dead */
    private var dead = false

    /* ---- picture shape + screen lock: they change only how the picture is
       shown and whether the screen takes touches, never how a stream opens ---- */
    private val ASPECT_NAMES = arrayOf("Fit", "Fill", "Zoom", "16:9", "4:3")
    private var aspectIdx = 0
    private var locked = false
    private lateinit var btnAspect: TextView
    private lateinit var btnLock: TextView
    /** Fit / ⚙ / 🔒 - a row of our own at the bottom, under the seek bar */
    private lateinit var bottomBar: LinearLayout
    private lateinit var btnUnlock: TextView
    private lateinit var lockOverlay: View
    private lateinit var txtHint: TextView
    private val hideUnlock = Runnable { if (locked) btnUnlock.visibility = View.GONE }
    private val hideHint = Runnable { txtHint.visibility = View.GONE }

    /* ---- audio / subtitles / quality: choose among the tracks the stream
       already has - how the stream is opened stays exactly the same ---- */
    private lateinit var btnTracks: TextView
    private lateinit var panelScrim: View
    private lateinit var panel: ScrollView
    private lateinit var panelBox: LinearLayout
    private var qualityPinned = false
    /** the app's language, sent by the page (en / ku / ar) */
    @Volatile private var uiLang = "en"
    private val SUB_SCALES = floatArrayOf(0.8f, 1f, 1.3f, 1.6f)

    /* ---- how fast a film plays, and keeping the sound on with the app away.
       Neither changes how a stream is opened. ---- */
    private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    /** lets the system show play / pause on the lock screen and headsets */
    private var session: MediaSession? = null

    /* ---- swipe up / down: left half = brightness, right half = volume ---- */
    private lateinit var swipe: SwipeGesture
    private var swipeStartBright = 0.5f
    private var swipeStartVol = 0
    /** the brightness picked in the player this session (-1 = the phone's own) */
    private var playerBrightness = -1f

    /* ---- picture-in-picture: Home while watching keeps it playing small ---- */
    private var inPip = false
    private var stopped = false
    /** when the small window last ended (it can end just before the app stops) */
    private var pipEndedAt = 0L

    /** the server's answer when a stream is refused (403, 404 ...), shown with the error */
    private var lastHttp = 0

    /** Android TV / Google TV / Fire TV: no touch screen, used with a remote */
    private val isTv: Boolean by lazy {
        val mode = try {
            (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType
        } catch (e: Exception) { 0 }
        mode == Configuration.UI_MODE_TYPE_TELEVISION ||
            (try { !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) } catch (e: Exception) { false })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        webView = findViewById(R.id.webView)
        playerLayer = findViewById(R.id.playerLayer)
        playerView = findViewById(R.id.playerView)
        loading = findViewById(R.id.loading)
        errorBox = findViewById(R.id.errorBox)
        txtError = findViewById(R.id.txtError)
        txtTitle = findViewById(R.id.txtTitle)
        topBar = findViewById(R.id.topBar)

        txtError.text = getString(R.string.player_error)
        findViewById<TextView>(R.id.btnRetry).apply {
            text = getString(R.string.retry)
            setOnClickListener {
                val i = try { player?.currentMediaItemIndex ?: 0 } catch (e: Exception) { 0 }
                if (urls.isNotEmpty()) {
                    try { buildAndStart(i) } catch (e: Exception) { showError("RETRY_FAILED") }
                }
            }
        }
        findViewById<TextView>(R.id.btnClose).setOnClickListener { hideNativePlayer() }
        findViewById<TextView>(R.id.liveBadge).text = getString(R.string.live)
        buildAspectAndLockUi()
        buildChansUi()
        focusRing(findViewById(R.id.btnClose), round = true)
        focusRing(findViewById(R.id.btnRetry), round = false, radiusDp = 14)

        playerView.controllerShowTimeoutMs = 3500
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                topBar.visibility = visibility
                bottomBar.visibility = visibility
            }
        )

        /* Some panels set a session cookie on the first request and reject
           every segment that comes back without it. VLC keeps cookies; the
           player does not, unless we give the JVM somewhere to put them. */
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(
                CookieManager().apply { setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER) }
            )
        }

        net = StreamNet(this)
        configureWebView()
        webView.loadUrl("file:///android_asset/index.html")
        webView.requestFocus()                /* a TV remote's keys go to the page */

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    playerLayer.visibility == View.VISIBLE && chansOn() -> closeChans()
                    playerLayer.visibility == View.VISIBLE && panelOn() -> closePanel()
                    playerLayer.visibility == View.VISIBLE && locked -> flashUnlock()
                    playerLayer.visibility == View.VISIBLE -> hideNativePlayer()
                    webView.canGoBack() -> webView.goBack()
                    else -> finish()
                }
            }
        })
    }

    // ---------------- webview + native download proxy ----------------

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.setBackgroundColor(0xFF070A0E.toInt())
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        WebView.setWebContentsDebuggingEnabled(false)
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(AndroidBridge(this), "AndroidPlayer")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                if (request.url.host != PROXY_HOST) return null
                val target = request.url.getQueryParameter("u") ?: return null
                val cors = hashMapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Access-Control-Allow-Headers" to "*",
                    "Access-Control-Allow-Methods" to "GET,OPTIONS",
                    "Cache-Control" to "no-store"
                )
                if (request.method.equals("OPTIONS", true)) {
                    return WebResourceResponse(
                        "text/plain", "utf-8", 200, "OK", cors, ByteArrayInputStream(ByteArray(0))
                    )
                }
                return try {
                    val conn = fetchAllowingCloudflare(target)
                    val code = conn.responseCode
                    val body = if (code in 200..299) conn.inputStream else conn.errorStream
                    WebResourceResponse(
                        "text/plain", "utf-8", code, if (code in 200..299) "OK" else "ERR",
                        cors, body ?: ByteArrayInputStream(ByteArray(0))
                    )
                } catch (e: Exception) {
                    WebResourceResponse(
                        "text/plain", "utf-8", 599, "ERR", cors,
                        ByteArrayInputStream(("PROXY_ERROR " + e.javaClass.simpleName + ": " + e.message)
                            .toByteArray(Charsets.UTF_8))
                    )
                }
            }
        }
    }

    /**
     * Some panels only answer to VLC, others sit behind Cloudflare which blocks it.
     * Try VLC first and retry as a browser when the first answer is a refusal.
     */
    private fun fetchAllowingCloudflare(target: String): HttpURLConnection {
        val first = openFollowingRedirects(target, UA)
        val code = first.responseCode
        if (code != 403 && code != 406 && code != 503) return first
        first.disconnect()
        return openFollowingRedirects(target, BROWSER_UA)
    }

    /** HttpURLConnection will not follow http -> https redirects, so do it by hand. */
    private fun openFollowingRedirects(startUrl: String, ua: String): HttpURLConnection {
        var url = startUrl
        var hops = 0
        while (true) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 30000
            c.readTimeout = 40000
            c.setRequestProperty("User-Agent", ua)
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            val code = c.responseCode
            if (code in 300..399 && hops < 5) {
                val next = c.getHeaderField("Location")
                c.disconnect()
                if (next.isNullOrBlank()) return URL(url).openConnection() as HttpURLConnection
                url = URL(URL(url), next).toString()
                hops++
                continue
            }
            return c
        }
    }

    inner class AndroidBridge(private val context: Context) {
        @android.webkit.JavascriptInterface
        fun playNative(url: String, title: String) {
            runOnUiThread {
                vodSet = emptySet()
                subsByUrl = emptyMap()
                openQueue(listOf(url), listOf(title), listOf(null), listOf(null), 0, 0L)
            }
        }

        /** the page asks this to show where the remote's focus is from the start */
        @android.webkit.JavascriptInterface
        fun isTv(): Boolean = this@MainActivity.isTv

        /**
         * "1.0|105" - the name a person reads, and the number that only ever
         * goes up. The page compares that number with the newest release to
         * decide whether there is anything to tell the user about.
         */
        @android.webkit.JavascriptInterface
        fun version(): String = try {
            val p = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
            (p.versionName ?: "") + "|" + code
        } catch (e: Exception) { "" }

        /**
         * Hands a plain web address to whatever the phone opens links with.
         * Only http and https, so the page can never start something else,
         * and the download and the install stay where the user can see them.
         */
        @android.webkit.JavascriptInterface
        fun openUrl(url: String) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) return
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) { /* nothing on the phone opens links */ }
            }
        }

        /**
         * In-app update.
         * Google Play build: Play's own full-screen update, inside the app.
         * GitHub / Uptodown build: the apk is downloaded into the app's own
         * cache (progress goes back to the page as window.kiptvUpd(state,
         * done, total)), then Android's installer opens on it. Either way the
         * person never leaves the app to fetch a file.
         */
        @android.webkit.JavascriptInterface
        fun installUpdate(url: String) {
            if (!canSelfInstall()) { runOnUiThread { playUpdate() }; return }
            if (!url.startsWith("https://") && !url.startsWith("http://")) return
            Thread {
                fun say(st: String, a: Long, b: Long) = updToPage(st, a, b)
                try {
                    val dir = java.io.File(cacheDir, "updates").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val f = java.io.File(dir, "update.apk")
                    val c = openFollowingRedirects(url, UA)
                    val total = c.contentLengthLong
                    var done = 0L
                    var lastTick = 0L
                    c.inputStream.use { inp ->
                        f.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val now = SystemClock.uptimeMillis()
                                if (now - lastTick > 150) { lastTick = now; say("run", done, total) }
                            }
                        }
                    }
                    c.disconnect()
                    say("run", done, done)
                    runOnUiThread {
                        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                            // one-time switch in Android's settings; the download is kept
                            say("perm", 0, 0)
                            try {
                                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:$packageName")))
                            } catch (e: Exception) { }
                        } else launchInstaller()
                    }
                } catch (e: Exception) { say("fail", 0, 0) }
            }.start()
        }

        /** after the user allowed installs, the page asks to carry on with the saved file */
        @android.webkit.JavascriptInterface
        fun continueInstall() {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls())
                    updToPage("perm", 0, 0)              /* still not allowed: say so again */
                else launchInstaller()
            }
        }

        private fun launchInstaller() {
            val f = java.io.File(java.io.File(cacheDir, "updates"), "update.apk")
            if (!f.exists()) return
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@MainActivity, "$packageName.fileprovider", f)
                startActivity(Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                /* the file is kept: if the installer is closed, the button
                   opens it again without downloading anything */
                updToPage("ready", 0, 0)
            } catch (e: Exception) { updToPage("fail", 0, 0) }
        }

        /**
         * Opens a stream the way the player would - every way it knows, the
         * redirects followed by hand - reads its first bytes and lets go at
         * once. The answer goes back as window.kiptvProbe(id, json). A way
         * that works is remembered, so the player starts with it later.
         */
        @android.webkit.JavascriptInterface
        fun probeStream(id: String, url: String, ua: String, ref: String) {
            Thread {
                val t0 = SystemClock.elapsedRealtime()
                val o = JSONObject()
                try {
                    val sr = net.search(url, ua.ifBlank { null }, ref.ifBlank { null }, isVod(url), 16)
                    val found = sr.found
                    val code = sr.code
                    o.put("trail", sr.trail)
                    o.put("ok", found != null)
                    o.put("ms", found?.ms ?: (SystemClock.elapsedRealtime() - t0))
                    o.put("kind", found?.kind ?: "")
                    if (code > 0) o.put("code", code)
                } catch (e: Exception) {
                    try { o.put("ok", false); o.put("err", e.javaClass.simpleName) } catch (e2: Exception) { }
                }
                val js = "window.kiptvProbe&&kiptvProbe(" + JSONObject.quote(id) + "," +
                    JSONObject.quote(o.toString()) + ")"
                runOnUiThread { try { webView.evaluateJavascript(js, null) } catch (e: Exception) { } }
            }.start()
        }

        /**
         * A request the page cannot make itself - a POST, or headers of its
         * own (OpenSubtitles, Claude for Kurdish subtitles). The answer goes
         * back as window.kiptvHttp(id, status, body).
         */
        @android.webkit.JavascriptInterface
        fun http(id: String, method: String, url: String, headers: String, body: String) {
            Thread {
                var code = 0
                var text = ""
                try {
                    if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) throw IllegalArgumentException("url")
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 20000
                    c.readTimeout = 180000
                    c.instanceFollowRedirects = true
                    c.requestMethod = if (method.equals("POST", true)) "POST" else "GET"
                    try {
                        val h = JSONObject(headers.ifBlank { "{}" })
                        for (k in h.keys()) c.setRequestProperty(k, h.optString(k))
                    } catch (e: Exception) { }
                    if (c.requestMethod == "POST") {
                        c.doOutput = true
                        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                    code = c.responseCode
                    val st = if (code >= 400) c.errorStream else c.inputStream
                    text = st?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
                    c.disconnect()
                } catch (e: Exception) { text = e.javaClass.simpleName + ": " + (e.message ?: "") }
                val js = "window.kiptvHttp&&kiptvHttp(" + JSONObject.quote(id) + "," + code + "," +
                    JSONObject.quote(text) + ")"
                runOnUiThread { try { webView.evaluateJavascript(js, null) } catch (e: Exception) { } }
            }.start()
        }

        /** keeps a finished subtitle file; answers the path the player reads it from */
        @android.webkit.JavascriptInterface
        fun subSave(name: String, text: String): String = try {
            val dir = File(filesDir, "subs").apply { mkdirs() }
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
            val f = File(dir, safe)
            f.writeText(text, Charsets.UTF_8)
            /* the oldest go once there are many */
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(400)?.forEach { it.delete() }
            f.absolutePath
        } catch (e: Exception) { "" }

        /** what this device decodes: {"hevc":..,"hevc4k":..,"av1":..,"ac3":..} */
        @android.webkit.JavascriptInterface
        fun codecs(): String {
            val o = JSONObject()
            try {
                var hevc = false; var hevc4k = false; var av1 = false; var ac3hw = false
                for (ci in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                    if (ci.isEncoder) continue
                    for (type in ci.supportedTypes) {
                        when (type.lowercase(Locale.ROOT)) {
                            "video/hevc" -> {
                                hevc = true
                                try {
                                    if (ci.getCapabilitiesForType(type).videoCapabilities
                                            ?.isSizeSupported(3840, 2160) == true) hevc4k = true
                                } catch (e: Exception) { }
                            }
                            "video/av01" -> av1 = true
                            "audio/ac3", "audio/eac3" -> ac3hw = true
                        }
                    }
                }
                o.put("hevc", hevc); o.put("hevc4k", hevc4k); o.put("av1", av1)
                o.put("ac3", true)          /* FFmpeg decodes it when the phone cannot */
                o.put("ac3hw", ac3hw)
            } catch (e: Exception) { }
            return o.toString()
        }

        /** the page's language, so the player's own menu speaks it too */
        @android.webkit.JavascriptInterface
        fun setLang(l: String) {
            runOnUiThread { uiLang = if (l == "ku" || l == "ar") l else "en" }
        }

        /** The whole live list, so the list inside the player can show every
         *  group - not only the one the page happens to be showing.
         *  json: [{"c":..,"n":..,"u":..,"l":..,"ua"?:..,"rf"?:..}, ...] */
        @android.webkit.JavascriptInterface
        fun setChannels(json: String) {
            try {
                val arr = JSONArray(json)
                val out = ArrayList<Chan>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val u = o.optString("u")
                    if (u.isBlank()) continue
                    out.add(Chan(
                        o.optString("c").ifBlank { "Other" },
                        o.optString("n"),
                        u,
                        o.optString("l"),
                        o.optString("ua").ifBlank { null },
                        o.optString("rf").ifBlank { null }
                    ))
                }
                runOnUiThread {
                    chanAll = out
                    chanCats = out.map { it.c }.distinct()
                    if (::btnChans.isInitialized && playerLayer.visibility == View.VISIBLE)
                        applyItemChrome(lastIndex)
                }
            } catch (e: Exception) { /* ignore malformed input */ }
        }

        /** json: [{"n":..,"u":..,"ua"?:..,"rf"?:..}, ...] - lets next / previous work */
        @android.webkit.JavascriptInterface
        fun playList(json: String, index: Int) { playList(json, index, 0) }

        @android.webkit.JavascriptInterface
        fun playList(json: String, index: Int, startMs: Int) {
            try {
                val arr = JSONArray(json)
                val u = ArrayList<String>(arr.length())
                val n = ArrayList<String>(arr.length())
                val a = ArrayList<String?>(arr.length())
                val r = ArrayList<String?>(arr.length())
                val v = HashSet<String>()
                val sb = HashMap<String, List<SubF>>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    u.add(o.optString("u"))
                    if (o.optInt("v", 0) == 1) v.add(o.optString("u"))
                    o.optJSONArray("subs")?.let { sa ->
                        val l = ArrayList<SubF>()
                        for (k in 0 until sa.length()) {
                            val so = sa.optJSONObject(k) ?: continue
                            val f = so.optString("f").removePrefix("file://")
                            if (f.isNotBlank() && File(f).isFile)
                                l.add(SubF(f, so.optString("lang"), so.optString("label"), so.optInt("def", 0) == 1))
                        }
                        if (l.isNotEmpty()) sb[o.optString("u")] = l
                    }
                    n.add(o.optString("n"))
                    a.add(o.optString("ua").ifBlank { null })
                    r.add(o.optString("rf").ifBlank { null })
                }
                if (u.isEmpty()) return
                val start = if (index in u.indices) index else 0
                runOnUiThread { vodSet = v; subsByUrl = sb; openQueue(u, n, a, r, start, startMs.toLong()) }
            } catch (e: Exception) { /* ignore malformed input */ }
        }
    }

    /** the queue's item [i] at [uri], with the subtitle files made ready for its stream */
    private fun mediaItem(i: Int, uri: String, mime: String?): MediaItem {
        val b = MediaItem.Builder().setUri(uri)
        mime?.let { b.setMimeType(it) }
        val subs = urls.getOrNull(i)?.let { subsByUrl[it] }
        if (!subs.isNullOrEmpty()) {
            b.setSubtitleConfigurations(subs.map { s ->
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(File(s.path)))
                    .setMimeType(if (s.path.endsWith(".vtt", true)) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage(s.lang)
                    .setLabel(s.label)
                    .setSelectionFlags(if (s.def) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            })
        }
        return b.build()
    }

    // ---------------- format plan ----------------

    /** What this address looks like. null means "let the extractors decide",
     *  which is what an ordinary .ts or .mkv wants. */
    private fun guessFor(url: String): String? {
        val full = url.lowercase()
        val path = full.substringBefore('?')
        return when {
            path.contains(".mpd") -> MimeTypes.APPLICATION_MPD
            path.contains(".m3u8") || path.endsWith(".m3u") -> MimeTypes.APPLICATION_M3U8
            /* .ts, .mkv, .mp4, .avi ... the extractors read these themselves */
            path.endsWith(".ts") || full.contains("extension=ts") -> null
            path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".avi") ||
            path.endsWith(".webm") || path.endsWith(".flv") || path.endsWith(".mov") -> null
            /* no extension: usually a short link that redirects to a playlist */
            path.matches(Regex(".*\\.[a-z0-9]{2,5}$")) -> null
            else -> MimeTypes.APPLICATION_M3U8
        }
    }

    /** Best guess first, then EVERY other container - so a stream that is not
     *  what its address claims still ends up playing. */
    private fun planFor(url: String): List<String?> {
        val best = guessFor(url)
        return listOf(best) + ORDER.filter { it != best }
    }

    // ---------------- player ----------------

    private fun buildPlayer(): ExoPlayer {
        /* A channel that brings its own agent uses it; everything else keeps
           the VLC agent exactly as before. The player is rebuilt whenever the
           agent or referrer changes, so nothing sits between the stream and
           the data source for an ordinary channel. */
        builtUa = curUa
        builtRef = curRef

        /* the data source reads net.way each time it opens a connection, so a
           different way of asking needs no new player - only a new prepare */
        net.referer = curRef
        val data = DefaultDataSource.Factory(this, net.dataSourceFactory())

        /* a live .ts often starts on a picture that is not a clean keyframe,
           and many encoders leave out the markers between frames */
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
            .setConstantBitrateSeekingEnabled(true)

        /* FFmpeg audio decoders (AC3, EAC3, DTS, TrueHD ...) as a fallback:
           the phone's own decoder is tried first, FFmpeg only takes over when
           the phone cannot play that audio format. */
        val renderers = NextRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(25000, 60000, 2500, 5000)
            .build()

        return ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(data, extractors))
            .setLoadControl(loadControl)
            .build()
    }

    /** Last resort: the address lied, or every way the player knows failed.
     *  Go and find where the stream really is, and what it really is, trying
     *  every way of asking - then play exactly that. */
    private fun resolveAndRetry(index: Int, fallbackCode: String, tries: Int = 10) {
        val link = urls.getOrNull(index)
        if (link == null) { showError(fallbackCode); return }
        val ua = curUa
        val ref = curRef
        loading.visibility = View.VISIBLE
        errorBox.visibility = View.GONE

        Thread {
            val found = try { net.find(link, ua, ref, isVod(link), tries + 4).first } catch (e: Exception) { null }
            runOnUiThread {
                if (dead || playerLayer.visibility != View.VISIBLE) return@runOnUiThread
                if (found == null) { showError(fallbackCode); return@runOnUiThread }

                /* the address found already carries the way's change of
                   address, so from here on the way only sets agent and DNS */
                val w = found.way.copy(swap = 0)
                ways = listOf(w) + ways.filter { it.key() != w.key() }
                wayIdx = 0
                net.way = w
                resolvedUrl = found.url
                step = 0
                reconnects = 0
                plan = listOf(found.mime) + ORDER.filter { it != found.mime }
                try {
                    val p = player ?: return@runOnUiThread
                    p.replaceMediaItem(index, mediaItem(index, found.url, found.mime))
                    p.prepare()
                    p.playWhenReady = true
                } catch (e: Exception) { showError(fallbackCode) }
            }
        }.start()
    }

    /** the ways to ask for this item, best first; the player starts on the first */
    private fun startWays(link: String) {
        itemStartAt = SystemClock.elapsedRealtime()
        waysTrail.clear()
        ways = net.waysFor(link, curUa, isVod(link))
        wayIdx = 0
        waysTried.clear()
        net.way = ways.firstOrNull() ?: Way(curUa ?: UA)
    }

    /** the next way that can help with this kind of failure, if one is left */
    private fun nextWay(why: Why): Boolean {
        ways.getOrNull(wayIdx)?.let { waysTried.add(it.key()) }
        val n = ways.indexOfFirst { it.key() !in waysTried && net.helps(it, why) }
        if (n < 0) return false
        wayIdx = n
        return true
    }

    /** the address the current way asks for */
    private fun linkNow(i: Int): String {
        val raw = resolvedUrl ?: urls.getOrNull(i) ?: ""
        return ways.getOrNull(wayIdx)?.let { net.linkFor(raw, it) } ?: raw
    }

    /** ask for the same item again, the new way */
    private fun switchWay(p: ExoPlayer, i: Int) {
        val w = ways.getOrNull(wayIdx) ?: return
        net.way = w
        val link = linkNow(i)
        if (w.swap == StreamNet.SWAP_EXT) { plan = planFor(link); step = 0 }
        val item = mediaItem(i, link, plan.getOrNull(step))
        loading.visibility = View.VISIBLE
        playerView.post {
            if (dead) return@post
            try {
                p.replaceMediaItem(i, item)
                p.prepare()
                p.playWhenReady = true
            } catch (e: Exception) { showError("RETRY_FAILED") }
        }
    }

    private fun isVod(url: String): Boolean {
        if (url in vodSet) return true
        val u = url.lowercase()
        return u.contains("/movie/") || u.contains("/series/")
    }

    /** Open a queue of streams; the next / previous buttons walk through it. */
    private fun openQueue(
        u: List<String>, n: List<String>,
        a: List<String?>, r: List<String?>,
        index: Int, startMs: Long
    ) {
        handler.removeCallbacksAndMessages(null)
        urls = u
        titles = n
        uas = a
        refs = r
        reconnects = 0
        startAtMs = startMs
        curUa = a.getOrNull(index)
        curRef = r.getOrNull(index)

        playerLayer.visibility = View.VISIBLE
        webView.visibility = View.GONE
        errorBox.visibility = View.GONE
        loading.visibility = View.VISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        setFullscreen(true)
        if (playerBrightness >= 0f) setWindowBrightness(playerBrightness)
        setPipAuto(!radioOn())        /* radio mode takes Home for the sound instead */

        buildAndStart(index)
        handler.postDelayed(posTicker, 5000)
    }

    private fun buildAndStart(index: Int) {
        releaseSession()                     /* always before the new one is made */
        player?.release()
        errorBox.visibility = View.GONE
        loading.visibility = View.VISIBLE

        step = 0
        hasPlayed = false
        probed = false
        resolvedUrl = null
        dead = false
        lastIndex = index
        currentUrl = urls.getOrNull(index)
        currentTitle = titles.getOrNull(index)
        curUa = uas.getOrNull(index)
        curRef = refs.getOrNull(index)
        startWays(currentUrl ?: "")
        plan = planFor(linkNow(index))
        applyItemChrome(index)

        val items = urls.mapIndexed { i, link ->
            if (i == index) mediaItem(i, linkNow(index), plan.getOrNull(0))
            else mediaItem(i, link, planFor(link).getOrNull(0))
        }

        player = buildPlayer().also { p ->
            playerView.player = p
            p.addListener(uiListener)
            applyTrackPrefs(p)

            p.addListener(object : Player.Listener {
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                    val i = p.currentMediaItemIndex
                    /* swapping the container of the item we are already on also
                       reports a transition - that must not restart the fallback */
                    if (i == lastIndex) return
                    lastIndex = i
                    probed = false
                    resolvedUrl = null
                    dead = false
                    reportPosition()
                    step = 0
                    hasPlayed = false
                    reconnects = 0
                    currentUrl = urls.getOrNull(i)
                    currentTitle = titles.getOrNull(i)
                    curUa = uas.getOrNull(i)
                    curRef = refs.getOrNull(i)
                    startWays(currentUrl ?: "")
                    plan = planFor(linkNow(i))
                    applyItemChrome(i)
                    applySpeed()        /* a film keeps the chosen speed, live is always 1× */
                    /* this channel wants a different agent - the data source
                       carries it, so start it over with one that matches */
                    if (curUa != builtUa || curRef != builtRef) {
                        playerView.post {
                            if (playerLayer.visibility == View.VISIBLE && urls.isNotEmpty())
                                buildAndStart(i)
                        }
                    } else if ((ways.firstOrNull()?.swap ?: 0) != 0) {
                        /* this server only works at its other address - ask there */
                        switchWay(p, i)
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_BUFFERING -> loading.visibility = View.VISIBLE
                        Player.STATE_READY -> {
                            loading.visibility = View.GONE
                            if (!hasPlayed) {
                                /* the way that worked is the first one tried on
                                   this server next time */
                                val w = ways.getOrNull(wayIdx)
                                val raw = urls.getOrNull(p.currentMediaItemIndex)
                                if (w != null && raw != null) net.remember(raw, w, w == Way(curUa ?: UA))
                            }
                            hasPlayed = true
                            reconnects = 0
                        }
                        Player.STATE_ENDED -> {
                            if (dead) return
                            val live = !isVod(currentUrl ?: "")
                            if (live && reconnects < MAX_RECONNECTS) reconnect()
                            else if (p.hasNextMediaItem()) p.seekToNextMediaItem()
                            else showError("ENDED")
                        }
                        else -> {}
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (dead) return
                    val i = p.currentMediaItemIndex
                    /* a live HLS stream the player fell behind: back to the live edge */
                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                        playerView.post {
                            if (dead) return@post
                            try {
                                p.seekToDefaultPosition(i)
                                p.prepare()
                                p.playWhenReady = true
                            } catch (e: Exception) { reconnect() }
                        }
                        return
                    }
                    /* 2xxx: the server would not hand the stream over. Another
                       container cannot fix that - another way of asking can. */
                    val netErr = error.errorCode in 2000..2999
                    val late = SystemClock.elapsedRealtime() - itemStartAt > WAYS_BUDGET_MS
                    if (!hasPlayed) ways.getOrNull(wayIdx)?.let { w ->
                        val what = when {
                            lastHttp > 0 -> lastHttp.toString()
                            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "conn"
                            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "timeout"
                            netErr -> "io"
                            else -> "format"
                        }
                        if (waysTrail.size < 24) waysTrail.add(w.label() + ":" + what)
                    }
                    when {
                        netErr && !hasPlayed && !late && nextWay(net.why(error)) -> switchWay(p, i)
                        // the container guess was wrong - try the next one for this item
                        !netErr && !hasPlayed && step < plan.size - 1 -> {
                            step++
                            val link = linkNow(i)
                            val item = mediaItem(i, link, plan.getOrNull(step))
                            playerView.post {
                                if (dead) return@post
                                try {
                                    p.replaceMediaItem(i, item)
                                    p.prepare()
                                    p.playWhenReady = true
                                } catch (e: Exception) { showError(error.errorCodeName) }
                            }
                        }
                        // every container failed: the address is not what it says
                        !hasPlayed && !probed && !late -> {
                            probed = true
                            /* after the ways ran out on a network failure, a short
                               look is enough; a stream that lied about what it
                               is gets the full search */
                            resolveAndRetry(i, error.errorCodeName, if (netErr) 3 else 10)
                        }
                        hasPlayed && reconnects < MAX_RECONNECTS -> reconnect()
                        !hasPlayed && !late && reconnects < 2 -> reconnect()
                        else -> showError(error.errorCodeName)
                    }
                }
            })

            p.setMediaItems(items, index, if (startAtMs > 0) startAtMs else 0L)
            startAtMs = 0L
            p.prepare()
            p.playWhenReady = true
            applyWake()
            applySpeed()
            openSession(p)
        }
        if (!locked) topBar.visibility = View.VISIBLE
        playerView.showController()          /* does nothing while locked */
    }

    /** Title and LIVE badge for whichever item is on screen. */
    private fun applyItemChrome(index: Int) {
        txtTitle.text = titles.getOrNull(index) ?: ""
        nowTitle = titles.getOrNull(index) ?: ""
        val live = !isVod(urls.getOrNull(index) ?: "")
        findViewById<TextView>(R.id.liveBadge).visibility =
            if (live) View.VISIBLE else View.GONE
        if (::btnChans.isInitialized)
            btnChans.visibility = if (live && chanAll.isNotEmpty()) View.VISIBLE else View.GONE
    }

    // ---------------- playing speed ----------------

    private fun speedPref(): Float = prefs().getFloat("speed", 1f)

    private fun setSpeed(s: Float) {
        prefs().edit().putFloat("speed", s).apply()
        applySpeed()
        fillPanel()
    }

    /** films and series follow the chosen speed; a live channel always runs at 1× */
    private fun applySpeed() {
        val p = player ?: return
        val wanted = if (isVod(currentUrl ?: "")) speedPref() else 1f
        try { if (p.playbackParameters.speed != wanted) p.setPlaybackSpeed(wanted) } catch (e: Exception) {}
    }

    // ---------------- radio mode: the sound stays on ----------------

    private fun radioOn(): Boolean = prefs().getBoolean("radio", false)

    private fun setRadio(on: Boolean) {
        prefs().edit().putBoolean("radio", on).apply()
        if (on) askNotify()
        applyWake()
        /* radio mode and the small floating window ask for the same gesture:
           with the sound carrying on, Home no longer opens the small window */
        if (playerLayer.visibility == View.VISIBLE) setPipAuto(!on)
        if (!on) stopRadio()
        fillPanel()
    }

    /** keeps the network awake while the screen sleeps; off again when radio mode is */
    private fun applyWake() {
        try { player?.setWakeMode(if (radioOn()) C.WAKE_MODE_NETWORK else C.WAKE_MODE_NONE) } catch (e: Exception) {}
    }

    /** the system's own play / pause, on the lock screen and on headsets */
    private fun openSession(p: ExoPlayer) {
        try {
            val s = MediaSession.Builder(this, p).build()
            session = s
            liveSession = s
        } catch (e: Exception) { session = null; liveSession = null }
    }

    private fun releaseSession() {
        try { session?.release() } catch (e: Exception) {}
        session = null
        liveSession = null
    }

    // ---------------- letting go of the line ----------------

    /**
     * A panel counts one connection for as long as the stream is held open, and
     * a paused picture still holds it. So an account that allows one or two at a
     * time stays locked on the other device long after this one was put down.
     *
     * Leaving the screen therefore closes the line and remembers where it was;
     * coming back opens it again at the same place. Radio mode and the small
     * floating window are the two cases that asked to go on, and are left alone.
     */
    private var sleptAt = -1L

    private fun sleepPlayer() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_IDLE) return
        sleptAt = if (isVod(currentUrl ?: "")) p.currentPosition else -1L
        try { p.stop() } catch (e: Exception) { }
    }

    private fun wakePlayer() {
        val p = player ?: return
        if (p.playbackState != Player.STATE_IDLE || dead) return
        try {
            if (sleptAt > 0) p.seekTo(sleptAt)
            sleptAt = -1L
            p.prepare()
            p.playWhenReady = true
        } catch (e: Exception) { }
    }

    /** true while the sound should carry on after the app leaves the screen */
    private fun keepPlaying(): Boolean =
        radioOn() && !dead && ::playerLayer.isInitialized &&
        playerLayer.visibility == View.VISIBLE && player?.playWhenReady == true

    private fun startRadio() {
        if (!keepPlaying()) return
        try {
            val i = Intent(this, RadioService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        } catch (e: Exception) { /* the system said no: the sound simply stops, as before */ }
    }

    private fun stopRadio() {
        try { stopService(Intent(this, RadioService::class.java)) } catch (e: Exception) {}
    }

    /** Android 13 and up hide the notification unless the user allows it; asked
     *  once, the first time radio mode is switched on, never at startup */
    private fun askNotify() {
        if (Build.VERSION.SDK_INT < 33) return                 /* TIRAMISU */
        try {
            val perm = "android.permission.POST_NOTIFICATIONS"
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(perm), 91)
        } catch (e: Exception) { /* no dialog: the sound still plays */ }
    }

    private fun reconnect() {
        if (dead || playerLayer.visibility != View.VISIBLE) return
        reconnects++
        loading.visibility = View.VISIBLE
        /* a film keeps its place across a hiccup; a live stream goes back to the edge */
        val i0 = try { player?.currentMediaItemIndex ?: 0 } catch (e: Exception) { 0 }
        val keep = try {
            if (isVod(urls.getOrNull(i0) ?: "")) (player?.currentPosition ?: 0L) else 0L
        } catch (e: Exception) { 0L }

        val wait = minOf(RECONNECT_DELAY_MS + 600L * (reconnects - 1), 6000L)

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (dead || playerLayer.visibility != View.VISIBLE) return@postDelayed
            try {
                val p = player
                if (p == null) {
                    startAtMs = keep
                    buildAndStart(i0)
                } else {
                    /* a film goes back to where it was; live goes to the live edge */
                    if (keep > 0) p.seekTo(p.currentMediaItemIndex, keep)
                    else p.seekToDefaultPosition(p.currentMediaItemIndex)
                    p.prepare()
                    p.playWhenReady = true
                }
            } catch (e: Exception) {
                try {
                    startAtMs = keep
                    buildAndStart(i0)
                } catch (e2: Exception) { showError("RETRY_FAILED") }
            }
            handler.removeCallbacks(posTicker)
            handler.postDelayed(posTicker, 5000)
        }, wait)
    }

    private fun showError(code: String) {
        if (locked) setLocked(false)      /* Retry and close must be reachable */
        setPipAuto(false)                 /* no small window for an error screen */
        dead = true                       /* no more retries until the user asks */
        handler.removeCallbacks(posTicker)
        try { player?.playWhenReady = false } catch (e: Exception) {}
        loading.visibility = View.GONE
        val why = when {
            /* a film's own server (not the panel) turning this phone away is
               most often its network: a VPN, or a country it does not serve */
            isVod(currentUrl ?: "") && lastHttp in listOf(400, 403, 451) -> tx("errVpn")
            lastHttp in listOf(401, 403, 407, 458, 509) || code.contains("NO_PERMISSION") -> tx("errRefused")
            lastHttp == 404 || code.contains("FILE_NOT_FOUND") -> tx("errGone")
            code.contains("TIMEOUT") -> tx("errSlow")
            code.contains("IO_") -> tx("errNet")
            code.contains("DECOD") || code.contains("AUDIO_TRACK") -> tx("errCodec")
            code.contains("PARSING") -> tx("errFormat")
            else -> ""
        }
        txtError.text = tx("errMain") + (if (why.isNotEmpty()) "\n$why" else "") + "\n\n" + code +
            (if (lastHttp > 0) "  (HTTP $lastHttp)" else "") +
            /* what each way got, so a screenshot says exactly what the server did */
            (if (waysTrail.isNotEmpty()) "\n\n" + waysTrail.joinToString("  ·  ") else "")
        findViewById<TextView>(R.id.btnRetry).text = tx("retry")
        errorBox.visibility = View.VISIBLE
    }

    private fun reportPosition() {
        if (playerLayer.visibility != View.VISIBLE) return
        val p = player ?: return
        val i = p.currentMediaItemIndex
        val link = urls.getOrNull(i) ?: return
        if (!isVod(link)) return
        val pos = p.currentPosition
        val dur = p.duration
        if (pos < 5000 || dur <= 0) return
        val js = "window.savePos&&savePos(" + JSONObject.quote(link) + "," + pos + "," + dur + ")"
        webView.evaluateJavascript(js, null)
    }

    private fun hideNativePlayer() {
        reportPosition()
        webView.evaluateJavascript("window.refreshHome&&refreshHome()", null)
        handler.removeCallbacksAndMessages(null)
        stopRadio()
        releaseSession()
        player?.release()
        player = null
        nowTitle = ""
        playerView.player = null

        playerLayer.visibility = View.GONE
        errorBox.visibility = View.GONE
        loading.visibility = View.GONE
        topBar.visibility = View.VISIBLE
        webView.visibility = View.VISIBLE
        webView.requestFocus()                /* the remote works in the page again */

        /* a phone goes back upright; a TV stays as it is (portrait would turn its picture) */
        requestedOrientation = if (isTv) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                               else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setFullscreen(false)
        resetLock()
    }

    private fun setFullscreen(on: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, !on)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---------------- picture shape + screen lock ----------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun chipBg(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(0xA6000000.toInt())
        setStroke(dp(1), 0x33FFFFFF)
    }

    private fun roundBg(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0xA6000000.toInt())
        setStroke(dp(1), 0x33FFFFFF)
    }

    /** A white ring that shows only where a TV remote's focus is.
     *  A finger never focuses these buttons, so a phone looks exactly as before. */
    private fun focusRing(v: View?, round: Boolean, radiusDp: Int = 12) {
        if (v == null) return
        val ring = GradientDrawable().apply {
            if (round) shape = GradientDrawable.OVAL else cornerRadius = dp(radiusDp).toFloat()
            setColor(0x33FFFFFF)
            setStroke(dp(2), Color.WHITE)
        }
        v.foreground = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), ring)
            addState(IntArray(0), ColorDrawable(Color.TRANSPARENT))
        }
        v.isFocusable = true
    }

    /** Fit / ⚙ / 🔒 sit in a row of our own at the bottom right, in the same
     *  line as the time, under the seek bar. It is not part of the player's own
     *  controls (those stay exactly as they were); it shows and hides with them,
     *  the same way the top bar does. */
    private fun buildAspectAndLockUi() {
        val layer = findViewById<FrameLayout>(R.id.playerLayer)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR      /* same order in every language */
        }
        bottomBar = bar
        /* 60dp = the height of the player's bottom line (the one with the time) */
        layer.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(60), Gravity.BOTTOM or Gravity.END).apply { marginEnd = dp(4) })

        /* the channel list. Only live television has one, so it comes and goes
           with whatever is playing - see applyItemChrome. */
        btnChans = TextView(this).apply {
            background = roundBg()
            text = "\u2630"
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            visibility = View.GONE
        }
        focusRing(btnChans, round = true)
        bar.addView(btnChans, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })
        btnChans.setOnClickListener { openChans() }

        btnAspect = TextView(this).apply {
            background = chipBg()
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            minWidth = dp(52)
            setPadding(dp(12), 0, dp(12), 0)
            isClickable = true
            isFocusable = true
        }
        focusRing(btnAspect, round = false)
        bar.addView(btnAspect, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginEnd = dp(10) })

        btnTracks = TextView(this).apply {
            background = roundBg()
            text = "\u2699\uFE0E"
            setTextColor(Color.WHITE)
            textSize = 19f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
        }
        focusRing(btnTracks, round = true)
        bar.addView(btnTracks, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })

        btnLock = TextView(this).apply {
            background = roundBg()
            text = "\uD83D\uDD12"
            textSize = 16f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            if (isTv) visibility = View.GONE       /* a TV has no touch screen to lock */
        }
        focusRing(btnLock, round = true)
        bar.addView(btnLock, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })

        /* the player's own gear (speed + audio) sat in this spot; our ⚙ has audio,
           subtitles and quality, so it is hidden - only its visibility changes */
        try {
            val id = resources.getIdentifier("exo_settings", "id", packageName)
            if (id != 0) playerView.findViewById<View>(id)?.visibility = View.GONE
        } catch (e: Exception) { /* keep it, then */ }

        /* takes every touch while locked, so nothing underneath reacts */
        lockOverlay = View(this).apply {
            isClickable = true
            isFocusable = false
            visibility = View.GONE
        }
        layer.addView(lockOverlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        btnUnlock = TextView(this).apply {
            background = roundBg()
            text = "\uD83D\uDD13"
            textSize = 22f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            visibility = View.GONE
        }
        focusRing(btnUnlock, round = true)
        layer.addView(btnUnlock, FrameLayout.LayoutParams(
            dp(56), dp(56), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(28) })

        txtHint = TextView(this).apply {
            background = chipBg()
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(10), dp(20), dp(10))
            visibility = View.GONE
        }
        layer.addView(txtHint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        panelScrim = View(this).apply {
            setBackgroundColor(0x66000000)
            isClickable = true
            isFocusable = false
            visibility = View.GONE
            setOnClickListener { closePanel() }
        }
        layer.addView(panelScrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        panelBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(28))
        }
        panel = ScrollView(this).apply {
            setBackgroundColor(0xF20B0F16.toInt())
            isClickable = true
            visibility = View.GONE
            addView(panelBox)
        }
        layer.addView(panel, FrameLayout.LayoutParams(
            dp(340), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))

        btnTracks.setOnClickListener { openPanel() }
        applySubScale()

        swipe = SwipeGesture(ViewConfiguration.get(this).scaledTouchSlop.toFloat(), dp(48).toFloat())

        aspectIdx = prefs().getInt("aspect", 0).coerceIn(0, ASPECT_NAMES.size - 1)
        applyAspect(false)

        btnAspect.setOnClickListener {
            aspectIdx = (aspectIdx + 1) % ASPECT_NAMES.size
            prefs().edit().putInt("aspect", aspectIdx).apply()
            applyAspect(true)
            playerView.showController()     /* stays up while you press through the shapes */
        }
        btnLock.setOnClickListener { setLocked(true) }
        btnUnlock.setOnClickListener { setLocked(false) }
        lockOverlay.setOnClickListener { flashUnlock() }
    }

    private fun prefs() = getSharedPreferences("kiptv_player", Context.MODE_PRIVATE)

    /** 16:9 and 4:3 force that shape (stretching the picture) - for channels
     *  with black bars. Fit / Fill / Zoom are the player's own modes. */
    private fun forcedRatio(): Float = when (aspectIdx) {
        3 -> 16f / 9f
        4 -> 4f / 3f
        else -> 0f
    }

    private fun applyAspect(announce: Boolean) {
        playerView.resizeMode = when (aspectIdx) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        applyRatio()
        btnAspect.text = ASPECT_NAMES[aspectIdx]
        if (announce) showHint(ASPECT_NAMES[aspectIdx])
    }

    /** the frame the picture sits in (a direct child of PlayerView) */
    private fun contentFrame(): AspectRatioFrameLayout? {
        for (i in 0 until playerView.childCount) {
            val v = playerView.getChildAt(i)
            if (v is AspectRatioFrameLayout) return v
        }
        return null
    }

    private fun applyRatio() {
        val frame = contentFrame() ?: return
        val forced = forcedRatio()
        if (forced > 0f) {
            frame.setAspectRatio(forced)
            return
        }
        /* back to the picture's own shape */
        val vs = try { player?.videoSize } catch (e: Exception) { null }
        if (vs != null && vs.width > 0 && vs.height > 0)
            frame.setAspectRatio(vs.width * vs.pixelWidthHeightRatio / vs.height)
    }

    /** Only looks, never steers playback:
     *  - PlayerView sets the frame to the video's own shape on every new video
     *    size; a forced 16:9 / 4:3 is put back right after it
     *  - an open ⚙ panel is redrawn when the stream's tracks change
     *  - a quality picked for one stream is dropped when the next one starts */
    private val uiListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (forcedRatio() > 0f) playerView.post { applyRatio() }
            playerView.post { if (playerLayer.visibility == View.VISIBLE && !dead) setPipAuto(true) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                lastHttp = 0
                playerView.post { if (playerLayer.visibility == View.VISIBLE && !dead) setPipAuto(true) }
            }
        }

        /* only notes the server's answer for the error text - the fallback
           steps that follow are exactly as before */
        override fun onPlayerError(error: PlaybackException) {
            lastHttp = try {
                var c: Throwable? = error.cause
                var code = 0
                var n = 0
                while (c != null && n < 6) {
                    if (c is HttpDataSource.InvalidResponseCodeException) { code = c.responseCode; break }
                    c = c.cause
                    n++
                }
                code
            } catch (e: Exception) { 0 }
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (panelOn()) playerView.post { if (panelOn()) fillPanel() }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!qualityPinned) return
            qualityPinned = false
            val p = player ?: return
            try {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
            } catch (e: Exception) { /* never let a menu choice stop playback */ }
        }
    }

    private fun setLocked(on: Boolean) {
        locked = on
        lockOverlay.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            closePanel()
            closeChans()
            playerView.hideController()
            playerView.useController = false
            topBar.visibility = View.GONE
            bottomBar.visibility = View.GONE
            showHint("\uD83D\uDD12")
            flashUnlock()
        } else {
            btnUnlock.removeCallbacks(hideUnlock)
            btnUnlock.visibility = View.GONE
            playerView.useController = true
            topBar.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE
            playerView.showController()
        }
    }

    /** a touch (or Back) while locked shows the unlock button for a moment */
    private fun flashUnlock() {
        btnUnlock.visibility = View.VISIBLE
        btnUnlock.removeCallbacks(hideUnlock)
        btnUnlock.postDelayed(hideUnlock, 2500)
    }

    private fun showHint(text: String) {
        txtHint.text = text
        txtHint.visibility = View.VISIBLE
        txtHint.removeCallbacks(hideHint)
        txtHint.postDelayed(hideHint, 1200)
    }

    /** the player closed: leave everything unlocked for next time */
    private fun resetLock() {
        setPipAuto(false)
        setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        closePanel()
        closeChans()
        locked = false
        lockOverlay.visibility = View.GONE
        btnUnlock.removeCallbacks(hideUnlock)
        btnUnlock.visibility = View.GONE
        txtHint.removeCallbacks(hideHint)
        txtHint.visibility = View.GONE
        playerView.useController = true
    }

    // ---------------- audio / subtitles / quality (⚙) ----------------

    private val TX_EN = mapOf(
        "allCats" to "All",
        "errMain" to "This channel could not be opened.",
        "errNet" to "The server did not answer, even after trying other ways to connect. It may be down right now.",
        "errSlow" to "The server is too slow to answer right now.",
        "errRefused" to "The server refused the stream. The account may be in use on another device, or it has ended.",
        "errGone" to "This stream is no longer on the server.",
        "errCodec" to "This device cannot decode the picture or sound of this stream.",
        "errFormat" to "What the server sent is not a video.",
        "retry" to "Try again",
        "errVpn" to "The film's server refused this connection. If a VPN is on, turn it off and try again.",
        "audio" to "Audio", "subs" to "Subtitles", "quality" to "Quality",
        "off" to "Off", "auto" to "Auto", "size" to "Text size",
        "s1" to "Small", "s2" to "Normal", "s3" to "Large", "s4" to "Extra large",
        "noSubs" to "No subtitles in this video", "oneAudio" to "Only one audio track",
        "oneVideo" to "Only one quality", "track" to "Track",
        "speed" to "Speed", "normalSpeed" to "Normal",
        "speedLive" to "Speed is for films and series",
        "radio" to "Background", "radioOn" to "Keep the sound playing",
        "radioNote" to "Leave the app and the sound goes on, like a radio. No small window then.")
    private val TX_KU = mapOf(
        "allCats" to "هەموو",
        "errMain" to "نەتوانرا ئەم کەناڵە بکرێتەوە.",
        "errNet" to "سێرڤەرەکە وەڵامی نەدایەوە، تەنانەت دوای تاقیکردنەوەی چەند ڕێگایەکی تری پەیوەندی. لەوانەیە ئێستا لەکارکەوتبێت.",
        "errSlow" to "سێرڤەرەکە ئێستا زۆر خاوە لە وەڵامدانەوە.",
        "errRefused" to "سێرڤەرەکە پەخشەکەی ڕەتکردەوە. لەوانەیە ئەکاونتەکە لە ئامێرێکی تر بەکاربێت یان کۆتایی هاتبێت.",
        "errGone" to "ئەم پەخشە چیتر لەسەر سێرڤەرەکە نییە.",
        "errCodec" to "ئەم ئامێرە ناتوانێت وێنە یان دەنگی ئەم پەخشە بخوێنێتەوە.",
        "errFormat" to "ئەوەی سێرڤەرەکە ناردی ڤیدیۆ نییە.",
        "retry" to "هەوڵدانەوە",
        "errVpn" to "سێرڤەری فیلمەکە ئەم پەیوەندییەی ڕەتکردەوە. ئەگەر VPN هەڵکراوە، بیکوژێنەوە و دووبارە هەوڵ بدەرەوە.",
        "audio" to "دەنگ", "subs" to "ژێرنووس", "quality" to "کوالیتی",
        "off" to "بێ ژێرنووس", "auto" to "خۆکار", "size" to "قەبارەی نووسین",
        "s1" to "بچووک", "s2" to "ئاسایی", "s3" to "گەورە", "s4" to "زۆر گەورە",
        "noSubs" to "ئەم ڤیدیۆیە ژێرنووسی نییە", "oneAudio" to "تەنها یەک دەنگ هەیە",
        "oneVideo" to "تەنها یەک کوالیتی هەیە", "track" to "تراک",
        "speed" to "خێرایی پەخش", "normalSpeed" to "ئاسایی",
        "speedLive" to "خێرایی تەنها بۆ فیلم و زنجیرەیە",
        "radio" to "لە پشتەوە", "radioOn" to "دەنگ بەردەوام بێت",
        "radioNote" to "لە ئەپەکە دەردەچیت و دەنگەکە بەردەوام دەبێت، وەک ڕادیۆ. ئەوکات پەنجەرە بچووکەکە ناکرێتەوە.")
    private val TX_AR = mapOf(
        "allCats" to "الكل",
        "errMain" to "تعذّر فتح هذه القناة.",
        "errNet" to "لم يستجب الخادم حتى بعد تجربة طرق اتصال أخرى. قد يكون متوقفاً الآن.",
        "errSlow" to "الخادم بطيء جداً في الرد الآن.",
        "errRefused" to "رفض الخادم البث. قد يكون الحساب مستخدماً على جهاز آخر أو منتهياً.",
        "errGone" to "هذا البث لم يعد موجوداً على الخادم.",
        "errCodec" to "هذا الجهاز لا يستطيع فك ترميز صورة أو صوت هذا البث.",
        "errFormat" to "ما أرسله الخادم ليس فيديو.",
        "retry" to "إعادة المحاولة",
        "errVpn" to "رفض خادم الفيلم هذا الاتصال. إذا كان VPN مفعّلاً فأوقفه وحاول مجدداً.",
        "audio" to "الصوت", "subs" to "الترجمة", "quality" to "الجودة",
        "off" to "بدون ترجمة", "auto" to "تلقائي", "size" to "حجم الخط",
        "s1" to "صغير", "s2" to "عادي", "s3" to "كبير", "s4" to "كبير جداً",
        "noSubs" to "لا توجد ترجمة في هذا الفيديو", "oneAudio" to "يوجد صوت واحد فقط",
        "oneVideo" to "توجد جودة واحدة فقط", "track" to "مسار",
        "speed" to "سرعة العرض", "normalSpeed" to "عادية",
        "speedLive" to "السرعة للأفلام والمسلسلات فقط",
        "radio" to "في الخلفية", "radioOn" to "استمرار الصوت",
        "radioNote" to "تخرج من التطبيق ويستمر الصوت كالراديو، ولا تُفتح النافذة الصغيرة حينها.")

    private fun tx(key: String): String =
        (when (uiLang) { "ku" -> TX_KU; "ar" -> TX_AR; else -> TX_EN })[key] ?: TX_EN[key] ?: key

    private val LANG_EN = mapOf("ku" to "Kurdish", "ckb" to "Kurdish (Sorani)", "kmr" to "Kurdish (Kurmanji)",
        "sdh" to "Kurdish (Southern)", "ar" to "Arabic", "en" to "English", "tr" to "Turkish", "fa" to "Persian",
        "fr" to "French", "de" to "German", "es" to "Spanish", "ru" to "Russian", "it" to "Italian",
        "hi" to "Hindi", "ur" to "Urdu")
    private val LANG_KU = mapOf("ku" to "کوردی", "ckb" to "کوردی (سۆرانی)", "kmr" to "کوردی (کرمانجی)",
        "sdh" to "کوردی (باشووری)", "ar" to "عەرەبی", "en" to "ئینگلیزی", "tr" to "تورکی", "fa" to "فارسی",
        "fr" to "فەرەنسی", "de" to "ئەڵمانی", "es" to "ئیسپانی", "ru" to "ڕووسی", "it" to "ئیتاڵی",
        "hi" to "هیندی", "ur" to "ئوردو")
    private val LANG_AR = mapOf("ku" to "الكردية", "ckb" to "الكردية (السورانية)", "kmr" to "الكردية (الكرمانجية)",
        "sdh" to "الكردية (الجنوبية)", "ar" to "العربية", "en" to "الإنجليزية", "tr" to "التركية", "fa" to "الفارسية",
        "fr" to "الفرنسية", "de" to "الألمانية", "es" to "الإسبانية", "ru" to "الروسية", "it" to "الإيطالية",
        "hi" to "الهندية", "ur" to "الأردية")

    /** a track's language in the app's language, or null when it has none */
    private fun langName(code: String?): String? {
        if (code.isNullOrBlank() || code == C.LANGUAGE_UNDETERMINED) return null
        val c = code.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
        val names = when (uiLang) { "ku" -> LANG_KU; "ar" -> LANG_AR; else -> LANG_EN }
        names[c]?.let { return it }
        val d = try { Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH) } catch (e: Exception) { "" }
        return if (d.isNotBlank() && d.lowercase(Locale.ROOT) != c) d else code
    }

    private fun codecName(mime: String?): String? = when (mime) {
        MimeTypes.AUDIO_AC3 -> "AC3"
        MimeTypes.AUDIO_E_AC3 -> "E-AC3"
        MimeTypes.AUDIO_AC4 -> "AC4"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_MPEG_L2 -> "MP2"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_OPUS -> "Opus"
        else -> null
    }

    private fun audioLabel(f: Format, n: Int): String {
        val parts = ArrayList<String>()
        val lang = langName(f.language)
        val label = f.label
        parts.add(label ?: lang ?: (tx("track") + " " + n))
        if (label != null && lang != null && lang != label) parts.add(lang)
        when (f.channelCount) {
            1 -> parts.add("Mono")
            2 -> parts.add("Stereo")
            6 -> parts.add("5.1")
            8 -> parts.add("7.1")
        }
        codecName(f.sampleMimeType)?.let { parts.add(it) }
        return parts.joinToString("  ·  ")
    }

    private fun textLabel(f: Format, n: Int): String {
        val lang = langName(f.language)
        val label = f.label
        val main = label ?: lang ?: (tx("track") + " " + n)
        return if (label != null && lang != null && lang != label) "$main  ·  $lang" else main
    }

    private fun qualityLabel(f: Format): String {
        val mbps = if (f.bitrate > 0) String.format(Locale.US, "  ·  %.1f Mbps", f.bitrate / 1_000_000f) else ""
        return "${f.height}p$mbps"
    }

    private fun panelBg(on: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(if (on) 0x33E8B33C else 0x14FFFFFF)
        setStroke(dp(1), if (on) 0xFFE8B33C.toInt() else 0x1FFFFFFF)
    }

    private fun sectionTitle(t: String): TextView = TextView(this).apply {
        text = t
        setTextColor(0xFF8B97A6.toInt())
        textSize = 12.5f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), dp(18), dp(4), dp(8))
    }

    private fun noteRow(t: String): TextView = TextView(this).apply {
        text = t
        setTextColor(0xFF5E6977.toInt())
        textSize = 12f
        setPadding(dp(6), dp(2), dp(6), dp(8))
    }

    private fun optionRow(label: String, on: Boolean, action: () -> Unit): TextView = TextView(this).apply {
        text = (if (on) "✓   " else "") + label
        setTextColor(if (on) 0xFFFFD277.toInt() else Color.WHITE)
        textSize = 14f
        if (on) setTypeface(typeface, Typeface.BOLD)
        background = panelBg(on)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        isClickable = true
        focusRing(this, round = false)
        tag = label                      /* lets the remote's focus come back to it */
        isSelected = on
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
    }

    private fun sizeRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val cur = prefs().getFloat("subScale", 1f)
        for ((k, s) in SUB_SCALES.withIndex()) {
            val on = abs(cur - s) < 0.01f
            val b = TextView(this).apply {
                text = tx("s" + (k + 1))
                gravity = Gravity.CENTER
                setTextColor(if (on) 0xFFFFD277.toInt() else Color.WHITE)
                textSize = 12f
                if (on) setTypeface(typeface, Typeface.BOLD)
                background = panelBg(on)
                setPadding(dp(4), dp(10), dp(4), dp(10))
                isClickable = true
                focusRing(this, round = false)
                tag = "size$k"
                isSelected = on
                setOnClickListener {
                    prefs().edit().putFloat("subScale", s).apply()
                    applySubScale()
                    fillPanel()
                }
            }
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (k > 0) marginStart = dp(6)
            })
        }
        return row
    }

    /** 0.5× … 2×, in two rows so every one of them stays readable */
    private fun speedRows(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val cur = speedPref()
        var row: LinearLayout? = null
        for ((k, s) in SPEEDS.withIndex()) {
            if (k % 3 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                box.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        if (k > 0) topMargin = dp(6)
                    })
            }
            val on = abs(cur - s) < 0.01f
            val b = TextView(this).apply {
                text = (if (abs(s - 1f) < 0.01f) tx("normalSpeed") else speedLabel(s))
                gravity = Gravity.CENTER
                setTextColor(if (on) 0xFFFFD277.toInt() else Color.WHITE)
                textSize = 12.5f
                if (on) setTypeface(typeface, Typeface.BOLD)
                background = panelBg(on)
                setPadding(dp(4), dp(11), dp(4), dp(11))
                isClickable = true
                focusRing(this, round = false)
                tag = "spd$k"
                isSelected = on
                setOnClickListener { setSpeed(s) }
            }
            row?.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (k % 3 > 0) marginStart = dp(6)
            })
        }
        return box
    }

    private fun speedLabel(s: Float): String =
        (if (s == s.toInt().toFloat()) s.toInt().toString()
         else String.format(Locale.US, "%.2f", s).trimEnd('0').trimEnd('.')) + "×"

    private fun applySubScale() {
        val s = prefs().getFloat("subScale", 0f)
        if (s > 0f) playerView.subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * s)
    }


    // ---------------- the channel list, over the picture ----------------

    /*
     * Two columns of names laid straight over the channel that is playing:
     * the groups on the left, the channels beside them. No box, no border,
     * no line - only the picture dimmed enough to read them by. The channel
     * never stops while the list is up, and picking a name switches at once.
     *
     * The page hands over the whole live list (setChannels); the player's
     * own next / previous buttons keep walking the group you chose from.
     */

    private class Chan(
        val c: String, val n: String, val u: String,
        val l: String, val ua: String?, val rf: String?
    )

    /** the group chip that means "no group at all" */
    private val ALL_CATS = "\u0000all"

    private var chanAll: List<Chan> = emptyList()
    private var chanCats: List<String> = emptyList()
    private var chanCat: String = ALL_CATS
    /** what the right column is showing: positions inside chanAll */
    private var chanShown: List<Int> = emptyList()
    private var chanDrawn = 0

    private lateinit var btnChans: TextView
    private lateinit var chanLayer: FrameLayout
    private lateinit var catCol: LinearLayout
    private lateinit var catBox: LinearLayout
    private lateinit var chanCol: LinearLayout
    private lateinit var chanBox: LinearLayout
    private lateinit var chanScroll: ScrollView
    private lateinit var btnCols: TextView
    private var colsOpen = true

    private val hideChans = Runnable { if (chansOn()) closeChans() }

    /** every touch puts the eight seconds back */
    private fun armChanHide() {
        handler.removeCallbacks(hideChans)
        handler.postDelayed(hideChans, 8000)
    }

    private fun chansOn(): Boolean =
        ::chanLayer.isInitialized && chanLayer.visibility == View.VISIBLE

    private fun buildChansUi() {
        val layer = findViewById<FrameLayout>(R.id.playerLayer)

        chanLayer = FrameLayout(this).apply {
            visibility = View.GONE
            /* a name, its logo and its number read the same way in every
               language, so this one layer stays left to right */
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        layer.addView(chanLayer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        /* the picture keeps playing underneath; it only dims */
        val scrim = View(this).apply {
            setBackgroundColor(0x85000000.toInt())
            isClickable = true
            setOnClickListener { closeChans() }
        }
        chanLayer.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        catCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        catCol.addView(colHeader("CATEGORIES"))
        catBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val catScroll = ScrollView(this).apply {
            isFillViewport = false
            addView(catBox)
        }
        /* scrolling the groups counts as being there, the same as the channels do */
        catScroll.setOnScrollChangeListener { _, _, _, _, _ -> armChanHide() }
        catCol.addView(catScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        chanLayer.addView(catCol, FrameLayout.LayoutParams(
            dp(198), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START or Gravity.TOP))

        btnCols = TextView(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x73909090)
            }
            text = "‹"
            setTextColor(Color.WHITE)
            textSize = 21f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = "categories"
        }
        focusRing(btnCols, round = true)
        chanLayer.addView(btnCols, FrameLayout.LayoutParams(
            dp(42), dp(42), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(204) })
        btnCols.setOnClickListener { toggleCols() }

        chanCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chanCol.addView(colHeader("TV CHANNELS"))
        chanBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chanScroll = ScrollView(this).apply {
            isFillViewport = false
            addView(chanBox)
        }
        chanCol.addView(chanScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        chanLayer.addView(chanCol, FrameLayout.LayoutParams(
            dp(272), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START or Gravity.TOP).apply {
            marginStart = dp(254)
        })

        /* five hundred names are not built at once: the next forty arrive as
           the list is scrolled, so opening it is instant on a cheap box */
        chanScroll.setOnScrollChangeListener { _, _, y, _, _ ->
            armChanHide()
            val bottom = chanBox.height - chanScroll.height
            if (bottom - y < dp(400)) drawMoreChans()
        }
    }

    private fun colHeader(t: String): TextView = TextView(this).apply {
        text = t
        setTextColor(0xEBFFFFFF.toInt())
        textSize = 12f
        letterSpacing = 0.15f
        gravity = Gravity.CENTER
        setPadding(0, dp(16), 0, dp(8))
        setShadowLayer(dp(5).toFloat(), 0f, dp(1).toFloat(), 0xE6000000.toInt())
    }

    /** the soft grey slab under whichever name is chosen - no border, no line */
    private fun pickedBg(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(13).toFloat()
        setColor(0x6BA8A8A8)
    }

    private fun toggleCols() {
        colsOpen = !colsOpen
        catCol.visibility = if (colsOpen) View.VISIBLE else View.GONE
        btnCols.text = if (colsOpen) "‹" else "›"
        (btnCols.layoutParams as FrameLayout.LayoutParams).marginStart =
            if (colsOpen) dp(204) else dp(8)
        (chanCol.layoutParams as FrameLayout.LayoutParams).marginStart =
            if (colsOpen) dp(254) else dp(58)
        btnCols.requestLayout()
        chanCol.requestLayout()
        armChanHide()
    }

    private fun openChans() {
        if (locked || chanAll.isEmpty()) return
        closePanel()
        /* open on the group the channel now playing belongs to */
        val here = chanAll.indexOfFirst { it.u == currentUrl }
        chanCat = if (here >= 0) chanAll[here].c else ALL_CATS
        colsOpen = true
        catCol.visibility = View.VISIBLE
        btnCols.text = "‹"
        (btnCols.layoutParams as FrameLayout.LayoutParams).marginStart = dp(204)
        (chanCol.layoutParams as FrameLayout.LayoutParams).marginStart = dp(254)
        fillCats()
        fillChans()
        chanLayer.visibility = View.VISIBLE
        playerView.hideController()
        armChanHide()
        if (!chanLayer.isInTouchMode) chanBox.postDelayed({ focusPlaying() }, 60)
    }

    private fun closeChans() {
        if (!::chanLayer.isInitialized) return
        val was = chansOn()
        chanLayer.visibility = View.GONE
        handler.removeCallbacks(hideChans)
        /* TV remote only: back to the ☰, so the next press has somewhere to go */
        if (was && !chanLayer.isInTouchMode && !locked && !inPip &&
            playerLayer.visibility == View.VISIBLE) {
            playerView.showController()
            btnChans.requestFocus()
        }
    }

    private fun fillCats() {
        catBox.removeAllViews()
        val all = ArrayList<Pair<String, String>>()
        all.add(Pair(ALL_CATS, tx("allCats")))
        for (c in chanCats) all.add(Pair(c, c))
        for ((key, label) in all) {
            val row = TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), 0, dp(13), 0)
                setShadowLayer(dp(5).toFloat(), 0f, dp(1).toFloat(), 0xE6000000.toInt())
                isClickable = true
                isFocusable = true
                background = if (key == chanCat) pickedBg() else null
                tag = key
            }
            focusRing(row, round = false, radiusDp = 13)
            row.setOnClickListener {
                chanCat = key
                armChanHide()
                fillCats()
                fillChans()
            }
            catBox.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
                topMargin = dp(2); leftMargin = dp(8); rightMargin = dp(8)
            })
        }
    }

    private fun fillChans() {
        chanBox.removeAllViews()
        chanDrawn = 0
        chanShown = chanAll.indices.filter { chanCat == ALL_CATS || chanAll[it].c == chanCat }
        drawMoreChans()
        chanScroll.scrollTo(0, 0)
    }

    private fun drawMoreChans() {
        var added = 0
        while (chanDrawn < chanShown.size && added < 40) {
            chanBox.addView(chanRow(chanDrawn), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
                topMargin = dp(2); leftMargin = dp(6); rightMargin = dp(6)
            })
            chanDrawn++
            added++
        }
    }

    /** one line: the channel's own logo, its name, its number */
    private fun chanRow(pos: Int): View {
        val ch = chanAll[chanShown[pos]]
        val playing = ch.u == currentUrl

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), 0, dp(9), 0)
            isClickable = true
            isFocusable = true
            background = if (playing) pickedBg() else null
            tag = ch.u
        }
        focusRing(row, round = false, radiusDp = 13)

        val badge = TextView(this).apply {
            text = initials(ch.n)
            setTextColor(Color.WHITE)
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(7).toFloat()
                setColor(0x29FFFFFF)
            }
        }
        val logo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        val stack = FrameLayout(this)
        stack.addView(badge, FrameLayout.LayoutParams(dp(38), dp(38)))
        stack.addView(logo, FrameLayout.LayoutParams(dp(38), dp(38)))
        row.addView(stack, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) })

        val name = TextView(this).apply {
            text = ch.n
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(dp(5).toFloat(), 0f, dp(1).toFloat(), 0xE6000000.toInt())
        }
        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val no = TextView(this).apply {
            text = (chanShown[pos] + 1).toString()
            setTextColor(0xE0FFFFFF.toInt())
            textSize = 14f
            setShadowLayer(dp(5).toFloat(), 0f, dp(1).toFloat(), 0xE6000000.toInt())
        }
        row.addView(no, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(10)
        })

        row.setOnClickListener { pickChan(pos) }
        loadLogo(logo, badge, ch.l)
        return row
    }

    /** what stands in for a logo until the real one arrives, or if none does */
    private fun initials(n: String): String {
        val w = n.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (w.isEmpty()) return "?"
        val first = w[0]
        return if (first.length >= 3) first.take(3).uppercase()
               else w.take(3).joinToString("") { it.take(1) }.uppercase()
    }

    private fun pickChan(pos: Int) {
        if (pos !in chanShown.indices) return
        closeChans()
        vodSet = emptySet()                  /* the list over the picture holds channels only */
        /* the next / previous buttons walk the group this came from */
        val from = maxOf(0, pos - 150)
        val to = minOf(chanShown.size, pos + 150)
        val win = chanShown.subList(from, to).map { chanAll[it] }
        openQueue(win.map { it.u }, win.map { it.n },
                  win.map { it.ua }, win.map { it.rf }, pos - from, 0L)
    }

    /** the remote opens on the channel that is playing */
    private fun focusPlaying() {
        var target: View? = null
        for (i in 0 until chanBox.childCount) {
            val v = chanBox.getChildAt(i)
            if (v.tag == currentUrl) { target = v; break }
        }
        (target ?: chanBox.getChildAt(0))?.requestFocus()
    }

    // ---------------- logos ----------------

    /* Kept in memory for as long as the app is up: a panel's logos are small,
       and asking for the same one twice while scrolling is what makes a list
       feel slow. A logo that will not come is remembered too, so it is not
       asked for again and again. */
    private val logoCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val logoBad = java.util.Collections.synchronizedSet(HashSet<String>())
    private val logoPool = java.util.concurrent.Executors.newFixedThreadPool(3)

    private fun loadLogo(img: ImageView, badge: TextView, url: String) {
        img.tag = url
        if (url.isBlank() || !url.startsWith("http", true) || logoBad.contains(url)) return
        val hit = logoCache.get(url)
        if (hit != null) {
            img.setImageBitmap(hit)
            img.visibility = View.VISIBLE
            badge.visibility = View.GONE
            return
        }
        logoPool.execute {
            var bm: Bitmap? = null
            try {
                val c = fetchAllowingCloudflare(url)
                val bytes = c.inputStream.use { it.readBytes() }
                try { c.disconnect() } catch (e: Exception) { }
                if (bytes.size in 1..(2 * 1024 * 1024)) {
                    val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
                    var step = 1
                    while (probe.outWidth / step > 160 || probe.outHeight / step > 160) step *= 2
                    bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = step })
                }
            } catch (e: Exception) { /* a logo is never worth a crash */ }
            if (bm == null) { logoBad.add(url); return@execute }
            logoCache.put(url, bm)
            val ready = bm
            runOnUiThread {
                if (img.tag == url) {
                    img.setImageBitmap(ready)
                    img.visibility = View.VISIBLE
                    badge.visibility = View.GONE
                }
            }
        }
    }

    private fun panelOn(): Boolean = panel.visibility == View.VISIBLE

    private fun openPanel() {
        if (locked) return
        closeChans()
        val w = resources.displayMetrics.widthPixels
        panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply {
            width = minOf(dp(360), (w * 0.62f).toInt())
        }
        fillPanel()
        panel.scrollTo(0, 0)
        panelScrim.visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
        playerView.hideController()
        if (!panel.isInTouchMode) focusPanel(null)      /* TV remote: start on the chosen row */
    }

    private fun closePanel() {
        val wasOpen = panelOn()
        panel.visibility = View.GONE
        panelScrim.visibility = View.GONE
        /* TV remote only: back to the ⚙ button, so the next press has somewhere to go */
        if (wasOpen && !panel.isInTouchMode && !locked && !inPip &&
            playerLayer.visibility == View.VISIBLE) {
            playerView.showController()
            btnTracks.requestFocus()
        }
    }

    /** the remote's focus goes to the row it was on, else the chosen one, else the first */
    private fun focusPanel(key: Any?) {
        val rows = ArrayList<View>()
        collectRows(panelBox, rows)
        val target = rows.firstOrNull { key != null && it.tag == key }
            ?: rows.firstOrNull { it.isSelected }
            ?: rows.firstOrNull()
        target?.requestFocus()
    }

    private fun collectRows(g: ViewGroup, out: MutableList<View>) {
        for (i in 0 until g.childCount) {
            val v = g.getChildAt(i)
            if (v is ViewGroup) collectRows(v, out) else if (v.isFocusable) out.add(v)
        }
    }

    /** what the stream offers right now, in three short lists */
    private fun fillPanel() {
        val box = panelBox
        val focusKey = panelBox.findFocus()?.tag
        box.removeAllViews()
        box.layoutDirection = if (uiLang == "en") View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
        val p = player
        val groups: List<Tracks.Group> = try { p?.currentTracks?.groups } catch (e: Exception) { null } ?: emptyList()

        /* audio */
        box.addView(sectionTitle("🔊  " + tx("audio")))
        var n = 0
        for (g in groups) {
            if (g.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                n++
                val f = g.getTrackFormat(i)
                box.addView(optionRow(audioLabel(f, n), g.isTrackSelected(i)) { chooseAudio(g, i) })
            }
        }
        if (n == 0) box.addView(noteRow("–"))
        else if (n == 1) box.addView(noteRow(tx("oneAudio")))

        /* subtitles */
        box.addView(sectionTitle("💬  " + tx("subs")))
        val subRows = ArrayList<TextView>()
        var anySub = false
        var m = 0
        for (g in groups) {
            if (g.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                m++
                val sel = g.isTrackSelected(i)
                if (sel) anySub = true
                subRows.add(optionRow(textLabel(g.getTrackFormat(i), m), sel) { chooseText(g, i) })
            }
        }
        if (m == 0) box.addView(noteRow(tx("noSubs")))
        else {
            box.addView(optionRow(tx("off"), !anySub) { subsOff() })
            for (r in subRows) box.addView(r)
            box.addView(noteRow(tx("size")))
            box.addView(sizeRow())
        }

        /* quality */
        box.addView(sectionTitle("🎬  " + tx("quality")))
        val video = ArrayList<Pair<Tracks.Group, Int>>()
        for (g in groups) {
            if (g.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until g.length)
                if (g.isTrackSupported(i) && g.getTrackFormat(i).height > 0) video.add(Pair(g, i))
        }
        video.sortByDescending { (g, i) -> g.getTrackFormat(i).height * 100000L + maxOf(0, g.getTrackFormat(i).bitrate) / 1000 }
        val nowH = try { p?.videoFormat?.height ?: 0 } catch (e: Exception) { 0 }
        box.addView(optionRow(tx("auto") + (if (!qualityPinned && nowH > 0) "  ·  ${nowH}p" else ""), !qualityPinned) { qualityAuto() })
        if (video.size <= 1) box.addView(noteRow(tx("oneVideo")))
        else {
            val seen = HashSet<String>()
            for ((g, i) in video) {
                val lbl = qualityLabel(g.getTrackFormat(i))
                if (!seen.add(lbl)) continue
                box.addView(optionRow(lbl, qualityPinned && g.isTrackSelected(i)) { chooseQuality(g, i, lbl) })
            }
        }

        /* how fast it plays - films and series only */
        box.addView(sectionTitle("⏩  " + tx("speed")))
        if (isVod(currentUrl ?: "")) box.addView(speedRows())
        else box.addView(noteRow(tx("speedLive")))

        /* the sound carries on with the app away */
        box.addView(sectionTitle("🎧  " + tx("radio")))
        box.addView(optionRow(tx("radioOn"), radioOn()) { setRadio(!radioOn()) })
        box.addView(noteRow(tx("radioNote")))

        if (panelOn() && !panel.isInTouchMode) focusPanel(focusKey)
    }

    /** play exactly this track of its kind */
    private fun useTrack(g: Tracks.Group, i: Int): Boolean {
        val p = player ?: return false
        return try {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(g.type, false)
                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                .build()
            true
        } catch (e: Exception) { false }
    }

    private fun chooseAudio(g: Tracks.Group, i: Int) {
        if (!useTrack(g, i)) return
        val f = g.getTrackFormat(i)
        val lang = f.language
        val e = prefs().edit()
        if (lang.isNullOrBlank() || lang == C.LANGUAGE_UNDETERMINED) e.remove("audioLang") else e.putString("audioLang", lang)
        e.apply()
        closePanel()
        showHint(audioLabel(f, 1))
    }

    private fun chooseText(g: Tracks.Group, i: Int) {
        if (!useTrack(g, i)) return
        val f = g.getTrackFormat(i)
        val lang = f.language
        prefs().edit().putString("textLang",
            if (lang.isNullOrBlank() || lang == C.LANGUAGE_UNDETERMINED) C.LANGUAGE_UNDETERMINED else lang).apply()
        closePanel()
        showHint(textLabel(f, 1))
    }

    private fun subsOff() {
        val p = player ?: return
        try {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        } catch (e: Exception) { return }
        prefs().edit().putString("textLang", "off").apply()
        closePanel()
        showHint(tx("off"))
    }

    private fun chooseQuality(g: Tracks.Group, i: Int, label: String) {
        if (!useTrack(g, i)) return
        qualityPinned = true
        closePanel()
        showHint(label)
    }

    private fun qualityAuto() {
        val p = player ?: return
        try {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
        } catch (e: Exception) { return }
        qualityPinned = false
        closePanel()
        showHint(tx("auto"))
    }

    /** A new player starts with the language choices made before. With no
     *  choice made yet nothing is touched - exactly the old behaviour. */
    private fun applyTrackPrefs(p: ExoPlayer) {
        qualityPinned = false
        val pr = prefs()
        val audio = pr.getString("audioLang", null)
        val text = pr.getString("textLang", null)
        /* a subtitle the person chose on the film's page is shown, whatever was set before */
        val chosen = subsByUrl.values.flatten().firstOrNull { it.def }
        if (chosen != null) {
            try {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setPreferredTextLanguage(chosen.lang)
                    .apply { if (audio != null) setPreferredAudioLanguage(audio) }
                    .build()
            } catch (e: Exception) { }
            return
        }
        if (audio == null && text == null) return
        try {
            val b = p.trackSelectionParameters.buildUpon()
            if (audio != null) b.setPreferredAudioLanguage(audio)
            when (text) {
                null -> {}
                "off" -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                C.LANGUAGE_UNDETERMINED -> {
                    b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    b.setSelectUndeterminedTextLanguage(true)
                }
                else -> {
                    b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    b.setPreferredTextLanguage(text)
                }
            }
            p.trackSelectionParameters = b.build()
        } catch (e: Exception) { /* keep the player's defaults */ }
    }

    // ---------------- swipe: brightness (left) / volume (right) ----------------

    /** Only while the player is on screen, unlocked, with the ⚙ panel and the
     *  channel list both closed - a list is there to be scrolled, and a finger
     *  moving up it must not be read as brightness or sound.
     *  Taps, the seek bar and the buttons get every touch exactly as before;
     *  only once a finger clearly moves up or down does the swipe take over. */
    private fun swipeAllowed(): Boolean =
        ::swipe.isInitialized && ::playerLayer.isInitialized && ::panel.isInitialized &&
            playerLayer.visibility == View.VISIBLE && !locked && !panelOn() &&
            !chansOn() && !inPip

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!swipeAllowed()) {
            if (::swipe.isInitialized && ev.actionMasked == MotionEvent.ACTION_DOWN) swipe.end()
            return super.dispatchTouchEvent(ev)
        }
        val act = ev.actionMasked
        if (act == MotionEvent.ACTION_DOWN) {
            val d = window.decorView
            swipe.down(ev.x, ev.y, d.width.toFloat(), d.height.toFloat())
            return super.dispatchTouchEvent(ev)
        }
        if (swipe.kind == SwipeGesture.NONE) {
            if (act == MotionEvent.ACTION_MOVE && swipe.move(ev.x, ev.y, ev.pointerCount)) {
                startSwipe()
                /* the player underneath must not also take this as a tap */
                val c = MotionEvent.obtain(ev)
                c.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(c)
                c.recycle()
                return true
            }
            if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) swipe.end()
            return super.dispatchTouchEvent(ev)
        }
        /* a swipe is running: it keeps the rest of this touch to itself */
        when (act) {
            MotionEvent.ACTION_MOVE -> updateSwipe(ev.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> swipe.end()
        }
        return true
    }

    private fun audio(): AudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun systemBrightness(): Float =
        (try { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f }
         catch (e: Exception) { 0.5f }).coerceIn(0.01f, 1f)

    private fun setWindowBrightness(v: Float) {
        try {
            val lp = window.attributes
            lp.screenBrightness = v
            window.attributes = lp
        } catch (e: Exception) { /* brightness is a nicety, never a crash */ }
    }

    private fun startSwipe() {
        if (swipe.kind == SwipeGesture.BRIGHTNESS) {
            val cur = try { window.attributes.screenBrightness } catch (e: Exception) { -1f }
            swipeStartBright = if (cur >= 0f) cur else systemBrightness()
        } else {
            swipeStartVol = try { audio().getStreamVolume(AudioManager.STREAM_MUSIC) } catch (e: Exception) { 0 }
        }
    }

    private fun updateSwipe(y: Float) {
        val p = swipe.progress(y)
        if (swipe.kind == SwipeGesture.BRIGHTNESS) {
            val v = (swipeStartBright + p).coerceIn(0.01f, 1f)
            playerBrightness = v
            setWindowBrightness(v)
            showHint("☀︎  " + (v * 100).roundToInt() + "%")
        } else {
            try {
                val am = audio()
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (max <= 0) return
                val v = (swipeStartVol + p * max).roundToInt().coerceIn(0, max)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                showHint((if (v == 0) "🔇" else "🔊") + "  " + (v * 100 / max) + "%")
            } catch (e: Exception) { /* volume is a nicety, never a crash */ }
        }
    }

    // ---------------- picture-in-picture (Android 8+) ----------------

    private fun inPipNow(): Boolean =
        inPip || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode)

    /** the small window takes the picture's shape (Android allows 1:2.39 .. 2.39:1) */
    private fun pipRatio(): Rational {
        val vs = try { player?.videoSize } catch (e: Exception) { null }
        if (vs != null && vs.width > 0 && vs.height > 0) {
            val r = vs.width * vs.pixelWidthHeightRatio / vs.height
            if (r in 0.42f..2.38f) return Rational((r * 1000).roundToInt(), 1000)
        }
        return Rational(16, 9)
    }

    private fun pipParams(auto: Boolean): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val b = PictureInPictureParams.Builder().setAspectRatio(pipRatio())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setAutoEnterEnabled(auto)
        return b.build()
    }

    /** Android 12+: go small by itself on Home, but only while a video is open */
    private fun setPipAuto(on: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try { pipParams(on)?.let { setPictureInPictureParams(it) } } catch (e: Exception) { /* no PiP here */ }
    }

    /** Android 8 - 11: Home while watching */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return     /* 12+ does it by itself */
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (radioOn()) return                                          /* sound only, no small window */
        if (!::playerLayer.isInitialized || playerLayer.visibility != View.VISIBLE || dead) return
        try {
            if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
            pipParams(false)?.let { enterPictureInPictureMode(it) }
        } catch (e: Exception) { /* no PiP here: it simply pauses, as before */ }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) { enterPipUi(); return }
        /* the small window was closed, or opened back to full screen */
        pipEndedAt = SystemClock.uptimeMillis()
        if (stopped) closeFromPip() else exitPipUi()
    }

    /** nothing to press in the small window: no bars, no buttons */
    private fun enterPipUi() {
        closePanel()
        if (locked) setLocked(false)
        playerView.hideController()
        playerView.useController = false
        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
        btnUnlock.visibility = View.GONE
        txtHint.visibility = View.GONE
    }

    private fun exitPipUi() {
        playerView.useController = true
        topBar.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        playerView.showController()
    }

    /** the small window was closed: stop and go back to the app's pages */
    private fun closeFromPip() {
        inPip = false
        if (::playerLayer.isInitialized && playerLayer.visibility == View.VISIBLE) hideNativePlayer()
    }

    override fun onStart() {
        super.onStart()
        stopped = false
    }

    /**
     * The page keeps the posters it has already fetched so that moving between
     * Live TV, Films and Series does not fetch them all over again - this
     * panel's picture server forbids them being kept any other way. On a box
     * with little room to spare that is the first thing that should go, so the
     * system's own warning is passed straight through to it.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < TRIM_MEMORY_RUNNING_LOW) return
        try { webView.evaluateJavascript("window.imgForget&&imgForget()", null) } catch (e: Exception) { }
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        /* the small window was closed (Android may report its end just before this) */
        if (inPip || SystemClock.uptimeMillis() - pipEndedAt < 2000) { closeFromPip(); return }
        /* never keep playing unseen - onPause normally did this already.
           Radio mode is the one thing the user asked to go on hearing. */
        if (::playerLayer.isInitialized && playerLayer.visibility == View.VISIBLE) {
            reportPosition()
            if (!radioOn()) {
                player?.playWhenReady = false
                sleepPlayer()          /* and give the connection back to the account */
            }
        }
    }

    // ---------------- lifecycle ----------------

    override fun onPause() {
        super.onPause()
        reportPosition()
        if (inPipNow()) return                           /* the small window keeps playing */
        if (keepPlaying()) { startRadio(); return }       /* ...and so does radio mode */
        player?.playWhenReady = false
    }

    override fun onResume() {
        super.onResume()
        stopRadio()                                      /* back on screen: no notification */
        /* a Google Play update that was under way when the app went away
           carries on where it was */
        if (!canSelfInstall()) try { playUpdate(resumeOnly = true) } catch (e: Exception) { }
        if (playerLayer.visibility == View.VISIBLE) {
            wakePlayer()                                 /* opens the line again where it was */
            player?.playWhenReady = true
            setFullscreen(true)
            handler.removeCallbacks(posTicker)      /* never stack two tickers */
            handler.postDelayed(posTicker, 5000)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopRadio()
        releaseSession()
        player?.release()
        player = null
        nowTitle = ""
        webView.destroy()
        super.onDestroy()
    }
}

/**
 * Decides whether a touch is an up / down swipe, and on which half of the
 * screen. No Android types in here, so it can be checked on its own.
 *  - a touch that starts in the top or bottom edge strip is left alone
 *    (that is where the phone's own swipes live)
 *  - a clear sideways move first (the seek bar) is left alone
 *  - two fingers are left alone
 */
class SwipeGesture(private val slopPx: Float, private val edgePx: Float) {
    companion object {
        const val NONE = 0
        const val BRIGHTNESS = 1
        const val VOLUME = 2
    }

    var kind = NONE
        private set
    private var side = NONE
    private var x0 = 0f
    private var y0 = 0f
    private var h = 1f
    private var tracking = false

    fun down(x: Float, y: Float, width: Float, height: Float) {
        kind = NONE
        x0 = x
        y0 = y
        h = if (height > 0f) height else 1f
        side = if (x < width / 2f) BRIGHTNESS else VOLUME
        tracking = height > 0f && y > edgePx && y < height - edgePx
    }

    /** true at the moment the swipe starts */
    fun move(x: Float, y: Float, pointers: Int): Boolean {
        if (!tracking || kind != NONE) return false
        if (pointers > 1) { tracking = false; return false }
        val dx = x - x0
        val dy = y - y0
        if (abs(dy) > slopPx && abs(dy) > abs(dx) * 1.5f) {
            kind = side
            y0 = y                       /* measure from here, so nothing jumps */
            return true
        }
        if (abs(dx) > slopPx * 2f) tracking = false
        return false
    }

    /** distance travelled since the swipe started, as a share of 3/4 of the
     *  screen height; up is positive */
    fun progress(y: Float): Float = (y0 - y) / (h * 0.75f)

    fun end() {
        kind = NONE
        tracking = false
    }
}
