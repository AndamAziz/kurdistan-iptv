package com.kurdistan.iptv

import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The Google Play build never accepts just any certificate (app/src/direct
 * does, for video streams). What it does instead, for video streams and only
 * after the normal, checked way has already failed, is what a browser does
 * with a server whose certificate is not quite in order:
 *
 *  - the certificate in the middle is missing (an "incomplete chain"):
 *    Chrome, Windows and macOS fetch it from the address written in the
 *    server's own certificate; Android does not. This fetches it, and the
 *    completed chain then goes through Android's own full check.
 *  - the authority at the top is one this phone is too old to know: a
 *    newer phone knows it. The authorities Firefox trusts today (Mozilla's
 *    list, resources/kiptv/roots.pem) are known here too, for this check only.
 *  - the certificate has run out: VLC and the other IPTV players play the
 *    stream anyway. This accepts it only if everything else about it is
 *    right - signed, link by link, up to an authority Android trusts, and
 *    every link valid on the day the oldest one ran out.
 *
 * The name on the certificate is still checked (OkHttp's own check is left
 * in place), and a certificate someone signed for themselves is still refused.
 */
object Lenient {
    /** true when the builder now completes chains and forgives old certificates */
    fun apply(b: OkHttpClient.Builder): Boolean = try {
        val tm = Tolerant.shared
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), SecureRandom())
        b.sslSocketFactory(ctx.socketFactory, tm)
        true
    } catch (e: Exception) { false }
}

class Tolerant(
    private val base: X509TrustManager,
    private val extra: List<X509Certificate> = emptyList(),
    private val now: () -> Date = { Date() }
) : X509TrustManager {

    companion object {
        val shared: Tolerant by lazy {
            val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            f.init(null as KeyStore?)
            Tolerant(f.trustManagers.first { it is X509TrustManager } as X509TrustManager, bundled())
        }

        /** Mozilla's list of authorities, carried in the app */
        private fun bundled(): List<X509Certificate> = try {
            Tolerant::class.java.getResourceAsStream("/kiptv/roots.pem")?.use { s ->
                CertificateFactory.getInstance("X.509").generateCertificates(s)
                    .filterIsInstance<X509Certificate>()
            } ?: emptyList()
        } catch (e: Exception) { emptyList() }

        /** OID 1.3.6.1.5.5.7.48.2 (id-ad-caIssuers), DER-encoded */
        private val CA_ISSUERS = byteArrayOf(0x06, 0x08, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02)

        /** certificates already fetched, by address - each address is asked once */
        private val fetched = ConcurrentHashMap<String, List<X509Certificate>>()
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        base.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = base.acceptedIssuers

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        try {
            base.checkServerTrusted(chain, authType)
            return
        } catch (first: CertificateException) {
            val ok = try { forgive(chain, authType) } catch (e: Throwable) { false }
            if (!ok) throw first
        }
    }

    /** whether the chain is sound once its missing links are fetched, its authority known, or its age forgiven */
    private fun forgive(chain: Array<X509Certificate>, authType: String): Boolean {
        if (chain.isEmpty()) return false
        val path = pathOf(chain) ?: return false
        /* the middle was missing: Android's own check, on the completed chain */
        try { base.checkServerTrusted(path.certs.toTypedArray(), authType); return true } catch (e: CertificateException) { }
        val anchor = path.anchor ?: return false
        val leaf = path.certs[0]
        val eku = try { leaf.extendedKeyUsage } catch (e: Exception) { null }
        if (eku != null && "1.3.6.1.5.5.7.3.1" !in eku && "2.5.29.37.0" !in eku) return false
        /* every link good today - or, for one that has run out, every link
           good on the day the first of them ran out */
        val today = now()
        val fresh = path.certs.all { c -> try { c.checkValidity(today); true } catch (e: Exception) { false } }
        val day = if (fresh) today else Date(path.certs.minOf { it.notAfter.time } - 60_000)
        if (day.after(today)) return false                    /* not old - something else is wrong */
        return (path.certs + anchor).all { c -> try { c.checkValidity(day); true } catch (e: Exception) { false } }
    }

    private class Path(val certs: List<X509Certificate>, val anchor: X509Certificate?)

    /**
     * The server's certificate, then each one that signed the one before, up
     * to an authority this phone trusts: from what the server sent, or else
     * from the address in the certificate. Every link's signature is checked.
     */
    private fun pathOf(chain: Array<X509Certificate>): Path? {
        val roots = base.acceptedIssuers.toList() + extra
        val pool = chain.drop(1).toMutableList()
        val out = mutableListOf(chain[0])
        var cur = chain[0]
        for (depth in 0 until 5) {
            roots.firstOrNull { signs(it, cur) }?.let { return Path(out, it) }
            if (cur.subjectX500Principal == cur.issuerX500Principal) return Path(out, null)
            var next = pool.firstOrNull { it !in out && isCa(it) && signs(it, cur) }
            if (next == null) {
                val got = caIssuers(cur)?.let { fetch(it) } ?: emptyList()
                pool.addAll(got)
                /* one an authority this phone trusts has signed, if there is one */
                val fit = got.filter { it !in out && isCa(it) && signs(it, cur) }
                next = fit.firstOrNull { c -> roots.any { signs(it, c) } } ?: fit.firstOrNull()
            }
            if (next == null || next.subjectX500Principal == next.issuerX500Principal &&
                roots.none { it.publicKey == next.publicKey }) return Path(out, null)
            out.add(next)
            cur = next
        }
        return Path(out, null)
    }

    private fun isCa(c: X509Certificate): Boolean = c.basicConstraints >= 0

    private fun signs(issuer: X509Certificate, c: X509Certificate): Boolean =
        issuer.subjectX500Principal == c.issuerX500Principal &&
            try { c.verify(issuer.publicKey); true } catch (e: Exception) { false }

    /** the "CA issuers" address written in a certificate, if it has one */
    internal fun caIssuers(cert: X509Certificate): String? {
        val ext = cert.getExtensionValue("1.3.6.1.5.5.7.1.1") ?: return null
        var i = 0
        while (i <= ext.size - CA_ISSUERS.size - 2) {
            var hit = true
            for (k in CA_ISSUERS.indices) if (ext[i + k] != CA_ISSUERS[k]) { hit = false; break }
            if (hit) {
                var p = i + CA_ISSUERS.size
                if (ext[p] != 0x86.toByte()) { i++; continue }           /* [6] uniformResourceIdentifier */
                p++
                var len = ext[p].toInt() and 0xFF
                p++
                if (len == 0x81) { len = ext[p].toInt() and 0xFF; p++ }
                else if (len == 0x82) { len = ((ext[p].toInt() and 0xFF) shl 8) or (ext[p + 1].toInt() and 0xFF); p += 2 }
                if (p + len > ext.size) return null
                val uri = String(ext, p, len, Charsets.US_ASCII)
                if (uri.startsWith("http://", true) || uri.startsWith("https://", true)) return uri
            }
            i++
        }
        return null
    }

    private fun fetch(url: String): List<X509Certificate> {
        fetched[url]?.let { return it }
        val got = try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.instanceFollowRedirects = true
            val bytes = c.inputStream.use { it.readBytes() }
            c.disconnect()
            /* DER, PEM or a PKCS#7 bundle - the factory reads them all */
            CertificateFactory.getInstance("X.509")
                .generateCertificates(ByteArrayInputStream(bytes))
                .filterIsInstance<X509Certificate>()
        } catch (e: Exception) { emptyList() }
        if (got.isNotEmpty()) fetched[url] = got
        return got
    }
}
