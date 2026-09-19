package com.kurdistan.iptv

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("kiptv", Context.MODE_PRIVATE)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        playerView = findViewById(R.id.playerView)
        configureWebView()
        webView.loadUrl("file:///android_asset/index.html")

        findViewById<Button>(R.id.closePlayer).setOnClickListener { hideNativePlayer() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (playerView.visibility == View.VISIBLE) {
                    hideNativePlayer()
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
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
        }
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

    private fun showNativePlayer(url: String, title: String) {
        player?.release()
        player = ExoPlayer.Builder(this).build().also { p ->
            playerView.player = p
            val builder = MediaItem.Builder().setUri(url)
            when {
                url.contains(".m3u8", true) -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
                url.matches(Regex(".*\\.(ts|m2ts)(?:[?#].*)?$", RegexOption.IGNORE_CASE)) ||
                    url.contains("extension=ts", true) -> builder.setMimeType(MimeTypes.VIDEO_MP2T)
            }
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Toast.makeText(this@MainActivity, "کێشە لە پەخشی ئەم کەنالەیە: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
                }
            })
            p.setMediaItem(builder.build())
            p.prepare()
            p.playWhenReady = true
        }
        playerView.visibility = View.VISIBLE
        findViewById<View>(R.id.nativeBar).visibility = View.VISIBLE
        findViewById<android.widget.TextView>(R.id.nativeTitle).text = title
    }

    private fun hideNativePlayer() {
        player?.release()
        player = null
        playerView.player = null
        playerView.visibility = View.GONE
        findViewById<View>(R.id.nativeBar).visibility = View.GONE
    }

    override fun onDestroy() {
        player?.release()
        webView.destroy()
        super.onDestroy()
    }
}
