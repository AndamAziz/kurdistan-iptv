package com.kurdistan.iptv

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Certificates checked the way a browser checks them.
 *
 * Many servers send their own certificate but leave out the one in the middle
 * that links it to a trusted authority (an "incomplete chain"). Chrome, Windows
 * and macOS go and fetch that missing piece from the address written in the
 * certificate itself (its "CA issuers" address) and carry on; Android does not,
 * so the very same film plays on a computer and fails on the phone.
 *
 * This does what the browser does: when Android's own check fails, it fetches
 * the missing certificate from that address and runs Android's own full check
 * again on the completed chain. Nothing is skipped - a certificate that is
 * expired, forged or for another name is still refused.
 */
class AiaTrust(private val base: X509TrustManager) : X509TrustManager {

    companion object {
        /** the phone's own trust store, completed with missing intermediates */
        val shared: AiaTrust by lazy {
            val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            f.init(null as KeyStore?)
            AiaTrust(f.trustManagers.first { it is X509TrustManager } as X509TrustManager)
        }

        /** OID 1.3.6.1.5.5.7.48.2 (id-ad-caIssuers), DER-encoded */
        private val CA_ISSUERS = byteArrayOf(0x06, 0x08, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02)

        /** intermediates already fetched, by address - each is asked for once */
        private val fetched = ConcurrentHashMap<String, List<X509Certificate>>()
    }

    val socketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(this@AiaTrust), null) }.socketFactory
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        base.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = base.acceptedIssuers

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        try {
            base.checkServerTrusted(chain, authType)
        } catch (first: CertificateException) {
            /* only a chain that is missing its middle can be helped */
            val full = complete(chain, authType) ?: throw first
            base.checkServerTrusted(full, authType)
        }
    }

    /** the chain with its missing intermediates fetched, or null if none could be */
    private fun complete(chain: Array<X509Certificate>, authType: String): Array<X509Certificate>? {
        if (chain.isEmpty()) return null
        val out = chain.toMutableList()
        var last = out.last()
        for (depth in 0 until 3) {
            if (last.issuerX500Principal == last.subjectX500Principal) break   /* a root: nothing above */
            val url = caIssuers(last) ?: break
            val issuer = fetch(url).firstOrNull { it.subjectX500Principal == last.issuerX500Principal } ?: break
            try { last.verify(issuer.publicKey) } catch (e: Exception) { break }  /* it must really sign it */
            out.add(issuer)
            last = issuer
            val arr = out.toTypedArray()
            try { base.checkServerTrusted(arr, authType); return arr } catch (e: CertificateException) { }
        }
        return if (out.size > chain.size) out.toTypedArray() else null
    }

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
            c.connectTimeout = 6000
            c.readTimeout = 6000
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
