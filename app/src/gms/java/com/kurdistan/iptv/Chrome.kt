package com.kurdistan.iptv

import android.content.Context
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.datasource.cronet.CronetUtil
import org.chromium.net.CronetEngine
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Chrome's own network code (Cronet), from Google Play Services when the
 * device has them: one more way to ask a server that turns away Android's
 * TLS. The Google Play and GitHub builds carry this file (app/src/gms); the
 * Amazon build, made for Fire TV where there are no Play Services and where
 * Amazon refuses Google's libraries, carries one that has none (app/src/nogms).
 */
internal class Chrome(private val context: Context) {
    private val exec = Executors.newFixedThreadPool(4)
    @Volatile private var tries = 0
    @Volatile private var lastTry = 0L
    @Volatile private var engine: CronetEngine? = null

    /* built the first time a way needs it, off the main thread. Play Services' Cronet is now
       fetched when the first stream starts, so it may still be on its way then: a failed build
       is tried again a little later, three times at most */
    private fun engine(): CronetEngine? {
        engine?.let { return it }
        synchronized(this) {
            engine?.let { return it }
            val now = android.os.SystemClock.elapsedRealtime()
            if (tries >= 3 || (tries > 0 && now - lastTry < 5000L)) return null
            tries++
            lastTry = now
            engine = try {
                CronetUtil.buildCronetEngine(context.applicationContext, null, true)
            } catch (e: Throwable) { null }
            return engine
        }
    }

    /* Play Services is asked for its Cronet the first time something is played, not while the
       app opens: at start the page's WebView talks to Play Services too, and on a phone whose Play
       Services answer slowly the two together held the screen still ("app not responding") */
    @Volatile private var installAsked = false

    private fun askInstall() {
        if (installAsked) return
        installAsked = true
        try {
            com.google.android.gms.net.CronetProviderInstaller.installProvider(context.applicationContext)
        } catch (e: Throwable) { }
    }

    /** whether the Chrome ways can be offered on this device at all */
    val ok: Boolean get() = try {
        val yes = com.google.android.gms.common.GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == com.google.android.gms.common.ConnectionResult.SUCCESS
        if (yes) askInstall()
        yes
    } catch (e: Throwable) { false }

    /** the player's source through Chrome, or null when it cannot be built */
    fun factory(ua: String, headers: Map<String, String>): HttpDataSource.Factory? {
        val eng = engine() ?: return null
        return CronetDataSource.Factory(eng, exec)
            .setUserAgent(ua)
            .setConnectionTimeoutMs(10000)
            .setReadTimeoutMs(20000)
            .setHandleSetCookieRequests(true)
            .setDefaultRequestProperties(headers)
    }

    /** one request through Chrome, or null when it cannot be built */
    fun open(url: URL): HttpURLConnection? = engine()?.openConnection(url) as HttpURLConnection?
}
