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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

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
    private var sn: List<Boolean> = emptyList()
    private var curUa: String? = null
    private var curRef: String? = null
    private var builtUa: String? = null
    private var builtRef: String? = null

    /** what the first bytes of a stream turned out to be, per url */
    private val sniffed = HashMap<String, String?>()
    private val io = Executors.newSingleThreadExecutor()

    private var startAtMs = 0L
    private val posTicker = object : Runnable {
        override fun run() {
            reportPosition()
            handler.postDelayed(this, 5000)
        }
    }

    private var plan: List<String?> = emptyList()
    private var step = 0
    private var hasPlayed = false
    private var reconnects = 0

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
                val i = player?.currentMediaItemIndex ?: 0
                if (urls.isNotEmpty()) buildAndStart(i)
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
                openQueue(listOf(url), listOf(title), listOf(null), listOf(null), listOf(false), 0, 0L)
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
                val q = ArrayList<Boolean>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    u.add(o.optString("u"))
                    n.add(o.optString("n"))
                    a.add(o.optString("ua").ifBlank { null })
                    r.add(o.optString("rf").ifBlank { null })
                    q.add(o.optInt("sn", 0) == 1)
                }
                if (u.isEmpty()) return
                val start = if (index in u.indices) index else 0
                runOnUiThread { openQueue(u, n, a, r, q, start, startMs.toLong()) }
            } catch (e: Exception) { /* ignore malformed input */ }
        }
    }

    // ---------------- format plan ----------------

    private fun guessFor(url: String): String? {
        val full = url.lowercase()
        val path = full.substringBefore('?')
        return when {
            path.contains(".mpd") -> MimeTypes.APPLICATION_MPD
            path.contains(".m3u8") || path.endsWith(".m3u") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".ts") || full.contains("extension=ts") -> null
            path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".avi") ||
            path.endsWith(".webm") || path.endsWith(".flv") || path.endsWith(".mov") -> null
            path.matches(Regex(".*\\.[a-z0-9]{2,5}$")) -> null
            else -> MimeTypes.APPLICATION_M3U8
        }
    }

    private fun planFor(url: String): List<String?> {
        val best = if (sniffed.containsKey(url)) sniffed[url] else guessFor(url)
        return listOf(best) + ORDER.filter { it != best }
    }

    private fun needsSniff(url: String, allowed: Boolean): Boolean {
        if (!allowed) return false
        val path = url.lowercase().substringBefore('?')
        if (path.contains(".m3u8") || path.contains(".mpd")) return false
        return true
    }

    private fun sniffMime(url: String, ua: String?, ref: String?): String? {
        return try {
            val finalUa = ua ?: UA
            val c = openFollowingRedirects(url, finalUa)
            c.requestMethod = "GET"
            c.setRequestProperty("Range", "bytes=0-1023")
            ref?.let { c.setRequestProperty("Referer", it) }
            
            val head = ByteArray(1024)
            var got = 0
            val code = c.responseCode
            if (code in 200..299) {
                c.inputStream.use { ins ->
                    while (got < head.size) {
                        val r = ins.read(head, got, head.size - got)
                        if (r <= 0) break
                        got += r
                    }
                }
            }
            val landed = c.url.toString().lowercase().substringBefore('?')
            c.disconnect()
            if (got <= 0) return null

            val text = String(head, 0, got, Charsets.ISO_8859_1)
            when {
                text.startsWith("#EXTM3U") || text.contains("#EXT-X-") -> MimeTypes.APPLICATION_M3U8
                text.contains("<MPD") -> MimeTypes.APPLICATION_MPD
                head[0] == 0x47.toByte() -> MimeTypes.VIDEO_MP2T
                landed.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
                landed.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
                else -> null
            }
        } catch (e: Exception) { null }
    }

    // ---------------- player ----------------

    private fun buildPlayer(): ExoPlayer {
        builtUa = curUa ?: UA
        builtRef = curRef

        val resolvedUa = if (builtUa.isNullOrBlank()) UA else builtUa!!

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(resolvedUa)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(25000)
            .setReadTimeoutMs(25000)
            .setKeepPostFor302Redirects(true)

        builtRef?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }

        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(25000, 60000, 2500, 5000)
            .build()

        return ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .setLoadControl(loadControl)
            .build()
    }

    private fun isVod(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("/movie/") || u.contains("/series/")
    }

    private fun openQueue(
        u: List<String>, n: List<String>,
        a: List<String?>, r: List<String?>, q: List<Boolean>,
        index: Int, startMs: Long
    ) {
        handler.removeCallbacksAndMessages(null)
        urls = u
        titles = n
        uas = a
        refs = r
        sn = q
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

        val link = u.getOrNull(index)
        if (link != null && needsSniff(link, sn.getOrNull(index) == true) &&
            !sniffed.containsKey(link)) {
            val ua = curUa; val rf = curRef
            io.execute {
                val m = sniffMime(link, ua, rf)
                runOnUiThread {
                    if (playerLayer.visibility != View.VISIBLE) return@runOnUiThread
                    if (m != null) sniffed[link] = m
                    buildAndStart(index)
                    handler.postDelayed(posTicker, 5000)
                }
            }
            return
        }

        buildAndStart(index)
        handler.postDelayed(posTicker, 5000)
    }

    private fun buildAndStart(index: Int) {
        player?.release()
        errorBox.visibility = View.GONE
        loading.visibility = View.VISIBLE

        step = 0
        hasPlayed = false
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
                    reportPosition()
                    val i = p.currentMediaItemIndex
                    step = 0
                    hasPlayed = false
                    reconnects = 0
                    currentUrl = urls.getOrNull(i)
                    currentTitle = titles.getOrNull(i)
                    curUa = uas.getOrNull(i)
                    curRef = refs.getOrNull(i)
                    plan = planFor(currentUrl ?: "")
                    applyItemChrome(i)
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
                            val live = !isVod(currentUrl ?: "")
                            if (live && reconnects < MAX_RECONNECTS) reconnect()
                            else if (p.hasNextMediaItem()) p.seekToNextMediaItem()
                            else showError("ENDED")
                        }
                        else -> {}
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    val i = p.currentMediaItemIndex
                    when {
                        !hasPlayed && step < plan.size - 1 -> {
                            step++
                            val b = MediaItem.Builder().setUri(urls.getOrNull(i) ?: "")
                            plan.getOrNull(step)?.let { b.setMimeType(it) }
                            playerView.post {
                                try {
                                    p.replaceMediaItem(i, b.build())
                                    p.prepare()
                                    p.playWhenReady = true
                                } catch (e: Exception) { showError(error.errorCodeName) }
                            }
                        }
                        // کاتێک پەخشەکە دەوەستێت بەهۆی کۆد یان ڕەتکردنەوە، بۆ جارێکی تر بە BROWSER_UA تاقی دەکاتەوە
                        reconnects == 0 && curUa != BROWSER_UA -> {
                            curUa = BROWSER_UA
                            playerView.post { buildAndStart(i) }
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

    private fun applyItemChrome(index: Int) {
        txtTitle.text = titles.getOrNull(index) ?: ""
        findViewById<TextView>(R.id.liveBadge).visibility =
            if (isVod(urls.getOrNull(index) ?: "")) View.GONE else View.VISIBLE
    }

    private fun reconnect() {
        if (playerLayer.visibility != View.VISIBLE) return
        reconnects++
        loading.visibility = View.VISIBLE
        val i0 = player?.currentMediaItemIndex ?: 0
        val keep = if (isVod(urls.getOrNull(i0) ?: "")) (player?.currentPosition ?: 0L) else 0L
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            val i = player?.currentMediaItemIndex ?: i0
            player?.let { p ->
                try {
                    p.seekTo(i, keep)
                    p.prepare()
                    p.playWhenReady = true
                } catch (e: Exception) {
                    startAtMs = keep
                    buildAndStart(i)
                }
            }
            handler.postDelayed(posTicker, 5000)
        }, RECONNECT_DELAY_MS)
    }

    private fun showError(code: String) {
        loading.visibility = View.GONE
        txtError.text = getString(R.string.player_error) + "\n\n" + code
        errorBox.visibility = View.VISIBLE
    }

    private fun reportPosition() {
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
            handler.postDelayed(posTicker, 5000)
        }
    }

    override fun onDestroy() {
        io.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        webView.destroy()
        super.onDestroy()
    }
}
