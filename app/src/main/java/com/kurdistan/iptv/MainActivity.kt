package com.kurdistan.iptv

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL

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
        topBar.visibility = View.VISIBLE
        playerView.showController()
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
