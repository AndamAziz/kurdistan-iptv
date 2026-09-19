package com.kurdistan.iptv

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
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

class MainActivity : ComponentActivity() {

    /* Many IPTV / Xtream servers reject the default player user agent.
       VLC's user agent is the one they are all configured to accept. */
    private val UA = "VLC/3.0.20 LibVLC/3.0.20"

    private lateinit var webView: WebView
    private lateinit var playerLayer: View
    private lateinit var playerView: PlayerView
    private lateinit var loading: ProgressBar
    private lateinit var errorBox: LinearLayout
    private lateinit var txtError: TextView
    private lateinit var txtTitle: TextView

    private var player: ExoPlayer? = null
    private var currentUrl: String? = null
    private var currentTitle: String? = null

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

        txtError.text = getString(R.string.player_error)
        findViewById<TextView>(R.id.btnRetry).apply {
            text = getString(R.string.retry)
            setOnClickListener { currentUrl?.let { u -> showNativePlayer(u, currentTitle ?: "") } }
        }
        findViewById<TextView>(R.id.btnClose).setOnClickListener { hideNativePlayer() }
        findViewById<TextView>(R.id.liveBadge).text = getString(R.string.live)

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
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(AndroidBridge(this), "AndroidPlayer")
    }

    inner class AndroidBridge(private val context: Context) {
        @android.webkit.JavascriptInterface
        fun playNative(url: String, title: String) {
            runOnUiThread { showNativePlayer(url, title) }
        }
    }

    // ---------------- player ----------------

    private fun buildPlayer(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(UA)
            .setAllowCrossProtocolRedirects(true)   // http -> https redirects (Xtream servers)
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(20000)
            .setKeepPostFor302Redirects(true)

        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)         // try another decoder instead of failing

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(25000, 60000, 2500, 5000)
            .build()

        return ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .setLoadControl(loadControl)
            .build()
    }

    private fun mimeFor(url: String): String? {
        val u = url.lowercase()
        return when {
            u.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
            u.contains(".mpd") -> MimeTypes.APPLICATION_MPD
            // only hint MP2T when explicitly specified in the extension parameter
            u.contains("extension=ts") -> MimeTypes.VIDEO_MP2T
            else -> null
        }
    }

    private fun showNativePlayer(url: String, title: String) {
        currentUrl = url
        currentTitle = title

        player?.release()
        errorBox.visibility = View.GONE
        loading.visibility = View.VISIBLE
        txtTitle.text = title

        playerLayer.visibility = View.VISIBLE
        webView.visibility = View.GONE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        setFullscreen(true)

        player = buildPlayer().also { p ->
            playerView.player = p

            val builder = MediaItem.Builder().setUri(url)
            val mime = mimeFor(url)
            if (mime != null) {
                builder.setMimeType(mime)
            }

            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    loading.visibility =
                        if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                }

                override fun onPlayerError(error: PlaybackException) {
                    loading.visibility = View.GONE
                    txtError.text = getString(R.string.player_error) + "\n\n" + error.errorCodeName
                    errorBox.visibility = View.VISIBLE
                }
            })

            p.setMediaItem(builder.build())
            p.prepare()
            p.playWhenReady = true
        }
    }

    private fun hideNativePlayer() {
        player?.release()
        player = null
        playerView.player = null

        playerLayer.visibility = View.GONE
        errorBox.visibility = View.GONE
        loading.visibility = View.GONE
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
        player?.playWhenReady = false
    }

    override fun onResume() {
        super.onResume()
        if (playerLayer.visibility == View.VISIBLE) {
            player?.playWhenReady = true
            setFullscreen(true)
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        webView.destroy()
        super.onDestroy()
    }
}
