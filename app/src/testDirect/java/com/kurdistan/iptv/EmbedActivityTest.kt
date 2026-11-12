package com.kurdistan.iptv

import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Movies Online's web player (GitHub build only): it opens the first server,
 * keeps its main page on the player's own sites, refuses pop-ups and other
 * schemes, and the page is told the player is here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmbedActivityTest {
    private fun spec(vararg urls: String) = JSONObject()
        .put("title", "Model Citizen")
        .put("urls", JSONArray(urls.toList()))
        .put("labels", JSONArray(urls.indices.map { "Server ${it + 1}" }))
        .put("close", "Close").toString()

    private fun req(url: String, main: Boolean = true) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private fun webOf(a: EmbedActivity): WebView =
        EmbedActivity::class.java.getDeclaredField("web").apply { isAccessible = true }.get(a) as WebView

    @Test fun opensFirstServerAndStaysOnThePlayer() {
        val i = Intent().setClassName("com.kurdistan.iptv", "com.kurdistan.iptv.EmbedActivity")
            .putExtra(EmbedActivity.SPEC, spec("https://vaplayer.ru/embed/movie/1792170", "https://vidapi.site/embed/movie/1792170"))
        val a = Robolectric.buildActivity(EmbedActivity::class.java, i).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val w = webOf(a)
        assertEquals("https://vaplayer.ru/embed/movie/1792170", shadowOf(w).lastLoadedUrl)

        val c = shadowOf(w).webViewClient
        assertFalse("its own page", c.shouldOverrideUrlLoading(w, req("https://vaplayer.ru/asb.html?_=1")))
        assertFalse("the second server", c.shouldOverrideUrlLoading(w, req("https://vidapi.site/embed/movie/1")))
        assertFalse("the vidsrc.sh server", c.shouldOverrideUrlLoading(w, req("https://vidsrc.sh/embed/movie/621")))
        assertFalse("a frame inside the player", c.shouldOverrideUrlLoading(w, req("https://nextgencloudfabric.com/embed/movie/1", main = false)))
        assertTrue("an advert taking the page", c.shouldOverrideUrlLoading(w, req("https://ads.example.com/landing")))
        assertTrue("another app", c.shouldOverrideUrlLoading(w, req("intent://x#Intent;end")))
        assertTrue("a store", c.shouldOverrideUrlLoading(w, req("market://details?id=x")))

        val chrome = shadowOf(w).webChromeClient!!
        assertFalse("a pop-up is refused", chrome.onCreateWindow(w, false, true, android.os.Message.obtain()))
        assertTrue(w.settings.javaScriptEnabled)
        assertFalse("not marked as an app's web view", w.settings.userAgentString.contains("; wv"))
    }

    @Test fun nothingToPlayClosesAtOnce() {
        val i = Intent().setClassName("com.kurdistan.iptv", "com.kurdistan.iptv.EmbedActivity")
            .putExtra(EmbedActivity.SPEC, "{\"urls\":[\"javascript:alert(1)\"]}")
        val a = Robolectric.buildActivity(EmbedActivity::class.java, i).setup().get()
        assertTrue(a.isFinishing)
    }

    @Test fun thePageIsToldThePlayerIsHere() {
        val m = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertTrue(m.AndroidBridge(m).embedOk())
    }
}
