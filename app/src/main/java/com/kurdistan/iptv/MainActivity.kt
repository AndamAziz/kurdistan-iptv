package com.kurdistan.iptv

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

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

    private val MAX_RECONNECTS = 8
    private val RECONNECT_DELAY_MS = 1200L

    private lateinit var webView: WebView
    private lateinit var playerLayer: View
    private lateinit var playerView: PlayerView
    private lateinit var loading: ProgressBar
    private lateinit var errorBox: LinearLayout
    private lateinit var txtError: TextView
    private lateinit var txtTitle: TextView

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var topBar: View

    private var player: ExoPlayer? = null
    private var currentUrl: String? = null
    private var currentTitle: String? = null

    /** the queue the next / previous buttons walk through */
    private var urls: List<String> = emptyList()
    private var titles: List<String> = emptyList()

    /** some channels only answer to their own agent / referrer */
    private var uas: List<String?> = emptyList()
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

    /* ---- swipe up / down: left half = brightness, right half = volume ---- */
    private lateinit var swipe: SwipeGesture
    private var swipeStartBright = 0.5f
    private var swipeStartVol = 0
    /** the brightness picked in the player this session (-1 = the phone's own) */
    private var playerBrightness = -1f

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

        playerView.controllerShowTimeoutMs = 3500
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                topBar.visibility = visibility
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

        configureWebView()
        webView.loadUrl("file:///android_asset/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
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
        webView.setBackgroundColor(0xFF080B10.toInt())
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
                openQueue(listOf(url), listOf(title), listOf(null), listOf(null), 0, 0L)
            }
        }

        /** the page's language, so the player's own menu speaks it too */
        @android.webkit.JavascriptInterface
        fun setLang(l: String) {
            runOnUiThread { uiLang = if (l == "ku" || l == "ar") l else "en" }
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
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    u.add(o.optString("u"))
                    n.add(o.optString("n"))
                    a.add(o.optString("ua").ifBlank { null })
                    r.add(o.optString("rf").ifBlank { null })
                }
                if (u.isEmpty()) return
                val start = if (index in u.indices) index else 0
                runOnUiThread { openQueue(u, n, a, r, start, startMs.toLong()) }
            } catch (e: Exception) { /* ignore malformed input */ }
        }
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

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(curUa ?: UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(20000)
            .setKeepPostFor302Redirects(true)

        curRef?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }

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
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .setLoadControl(loadControl)
            .build()
    }

    /** Follow the address by hand the way VLC does - every redirect, keeping
     *  cookies - and look at the first bytes that actually come back. Returns
     *  the address the stream really lives at and what it turned out to be. */
    private fun resolveStream(url: String, ua: String, ref: String?): Pair<String, String?>? {
        var target = url
        var hop = 0
        try {
            while (hop++ < 8) {
                val c = URL(target).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false          /* we follow them ourselves */
                c.connectTimeout = 9000
                c.readTimeout = 9000
                c.setRequestProperty("User-Agent", ua)
                c.setRequestProperty("Accept", "*/*")
                c.setRequestProperty("Connection", "close")
                ref?.let { c.setRequestProperty("Referer", it) }

                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location")
                    c.disconnect()
                    if (loc.isNullOrBlank()) return null
                    target = URL(URL(target), loc).toString()   /* relative or absolute */
                    continue
                }
                if (code !in 200..299) { c.disconnect(); return null }

                val ctype = (c.contentType ?: "").lowercase()
                val head = ByteArray(2048)
                var got = 0
                try {
                    c.inputStream.use { ins ->
                        while (got < head.size) {
                            val r = ins.read(head, got, head.size - got)
                            if (r <= 0) break
                            got += r
                        }
                    }
                } catch (e: Exception) { /* enough is enough */ }
                c.disconnect()

                val text = if (got > 0) String(head, 0, got, Charsets.ISO_8859_1) else ""
                val mime = when {
                    text.startsWith("#EXTM3U") || text.contains("#EXT-X-") ->
                        MimeTypes.APPLICATION_M3U8
                    text.contains("<MPD") -> MimeTypes.APPLICATION_MPD
                    got > 0 && head[0] == 0x47.toByte() -> MimeTypes.VIDEO_MP2T
                    ctype.contains("mpegurl") -> MimeTypes.APPLICATION_M3U8
                    ctype.contains("dash+xml") -> MimeTypes.APPLICATION_MPD
                    ctype.contains("mp2t") -> MimeTypes.VIDEO_MP2T
                    else -> null
                }
                return Pair(target, mime)
            }
        } catch (e: Exception) { /* fall through */ }
        return null
    }

    /** Last resort: the address lied, so go and find the real one. */
    private fun resolveAndRetry(index: Int, fallbackCode: String) {
        val link = urls.getOrNull(index)
        if (link == null) { showError(fallbackCode); return }
        val ua = curUa ?: UA
        val ref = curRef
        loading.visibility = View.VISIBLE
        errorBox.visibility = View.GONE

        Thread {
            val found = resolveStream(link, ua, ref)
            runOnUiThread {
                if (dead || playerLayer.visibility != View.VISIBLE) return@runOnUiThread
                if (found == null) { showError(fallbackCode); return@runOnUiThread }

                val (realUrl, mime) = found
                resolvedUrl = realUrl
                step = 0
                reconnects = 0
                plan = listOf(mime) + ORDER.filter { it != mime }
                val b = MediaItem.Builder().setUri(realUrl)
                mime?.let { b.setMimeType(it) }
                try {
                    val p = player ?: return@runOnUiThread
                    p.replaceMediaItem(index, b.build())
                    p.prepare()
                    p.playWhenReady = true
                } catch (e: Exception) { showError(fallbackCode) }
            }
        }.start()
    }

    private fun isVod(url: String): Boolean {
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

        buildAndStart(index)
        handler.postDelayed(posTicker, 5000)
    }

    private fun buildAndStart(index: Int) {
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
        plan = planFor(currentUrl ?: "")
        applyItemChrome(index)

        val items = urls.mapIndexed { i, link ->
            val b = MediaItem.Builder().setUri(link)
            if (i == index) plan.getOrNull(0)?.let { b.setMimeType(it) }
            else planFor(link).getOrNull(0)?.let { b.setMimeType(it) }
            b.build()
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
                    plan = planFor(currentUrl ?: "")
                    applyItemChrome(i)
                    /* this channel wants a different agent - the data source
                       carries it, so start it over with one that matches */
                    if (curUa != builtUa || curRef != builtRef) {
                        playerView.post {
                            if (playerLayer.visibility == View.VISIBLE && urls.isNotEmpty())
                                buildAndStart(i)
                        }
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_BUFFERING -> loading.visibility = View.VISIBLE
                        Player.STATE_READY -> {
                            loading.visibility = View.GONE
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
                    when {
                        // the container guess was wrong - try the next one for this item
                        !hasPlayed && step < plan.size - 1 -> {
                            step++
                            val link = resolvedUrl ?: urls.getOrNull(i) ?: ""
                            val b = MediaItem.Builder().setUri(link)
                            plan.getOrNull(step)?.let { b.setMimeType(it) }
                            playerView.post {
                                if (dead) return@post
                                try {
                                    p.replaceMediaItem(i, b.build())
                                    p.prepare()
                                    p.playWhenReady = true
                                } catch (e: Exception) { showError(error.errorCodeName) }
                            }
                        }
                        // every container failed: the address is not what it says
                        !hasPlayed && !probed -> {
                            probed = true
                            resolveAndRetry(i, error.errorCodeName)
                        }
                        reconnects < MAX_RECONNECTS -> reconnect()
                        else -> showError(error.errorCodeName)
                    }
                }
            })

            p.setMediaItems(items, index, if (startAtMs > 0) startAtMs else 0L)
            startAtMs = 0L
            p.prepare()
            p.playWhenReady = true
        }
        if (!locked) topBar.visibility = View.VISIBLE
        playerView.showController()          /* does nothing while locked */
    }

    /** Title and LIVE badge for whichever item is on screen. */
    private fun applyItemChrome(index: Int) {
        txtTitle.text = titles.getOrNull(index) ?: ""
        findViewById<TextView>(R.id.liveBadge).visibility =
            if (isVod(urls.getOrNull(index) ?: "")) View.GONE else View.VISIBLE
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

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (dead || playerLayer.visibility != View.VISIBLE) return@postDelayed
            try {
                val p = player
                if (p == null) {
                    startAtMs = keep
                    buildAndStart(i0)
                } else {
                    p.seekTo(p.currentMediaItemIndex, keep)
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
        }, RECONNECT_DELAY_MS)
    }

    private fun showError(code: String) {
        if (locked) setLocked(false)      /* Retry and close must be reachable */
        dead = true                       /* no more retries until the user asks */
        handler.removeCallbacks(posTicker)
        try { player?.playWhenReady = false } catch (e: Exception) {}
        loading.visibility = View.GONE
        txtError.text = getString(R.string.player_error) + "\n\n" + code
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
        player?.release()
        player = null
        playerView.player = null

        playerLayer.visibility = View.GONE
        errorBox.visibility = View.GONE
        loading.visibility = View.GONE
        topBar.visibility = View.VISIBLE
        webView.visibility = View.VISIBLE

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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

    /** The two new buttons sit at the end of the top bar; the lock layer,
     *  the unlock button and the short hint sit on top of the player. */
    private fun buildAspectAndLockUi() {
        val bar = findViewById<LinearLayout>(R.id.topBar)
        val layer = findViewById<FrameLayout>(R.id.playerLayer)

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
        bar.addView(btnAspect, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(10) })

        btnTracks = TextView(this).apply {
            background = roundBg()
            text = "\u2699\uFE0E"
            setTextColor(Color.WHITE)
            textSize = 19f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
        }
        bar.addView(btnTracks, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) })

        btnLock = TextView(this).apply {
            background = roundBg()
            text = "\uD83D\uDD12"
            textSize = 16f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
        }
        bar.addView(btnLock, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) })

        /* takes every touch while locked, so nothing underneath reacts */
        lockOverlay = View(this).apply {
            isClickable = true
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
            playerView.hideController()
            playerView.useController = false
            topBar.visibility = View.GONE
            showHint("\uD83D\uDD12")
            flashUnlock()
        } else {
            btnUnlock.removeCallbacks(hideUnlock)
            btnUnlock.visibility = View.GONE
            playerView.useController = true
            topBar.visibility = View.VISIBLE
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
        setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        closePanel()
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
        "audio" to "Audio", "subs" to "Subtitles", "quality" to "Quality",
        "off" to "Off", "auto" to "Auto", "size" to "Text size",
        "s1" to "Small", "s2" to "Normal", "s3" to "Large", "s4" to "Extra large",
        "noSubs" to "No subtitles in this video", "oneAudio" to "Only one audio track",
        "oneVideo" to "Only one quality", "track" to "Track")
    private val TX_KU = mapOf(
        "audio" to "دەنگ", "subs" to "ژێرنووس", "quality" to "کوالیتی",
        "off" to "بێ ژێرنووس", "auto" to "خۆکار", "size" to "قەبارەی نووسین",
        "s1" to "بچووک", "s2" to "ئاسایی", "s3" to "گەورە", "s4" to "زۆر گەورە",
        "noSubs" to "ئەم ڤیدیۆیە ژێرنووسی نییە", "oneAudio" to "تەنها یەک دەنگ هەیە",
        "oneVideo" to "تەنها یەک کوالیتی هەیە", "track" to "تراک")
    private val TX_AR = mapOf(
        "audio" to "الصوت", "subs" to "الترجمة", "quality" to "الجودة",
        "off" to "بدون ترجمة", "auto" to "تلقائي", "size" to "حجم الخط",
        "s1" to "صغير", "s2" to "عادي", "s3" to "كبير", "s4" to "كبير جداً",
        "noSubs" to "لا توجد ترجمة في هذا الفيديو", "oneAudio" to "يوجد صوت واحد فقط",
        "oneVideo" to "توجد جودة واحدة فقط", "track" to "مسار")

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
        setColor(if (on) 0x33E63E46 else 0x14FFFFFF)
        setStroke(dp(1), if (on) 0xFFE63E46.toInt() else 0x1FFFFFFF)
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
        setTextColor(if (on) 0xFFFF6A5E.toInt() else Color.WHITE)
        textSize = 14f
        if (on) setTypeface(typeface, Typeface.BOLD)
        background = panelBg(on)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        isClickable = true
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
                setTextColor(if (on) 0xFFFF6A5E.toInt() else Color.WHITE)
                textSize = 12f
                if (on) setTypeface(typeface, Typeface.BOLD)
                background = panelBg(on)
                setPadding(dp(4), dp(10), dp(4), dp(10))
                isClickable = true
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

    private fun applySubScale() {
        val s = prefs().getFloat("subScale", 0f)
        if (s > 0f) playerView.subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * s)
    }

    private fun panelOn(): Boolean = panel.visibility == View.VISIBLE

    private fun openPanel() {
        if (locked) return
        val w = resources.displayMetrics.widthPixels
        panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply {
            width = minOf(dp(360), (w * 0.62f).toInt())
        }
        fillPanel()
        panel.scrollTo(0, 0)
        panelScrim.visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
        playerView.hideController()
    }

    private fun closePanel() {
        panel.visibility = View.GONE
        panelScrim.visibility = View.GONE
    }

    /** what the stream offers right now, in three short lists */
    private fun fillPanel() {
        val box = panelBox
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

    /** Only while the player is on screen, unlocked, with the ⚙ panel closed.
     *  Taps, the seek bar and the buttons get every touch exactly as before;
     *  only once a finger clearly moves up or down does the swipe take over. */
    private fun swipeAllowed(): Boolean =
        ::swipe.isInitialized && ::playerLayer.isInitialized && ::panel.isInitialized &&
            playerLayer.visibility == View.VISIBLE && !locked && !panelOn()

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

    // ---------------- lifecycle ----------------

    override fun onPause() {
        super.onPause()
        reportPosition()
        player?.playWhenReady = false
    }

    override fun onResume() {
        super.onResume()
        if (playerLayer.visibility == View.VISIBLE) {
            player?.playWhenReady = true
            setFullscreen(true)
            handler.removeCallbacks(posTicker)      /* never stack two tickers */
            handler.postDelayed(posTicker, 5000)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
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
