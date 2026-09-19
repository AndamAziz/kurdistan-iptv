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

import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

import androidx.media3.ui.PlayerView


class MainActivity : ComponentActivity() {

    /*
     * IPTV servers sometimes require a VLC-like User-Agent.
     */
    private val UA =
        "VLC/3.0.20 LibVLC/3.0.20"

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


    // ============================================================
    // ACTIVITY
    // ============================================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_main
        )

        /*
         * Allow fullscreen content around display cutout
         * on Android 9+.
         */
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.P
        ) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams
                    .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }


        // --------------------------------------------------------
        // Find views
        // --------------------------------------------------------

        webView =
            findViewById(R.id.webView)

        playerLayer =
            findViewById(R.id.playerLayer)

        playerView =
            findViewById(R.id.playerView)

        loading =
            findViewById(R.id.loading)

        errorBox =
            findViewById(R.id.errorBox)

        txtError =
            findViewById(R.id.txtError)

        txtTitle =
            findViewById(R.id.txtTitle)


        // --------------------------------------------------------
        // Error message
        // --------------------------------------------------------

        txtError.text =
            getString(
                R.string.player_error
            )


        // --------------------------------------------------------
        // Retry button
        // --------------------------------------------------------

        findViewById<TextView>(
            R.id.btnRetry
        ).apply {

            text =
                getString(
                    R.string.retry
                )

            setOnClickListener {

                val url =
                    currentUrl

                if (url != null) {

                    showNativePlayer(
                        url,
                        currentTitle ?: ""
                    )
                }
            }
        }


        // --------------------------------------------------------
        // Close button
        // --------------------------------------------------------

        findViewById<TextView>(
            R.id.btnClose
        ).setOnClickListener {

            hideNativePlayer()
        }


        // --------------------------------------------------------
        // LIVE badge
        // --------------------------------------------------------

        findViewById<TextView>(
            R.id.liveBadge
        ).text =
            getString(
                R.string.live
            )


        // --------------------------------------------------------
        // Configure WebView
        // --------------------------------------------------------

        configureWebView()


        // --------------------------------------------------------
        // Load website
        // --------------------------------------------------------

        webView.loadUrl(
            "file:///android_asset/index.html"
        )


        // --------------------------------------------------------
        // Android back button
        // --------------------------------------------------------

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {

                override fun handleOnBackPressed() {

                    when {

                        /*
                         * Player is currently open
                         */
                        playerLayer.visibility ==
                                View.VISIBLE -> {

                            hideNativePlayer()
                        }


                        /*
                         * WebView has previous page
                         */
                        webView.canGoBack() -> {

                            webView.goBack()
                        }


                        /*
                         * Exit application
                         */
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

            javaScriptEnabled =
                true

            domStorageEnabled =
                true

            mediaPlaybackRequiresUserGesture =
                false

            /*
             * IPTV URLs may be HTTP while the application
             * itself can use HTTPS/file content.
             */
            mixedContentMode =
                WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            allowFileAccess =
                true

            allowContentAccess =
                true

            cacheMode =
                WebSettings.LOAD_DEFAULT
        }


        webView.setBackgroundColor(
            0xFF080B10.toInt()
        )

        webView.overScrollMode =
            View.OVER_SCROLL_NEVER


        WebView.setWebContentsDebuggingEnabled(
            false
        )


        webView.webViewClient =
            WebViewClient()


        webView.webChromeClient =
            WebChromeClient()


        /*
         * JavaScript -> Android
         *
         * JavaScript can call:
         *
         * AndroidPlayer.playNative(url, title)
         */
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
    // BUILD EXOPLAYER
    // ============================================================

    private fun buildPlayer(): ExoPlayer {

        /*
         * --------------------------------------------------------
         * HTTP Data Source
         * --------------------------------------------------------
         */

        val httpDataSource =
            DefaultHttpDataSource.Factory()
                .setUserAgent(UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(30_000)
                .setReadTimeoutMs(30_000)
                .setKeepPostFor302Redirects(true)


        /*
         * --------------------------------------------------------
         * MPEG-TS extractor configuration
         * --------------------------------------------------------
         *
         * Some IPTV servers send MPEG-TS streams in a form
         * that needs additional TS detection flags.
         */

        val tsFlags =
            DefaultTsPayloadReaderFactory
                .FLAG_DETECT_ACCESS_UNITS or
            DefaultTsPayloadReaderFactory
                .FLAG_ALLOW_NON_IDR_KEYFRAMES


        val extractorsFactory =
            DefaultExtractorsFactory()
                .setTsExtractorFlags(
                    tsFlags
                )


        /*
         * --------------------------------------------------------
         * Media Source Factory
         * --------------------------------------------------------
         *
         * DefaultMediaSourceFactory allows Media3 to select
         * the correct source type for:
         *
         * HLS     -> .m3u8
         * DASH    -> .mpd
         * MPEG-TS -> .ts
         * MP4     -> .mp4
         * etc.
         */

        val mediaSourceFactory =
            DefaultMediaSourceFactory(this)
                .setDataSourceFactory(
                    httpDataSource
                )
                .setExtractorsFactory(
                    extractorsFactory
                )


        /*
         * --------------------------------------------------------
         * Renderers
         * --------------------------------------------------------
         */

        val renderersFactory =
            DefaultRenderersFactory(this)
                .setEnableDecoderFallback(
                    true
                )


        /*
         * --------------------------------------------------------
         * Buffer configuration
         * --------------------------------------------------------
         */

        val loadControl =
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    25_000,
                    60_000,
                    2_500,
                    5_000
                )
                .build()


        /*
         * --------------------------------------------------------
         * Create ExoPlayer
         * --------------------------------------------------------
         */

        return ExoPlayer.Builder(
            this,
            renderersFactory
        )
            .setMediaSourceFactory(
                mediaSourceFactory
            )
            .setLoadControl(
                loadControl
            )
            .build()
    }


    // ============================================================
    // MIME TYPE
    // ============================================================

    private fun mimeFor(
        url: String
    ): String? {

        /*
         * Remove query string before checking extension.
         *
         * Example:
         *
         * http://server/live/528.ts?token=123
         */

        val cleanUrl =
            url
                .substringBefore("?")
                .substringBefore("#")
                .lowercase()


        return when {

            cleanUrl.endsWith(".m3u8") ->
                MimeTypes.APPLICATION_M3U8

            cleanUrl.endsWith(".mpd") ->
                MimeTypes.APPLICATION_MPD

            cleanUrl.endsWith(".ts") ->
                MimeTypes.VIDEO_MP2T

            cleanUrl.endsWith(".mp4") ->
                MimeTypes.VIDEO_MP4

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

        /*
         * Save current channel
         */
        currentUrl =
            url

        currentTitle =
            title


        /*
         * Release old player
         */
        player?.release()

        player = null


        /*
         * Reset UI
         */
        errorBox.visibility =
            View.GONE

        loading.visibility =
            View.VISIBLE

        txtTitle.text =
            title


        /*
         * Show player
         */
        playerLayer.visibility =
            View.VISIBLE


        /*
         * Hide WebView
         */
        webView.visibility =
            View.GONE


        /*
         * Landscape
         */
        requestedOrientation =
            ActivityInfo
                .SCREEN_ORIENTATION_SENSOR_LANDSCAPE


        /*
         * Fullscreen
         */
        setFullscreen(true)


        /*
         * Build player
         */
        player =
            buildPlayer()


        player?.let { p ->

            /*
             * Connect PlayerView
             */
            playerView.player =
                p


            /*
             * Detect stream type
             */
            val mime =
                mimeFor(url)


            /*
             * Build MediaItem
             */
            val mediaItemBuilder =
                MediaItem.Builder()
                    .setUri(url)


            /*
             * If URL tells us the MIME type,
             * explicitly provide it.
             */
            if (mime != null) {

                mediaItemBuilder
                    .setMimeType(mime)
            }


            val mediaItem =
                mediaItemBuilder.build()


            // ----------------------------------------------------
            // Player listener
            // ----------------------------------------------------

            p.addListener(
                object : Player.Listener {


                    override fun onPlaybackStateChanged(
                        state: Int
                    ) {

                        when (state) {

                            /*
                             * Stream is buffering
                             */
                            Player.STATE_BUFFERING -> {

                                loading.visibility =
                                    View.VISIBLE
                            }


                            /*
                             * Stream is playing/ready
                             */
                            Player.STATE_READY -> {

                                loading.visibility =
                                    View.GONE
                            }


                            /*
                             * Stream ended
                             */
                            Player.STATE_ENDED -> {

                                loading.visibility =
                                    View.GONE
                            }
                        }
                    }


                    /*
                     * Player error
                     */
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


            /*
             * Set channel
             */
            p.setMediaItem(
                mediaItem
            )


            /*
             * Prepare stream
             */
            p.prepare()


            /*
             * Auto play
             */
            p.playWhenReady =
                true
        }
    }


    // ============================================================
    // HIDE NATIVE PLAYER
    // ============================================================

    private fun hideNativePlayer() {

        /*
         * Release player
         */
        player?.release()

        player = null


        /*
         * Disconnect PlayerView
         */
        playerView.player =
            null


        /*
         * Hide player UI
         */
        playerLayer.visibility =
            View.GONE

        errorBox.visibility =
            View.GONE

        loading.visibility =
            View.GONE


        /*
         * Show WebView again
         */
        webView.visibility =
            View.VISIBLE


        /*
         * Portrait
         */
        requestedOrientation =
            ActivityInfo
                .SCREEN_ORIENTATION_PORTRAIT


        /*
         * Exit fullscreen
         */
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

            /*
             * Hide status/navigation bars
             */
            controller.hide(
                WindowInsetsCompat.Type.systemBars()
            )


            controller.systemBarsBehavior =
                WindowInsetsControllerCompat
                    .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        } else {

            /*
             * Show system bars
             */
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
         * Pause playback when application
         * goes to background.
         */
        player?.playWhenReady =
            false
    }


    override fun onResume() {

        super.onResume()

        /*
         * Resume player when application
         * returns to foreground.
         */
        if (
            playerLayer.visibility ==
            View.VISIBLE
        ) {

            player?.playWhenReady =
                true

            setFullscreen(true)
        }
    }


    override fun onDestroy() {

        /*
         * Release ExoPlayer
         */
        player?.release()

        player = null


        /*
         * Destroy WebView
         */
        webView.destroy()


        super.onDestroy()
    }
}
