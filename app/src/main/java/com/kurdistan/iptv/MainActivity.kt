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

    /*
     * VLC-style User-Agent.
     * Some IPTV/Xtream servers reject the default Android player User-Agent.
     */
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

        // Allow content to use the display cutout area on Android 9+
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

        // Default error message
        txtError.text = getString(R.string.player_error)

        // Retry button
        findViewById<TextView>(R.id.btnRetry).apply {
            text = getString(R.string.retry)

            setOnClickListener {
                currentUrl?.let { url ->
                    showNativePlayer(
                        url,
                        currentTitle ?: ""
                    )
                }
            }
        }

        // Close player button
        findViewById<TextView>(R.id.btnClose).setOnClickListener {
            hideNativePlayer()
        }

        // LIVE badge
        findViewById<TextView>(R.id.liveBadge).text =
            getString(R.string.live)

        // Configure WebView
        configureWebView()

        // Load IPTV web interface
        webView.loadUrl("file:///android_asset/index.html")

        // Android back button
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {

                override fun handleOnBackPressed() {

                    when {
                        // Player is open
                        playerLayer.visibility == View.VISIBLE -> {
                            hideNativePlayer()
                        }

                        // WebView has history
                        webView.canGoBack() -> {
                            webView.goBack()
                        }

                        // Exit application
                        else -> {
                            finish()
                        }
                    }
                }
            }
        )
    }

    // ============================================================
    // WEBVIEW
    // ============================================================

    private fun configureWebView() {

        webView.settings.apply {

            javaScriptEnabled = true

            domStorageEnabled = true

            mediaPlaybackRequiresUserGesture = false

            mixedContentMode =
                WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            allowFileAccess = true

            allowContentAccess = true

            cacheMode = WebSettings.LOAD_DEFAULT
        }

        webView.setBackgroundColor(
            0xFF080B10.toInt()
        )

        webView.overScrollMode =
            View.OVER_SCROLL_NEVER

        WebView.setWebContentsDebuggingEnabled(false)

        webView.webViewClient = WebViewClient()

        webView.webChromeClient = WebChromeClient()

        // JavaScript -> Android bridge
        webView.addJavascriptInterface(
            AndroidBridge(this),
            "AndroidPlayer"
        )
    }

    // ============================================================
    // JAVASCRIPT BRIDGE
    // ============================================================

    inner class AndroidBridge(
        private val context: Context
    ) {

        @android.webkit.JavascriptInterface
        fun playNative(
            url: String,
            title: String
        ) {
            runOnUiThread {
                showNativePlayer(
                    url,
                    title
                )
            }
        }
    }

    // ============================================================
    // EXOPLAYER
    // ============================================================

    private fun buildPlayer(): ExoPlayer {

        val httpDataSource =
            DefaultHttpDataSource.Factory()
                .setUserAgent(UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(20_000)
                .setReadTimeoutMs(20_000)
                .setKeepPostFor302Redirects(true)

        /*
         * Decoder fallback:
         * If the preferred hardware decoder fails,
         * Media3 can try another decoder.
         */
        val renderersFactory =
            DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)

        /*
         * Buffer configuration suitable for IPTV/live streams.
         */
        val loadControl =
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    25_000, // min buffer
                    60_000, // max buffer
                    2_500,  // playback buffer
                    5_000   // rebuffer
                )
                .build()

        return ExoPlayer.Builder(
            this,
            renderersFactory
        )
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(
                        httpDataSource
                    )
            )
            .setLoadControl(loadControl)
            .build()
    }

    // ============================================================
    // MIME TYPE DETECTION
    // ============================================================

    private fun mimeFor(url: String): String? {

        val u = url.lowercase()

        return when {

            u.contains(".m3u8") ->
                MimeTypes.APPLICATION_M3U8

            u.contains(".mpd") ->
                MimeTypes.APPLICATION_MPD

            u.contains(".ts") ->
                MimeTypes.VIDEO_MP2T

            else ->
                null
        }
    }

    // ============================================================
    // SHOW NATIVE PLAYER
    // ============================================================

    private fun showNativePlayer(
        url: String,
        title: String
    ) {

        currentUrl = url
        currentTitle = title

        // Release previous player
        player?.release()
        player = null

        // Reset UI
        errorBox.visibility = View.GONE

        loading.visibility = View.VISIBLE

        txtTitle.text = title

        // Show native player
        playerLayer.visibility = View.VISIBLE

        // Hide WebView while playing
        webView.visibility = View.GONE

        // Landscape mode
        requestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // Fullscreen
        setFullscreen(true)

        // Create new player
        player = buildPlayer()

        player?.let { p ->

            playerView.player = p

            // Build MediaItem
            val mediaItemBuilder =
                MediaItem.Builder()
                    .setUri(url)

            // Detect MIME type
            val mimeType = mimeFor(url)

            if (mimeType != null) {
                mediaItemBuilder.setMimeType(mimeType)
            }

            /*
             * Player listener
             */
            p.addListener(
                object : Player.Listener {

                    override fun onPlaybackStateChanged(
                        state: Int
                    ) {

                        loading.visibility =
                            when (state) {

                                Player.STATE_BUFFERING ->
                                    View.VISIBLE

                                Player.STATE_READY ->
                                    View.GONE

                                Player.STATE_ENDED ->
                                    View.GONE

                                else ->
                                    loading.visibility
                            }
                    }

                    override fun onPlayerError(
                        error: PlaybackException
                    ) {

                        loading.visibility =
                            View.GONE

                        txtError.text =
                            getString(
                                R.string.player_error
                            ) +
                            "\n\n" +
                            error.errorCodeName

                        errorBox.visibility =
                            View.VISIBLE
                    }
                }
            )

            // Set media
            p.setMediaItem(
                mediaItemBuilder.build()
            )

            // Prepare
            p.prepare()

            // Start automatically
            p.playWhenReady = true
        }
    }

    // ============================================================
    // HIDE PLAYER
    // ============================================================

    private fun hideNativePlayer() {

        player?.release()

        player = null

        playerView.player = null

        playerLayer.visibility =
            View.GONE

        errorBox.visibility =
            View.GONE

        loading.visibility =
            View.GONE

        webView.visibility =
            View.VISIBLE

        // Back to portrait
        requestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // Exit fullscreen
        setFullscreen(false)
    }

    // ============================================================
    // FULLSCREEN
    // ============================================================

    private fun setFullscreen(
        on: Boolean
    ) {

        WindowCompat.setDecorFitsSystemWindows(
            window,
            !on
        )

        val controller =
            WindowCompat.getInsetsController(
                window,
                window.decorView
            )

        if (on) {

            controller.hide(
                WindowInsetsCompat.Type.systemBars()
            )

            controller.systemBarsBehavior =
                WindowInsetsControllerCompat
                    .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        } else {

            controller.show(
                WindowInsetsCompat.Type.systemBars()
            )
        }
    }

    // ============================================================
    // LIFECYCLE
    // ============================================================

    override fun onPause() {

        super.onPause()

        /*
         * Pause playback when app goes into background.
         */
        player?.playWhenReady = false
    }

    override fun onResume() {

        super.onResume()

        /*
         * Resume playback when app returns.
         */
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
