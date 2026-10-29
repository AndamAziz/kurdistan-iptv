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
    @Volatile private var tried = false
    @Volatile private var engine: CronetEngine? = null

    /* built once, the first time a way needs it, off the main thread */
    private fun engine(): CronetEngine? {
        if (tried) return engine
        synchronized(this) {
            if (!tried) {
                engine = try {
                    CronetUtil.buildCronetEngine(context.applicationContext, null, true)
                } catch (e: Throwable) { null }
                tried = true
            }
        }
        return engine
    }

    init {
        /* ask Play Services for its Cronet early, so it is ready when needed */
        try {
            com.google.android.gms.net.CronetProviderInstaller.installProvider(context.applicationContext)
        } catch (e: Throwable) { }
    }

    /** whether the Chrome ways can be offered on this device at all */
    val ok: Boolean get() = try {
        com.google.android.gms.common.GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == com.google.android.gms.common.ConnectionResult.SUCCESS
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
