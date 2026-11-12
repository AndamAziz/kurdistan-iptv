package com.kurdistan.iptv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * Movies Online: VidAPI's web player, full screen, inside the app.
 *
 * Only the GitHub build has this screen (app/src/direct); the page offers
 * Movies Online only when Android says it is there. The player stays on its
 * own pages: a pop-up or a new tab is refused, the main page is never sent to
 * another site, and nothing is downloaded. A thin bar at the top (title,
 * servers, close) shows on a touch or the Menu key and fades by itself.
 */
class EmbedActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var bar: LinearLayout
    private var srvBtn: TextView? = null
    private var closeBtn: TextView? = null

    private var urls = listOf<String>()
    private var labels = listOf<String>()
    private var at = 0
    /* the sites this player may show as its main page */
    private val allowed = mutableSetOf("vaplayer.ru", "vidapi.xyz", "vidapi.site", "vidsrc.sh", "mv.andam.uk")

    private var custom: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private val ui = Handler(Looper.getMainLooper())
    private val fade = Runnable { hideBar() }

    private val isTv: Boolean by lazy {
        val m = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        m?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature("android.software.leanback")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val spec = try { JSONObject(intent.getStringExtra(SPEC) ?: "{}") } catch (e: Exception) { JSONObject() }
        urls = spec.optJSONArray("urls")?.let { a -> (0 until a.length()).map { a.optString(it) } }
            ?.filter { it.startsWith("https://") || it.startsWith("http://") } ?: emptyList()
        labels = spec.optJSONArray("labels")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        if (urls.isEmpty()) { finish(); return }
        urls.forEach { u -> Uri.parse(u).host?.let { allowed.add(it.lowercase()) } }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!isTv) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)          /* window.open asks us - and is refused */
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            /* the phone's own Chrome, not "a web view inside an app" */
            userAgentString = userAgentString.replace("; wv", "").replace(Regex(" Version/\\d+(\\.\\d+)*"), "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.setDownloadListener { _, _, _, _, _ -> }     /* nothing is downloaded */

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                if (!req.isForMainFrame) return false     /* the player's own frames */
                val u = req.url
                val s = u.scheme?.lowercase() ?: return true
                if (s != "http" && s != "https") return true    /* intent://, market://: never */
                return !hostOk(u.host)                    /* an advert taking over the page: refused */
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                finish()
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean = false

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (custom != null) { callback.onCustomViewHidden(); return }
                custom = view
                customCb = callback
                root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                bar.bringToFront()
                immersive()
            }

            override fun onHideCustomView() = leaveCustom()

            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        }

        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bar = makeBar(spec.optString("title"), spec.optString("close", "Close"))
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        setContentView(root)
        immersive()

        open(0)
        showBar()
        web.requestFocus()
    }

    private fun hostOk(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return allowed.any { h == it || h.endsWith(".$it") }
    }

    private fun open(i: Int) {
        at = i.coerceIn(0, urls.size - 1)
        srvBtn?.text = labels.getOrNull(at)?.takeIf { it.isNotBlank() } ?: "Server ${at + 1}"
        web.loadUrl(urls[at])
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun pill(text: String, strong: Boolean): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(7), dp(14), dp(7))
        isFocusable = true
        isClickable = true
        val bg = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(if (strong) 0xFFE50914.toInt() else 0x33FFFFFF)
        }
        val on = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(if (strong) 0xFFFF2A35.toInt() else 0x66FFFFFF)
            setStroke(dp(2), Color.WHITE)
        }
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), on)
            addState(intArrayOf(android.R.attr.state_pressed), on)
            addState(intArrayOf(), bg)
        }
    }

    private fun makeBar(title: String, close: String): LinearLayout {
        val b = LinearLayout(this)
        b.orientation = LinearLayout.HORIZONTAL
        b.gravity = Gravity.CENTER_VERTICAL
        b.setPadding(dp(16), dp(12), dp(16), dp(18))
        b.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x00000000))
        val name = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        b.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (urls.size > 1) {
            val s = pill("", false)
            s.setOnClickListener { open((at + 1) % urls.size); showBar() }
            srvBtn = s
            b.addView(s, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        }
        val c = pill(close, true)
        c.setOnClickListener { finish() }
        closeBtn = c
        b.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        return b
    }

    private fun showBar() {
        ui.removeCallbacks(fade)
        if (bar.visibility != View.VISIBLE) { bar.alpha = 0f; bar.visibility = View.VISIBLE }
        bar.animate().alpha(1f).setDuration(180).start()
        ui.postDelayed(fade, 3500)
    }

    private fun hideBar() {
        ui.removeCallbacks(fade)
        if (bar.hasFocus()) web.requestFocus()
        bar.animate().alpha(0f).setDuration(250).withEndAction { bar.visibility = View.GONE }.start()
    }

    private fun leaveCustom() {
        val v = custom ?: return
        root.removeView(v)
        custom = null
        customCb?.onCustomViewHidden()
        customCb = null
        immersive()
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::web.isInitialized) immersive()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) showBar()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = event.keyCode
        /* Menu / Info on a remote: the bar, with the focus on its first button */
        if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_INFO || k == KeyEvent.KEYCODE_GUIDE) {
            if (event.action == KeyEvent.ACTION_UP) {
                showBar()
                (srvBtn ?: closeBtn)?.requestFocus()
            }
            return true
        }
        if (k == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                when {
                    custom != null -> leaveCustom()
                    bar.visibility == View.VISIBLE && bar.hasFocus() -> hideBar()
                    else -> finish()
                }
            }
            return true
        }
        if (bar.visibility == View.VISIBLE && bar.hasFocus()) { ui.removeCallbacks(fade); ui.postDelayed(fade, 3500) }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        if (::web.isInitialized) web.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (!::web.isInitialized) return              /* closed itself: nothing to play */
        web.onResume()
        immersive()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        if (::web.isInitialized) {
            try {
                web.stopLoading()
                web.loadUrl("about:blank")
                (web.parent as? ViewGroup)?.removeView(web)
                web.destroy()
            } catch (e: Exception) { }
        }
        super.onDestroy()
    }

    companion object {
        const val SPEC = "spec"
    }
}
