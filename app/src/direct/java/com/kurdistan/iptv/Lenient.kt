package com.kurdistan.iptv

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Many IPTV panels serve https with a certificate that has run out or that
 * they signed themselves. VLC and the other IPTV players open those anyway;
 * this lets the player do the same - but only for video streams, only after
 * the normal, checked way has already failed, and never for updates or
 * anything else the app downloads.
 *
 * The Google Play build carries a version of this file that does nothing
 * (app/src/play), so that build always checks certificates.
 */
object Lenient {
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /** true when the builder now accepts any certificate */
    fun apply(b: OkHttpClient.Builder): Boolean = try {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(trustAll), SecureRandom())
        b.sslSocketFactory(ctx.socketFactory, trustAll).hostnameVerifier { _, _ -> true }
        true
    } catch (e: Exception) { false }
}
