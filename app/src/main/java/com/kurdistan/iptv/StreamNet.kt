package com.kurdistan.iptv

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.datasource.cronet.CronetUtil
import org.chromium.net.CronetEngine
import java.util.concurrent.Executors
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.EOFException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * One way of asking a server for a stream: which agent the request carries,
 * whether a certificate the phone does not trust is accepted, whether the
 * name is looked up over secure DNS, and whether the address itself is
 * changed (.ts <-> .m3u8 on an Xtream live link, or http <-> https).
 */
data class Way(
    val ua: String,
    val lenient: Boolean = false,
    val doh: Boolean = false,
    val swap: Int = 0,
    /** ask the way VLC and mpv do: HTTP/1.1, nothing compressed */
    val h1: Boolean = false,
    /** Android's own HTTP code instead of OkHttp - what most other IPTV apps
     *  play with - so a server that dislikes OkHttp itself still answers */
    val sys: Boolean = false,
    /** "Range: bytes=0-" on the first request, as VLC and FFmpeg always send */
    val range: Boolean = false,
    /** Chrome's own network code (Cronet): its TLS, its HTTP - a browser */
    val cr: Boolean = false
) {
    fun key(): String = "$ua|$lenient|$doh|$swap|$h1|$sys|$range|$cr"

    /** a few letters for the health check: which way got which answer */
    fun label(): String {
        val who = when {
            ua.startsWith("VLC") -> "VLC"; ua.startsWith("Mozilla") -> "Web"
            ua.startsWith("IPTVSmarters") -> "Smarters"; ua.startsWith("Lavf") -> "Lavf"
            else -> "UA"
        }
        return who + (if (cr) "+chrome" else "") + (if (sys) "+sys" else "") + (if (range) "+range" else "") + (if (h1) "+h1" else "") +
            (if (doh) "+dns" else "") + (if (lenient) "+cert" else "") +
            (if (swap == 1) "+ext" else if (swap == 2) "+scheme" else "")
    }

    companion object {
        fun parse(s: String?): Way? {
            if (s.isNullOrBlank()) return null
            val p = s.split('|')
            return try {
                fun b(x: String) = x == "true" || x == "false"
                /* written with every flag, with the HTTP/1.1 flag only, or by an
                   older build - each reads back */
                if (p.size >= 8 && p[p.size - 5].toIntOrNull() != null && b(p[p.size - 4]) && b(p[p.size - 3]) &&
                    b(p[p.size - 2]) && b(p.last()))
                    Way(p.dropLast(7).joinToString("|"), p[p.size - 7].toBoolean(), p[p.size - 6].toBoolean(),
                        p[p.size - 5].toInt(), p[p.size - 4].toBoolean(), p[p.size - 3].toBoolean(),
                        p[p.size - 2].toBoolean(), p.last().toBoolean())
                else if (p.size >= 7 && p[p.size - 4].toIntOrNull() != null && b(p[p.size - 3]) && b(p[p.size - 2]) && b(p.last()))
                    Way(p.dropLast(6).joinToString("|"), p[p.size - 6].toBoolean(), p[p.size - 5].toBoolean(),
                        p[p.size - 4].toInt(), p[p.size - 3].toBoolean(), p[p.size - 2].toBoolean(), p.last().toBoolean())
                else if (p.size >= 5 && p[p.size - 2].toIntOrNull() != null && b(p.last()))
                    Way(p.dropLast(4).joinToString("|"), p[p.size - 4].toBoolean(),
                        p[p.size - 3].toBoolean(), p[p.size - 2].toInt(), p.last().toBoolean())
                else if (p.size >= 4)
                    Way(p.dropLast(3).joinToString("|"), p[p.size - 3].toBoolean(),
                        p[p.size - 2].toBoolean(), p[p.size - 1].toInt())
                else null
            } catch (e: Exception) { null }
        }
    }
}

/** why a server would not hand a stream over - it decides what to try next */
enum class Why { DNS, CONNECT, SSL, REFUSED, DROPPED, OTHER }

/**
 * Everything about reaching a stream's server.
 *
 * A link that plays in VLC or a browser but not here was almost always one of
 * these: the server only answers some agents and drops the rest; its https
 * certificate has run out; the name is blocked by the internet provider's
 * DNS (a browser with secure DNS still gets through); or the panel has
 * switched off one of its two outputs (.ts or .m3u8). The player now tries
 * the next way that can help with the failure it actually saw, and remembers
 * per server which way worked, so the next channel there opens at once.
 */
class StreamNet(private val context: Context) {
    companion object {
        const val VLC_UA = "VLC/3.0.20 LibVLC/3.0.20"
        const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        const val SMARTERS_UA = "IPTVSmartersPlayer"
        const val LAVF_UA = "Lavf/60.16.100"

        /* every request carries what VLC, mpv and the Windows app send: some
           film servers answer a request without them with 400 Bad Request */
        val PLAYER_HEADERS = mapOf("Accept" to "*/*", "Accept-Language" to "en-US,en;q=0.9")

        const val SWAP_EXT = 1
        const val SWAP_SCHEME = 2

        private val LIVE_RX = Regex(
            "^(https?://[^/?#]+/(?:live/)?[^/?#]+/[^/?#]+/\\d+)(\\.(?:ts|m3u8))?(\\?.*)?$",
            RegexOption.IGNORE_CASE
        )
        private val IP_RX = Regex("^[0-9.]+$|:")
    }

    private val prefs = context.getSharedPreferences("kiptv_net", Context.MODE_PRIVATE)

    /* Chrome's network code, from Google Play Services when the device has
       them. Built once, the first time a way needs it, off the main thread. */
    private val crExec = Executors.newFixedThreadPool(4)
    @Volatile private var crTried = false
    @Volatile private var crEngine: CronetEngine? = null
    private fun cronet(): CronetEngine? {
        if (crTried) return crEngine
        synchronized(this) {
            if (!crTried) {
                crEngine = try {
                    CronetUtil.buildCronetEngine(context.applicationContext, null, true)
                } catch (e: Throwable) { null }
                crTried = true
            }
        }
        return crEngine
    }
    init {
        /* ask Play Services for its Cronet early, so it is ready when needed */
        try {
            com.google.android.gms.net.CronetProviderInstaller.installProvider(context.applicationContext)
        } catch (e: Throwable) { }
    }
    /** whether the Chrome ways can be offered on this device at all */
    val cronetOk: Boolean get() = try {
        com.google.android.gms.common.GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == com.google.android.gms.common.ConnectionResult.SUCCESS
    } catch (e: Throwable) { false }

    /** false in the Google Play build, which always checks certificates */
    val lenientOk: Boolean = Lenient.apply(OkHttpClient.Builder())

    /* Some panels set a session cookie on the first request and reject every
       segment that comes back without it. VLC keeps cookies; so does this. */
    private val jar = object : CookieJar {
        private val store = ConcurrentHashMap<String, ConcurrentHashMap<String, Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val m = store.getOrPut(url.host) { ConcurrentHashMap() }
            for (c in cookies) m[c.name] = c
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            return store[url.host]?.values?.filter { it.expiresAt > now && it.matches(url) } ?: emptyList()
        }
    }

    /* IPv4 first. A phone on a network whose IPv6 is half-working tries every
       IPv6 address of a server one after another before IPv4, and each one
       waits out the connect timeout - for every piece of a film. Servers on
       Cloudflare (r2.dev, most panels) all have IPv6; live channels' servers
       often do not, which is how films could stall while live played. */
    private val v4First = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
    }

    private val base: OkHttpClient = OkHttpClient.Builder()
        /* certificates checked as a browser checks them: a server that leaves
           out its middle certificate is completed, then fully checked */
        .sslSocketFactory(AiaTrust.shared.socketFactory, AiaTrust.shared)
        .dns(v4First)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .cookieJar(jar)
        .build()

    /* the name looked up over https (Cloudflare, then Google), the phone's
       own DNS only when both fail - an ISP that blocks a name by DNS cannot
       see or change these answers */
    private val smartDns: Dns by lazy {
        val boot = base.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        fun make(url: String, vararg ips: String): Dns? = try {
            DnsOverHttps.Builder().client(boot).url(url.toHttpUrl())
                .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) })
                .includeIPv6(false)
                .build()
        } catch (e: Exception) { null }
        val list = listOfNotNull(
            make("https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1"),
            make("https://dns.google/dns-query", "8.8.8.8", "8.8.4.4")
        )
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (IP_RX.containsMatchIn(hostname)) return Dns.SYSTEM.lookup(hostname)
                for (d in list) {
                    try {
                        val r = d.lookup(hostname)
                        if (r.isNotEmpty()) return r
                    } catch (e: Exception) { /* the next one */ }
                }
                return v4First.lookup(hostname)
            }
        }
    }

    private val clients = ConcurrentHashMap<String, OkHttpClient>()

    fun client(w: Way, follow: Boolean = true): OkHttpClient {
        val lenient = w.lenient && lenientOk
        return clients.getOrPut("$lenient|${w.doh}|${w.h1}|$follow") {
            val b = base.newBuilder()
            if (lenient) Lenient.apply(b)
            if (lenient || w.h1) b.protocols(listOf(Protocol.HTTP_1_1))
            if (w.doh) b.dns(smartDns)
            if (!follow) b.followRedirects(false).followSslRedirects(false)
            b.build()
        }
    }

    // ---------------- what the player reads its streams with ----------------

    /** the way the player is using right now; read each time it opens a connection */
    @Volatile var way: Way = Way(VLC_UA)
    @Volatile var referer: String? = null

    private val factories = ConcurrentHashMap<String, HttpDataSource.Factory>()

    fun dataSourceFactory(): DataSource.Factory = DataSource.Factory {
        val w = way
        val r = referer
        factories.getOrPut(w.key() + "|" + (r ?: "")) {
            val h = HashMap(PLAYER_HEADERS)
            if (r != null) h["Referer"] = r
            val eng = if (w.cr) cronet() else null
            if (eng != null) {
                CronetDataSource.Factory(eng, crExec)
                    .setUserAgent(w.ua)
                    .setConnectionTimeoutMs(10000)
                    .setReadTimeoutMs(20000)
                    .setHandleSetCookieRequests(true)
                    .setDefaultRequestProperties(h)
            } else if (w.sys || w.cr) {
                /* Android's own HTTP code. Its range header is set after these,
                   so a seek still asks for the right place */
                if (w.range) h["Range"] = "bytes=0-"
                DefaultHttpDataSource.Factory()
                    .setUserAgent(w.ua)
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(10000)
                    .setReadTimeoutMs(20000)
                    .setKeepPostFor302Redirects(true)
                    .setDefaultRequestProperties(h)
            } else {
                if (w.h1) h["Accept-Encoding"] = "identity"
                OkHttpDataSource.Factory(client(w)).setUserAgent(w.ua).setDefaultRequestProperties(h)
            }
        }.createDataSource()
    }

    // ---------------- the ways, in the order they are tried ----------------

    fun waysFor(url: String, ua: String?, vod: Boolean): List<Way> {
        val u0 = ua ?: VLC_UA
        val out = LinkedHashMap<String, Way>()
        val crOk = cronetOk
        fun add(w: Way) {
            if (w.cr && !crOk) return
            val e = if (w.lenient && !lenientOk) w.copy(lenient = false) else w
            if (e.swap == SWAP_EXT && (vod || !canSwapExt(url))) return
            out.putIfAbsent(e.key(), e)
        }
        /* The same order the Windows app uses. Secure DNS always comes before
           accepting a certificate the phone does not trust: a name the
           internet provider blocks often leads to its own block page, whose
           certificate is wrong - accepting it would hand the player a web
           page instead of a film. With the real address from secure DNS, an
           old certificate is then the only thing left to forgive. */
        remembered(url)?.let { add(it) }          /* what worked on this server last time */
        add(Way(u0))
        add(Way(BROWSER_UA))
        add(Way(BROWSER_UA, cr = true))           /* a browser, exactly: Chrome's own network code */
        add(Way(u0, cr = true))
        add(Way(u0, sys = true, range = true))    /* what VLC sends, through Android's own HTTP code */
        add(Way(u0, h1 = true))                   /* exactly what mpv on Windows sends */
        add(Way(BROWSER_UA, sys = true))          /* what an ordinary IPTV app sends */
        add(Way(u0, doh = true))
        add(Way(SMARTERS_UA))
        add(Way(BROWSER_UA, doh = true))
        add(Way(BROWSER_UA, h1 = true, doh = true))
        add(Way(u0, doh = true, swap = SWAP_EXT))
        add(Way(u0, lenient = true, doh = true))
        add(Way(BROWSER_UA, lenient = true, doh = true))
        add(Way(u0, doh = true, swap = SWAP_SCHEME))
        add(Way(LAVF_UA, lenient = true, doh = true))
        add(Way(u0, sys = true))
        add(Way(LAVF_UA, sys = true, range = true))
        return out.values.toList()
    }

    /** whether trying [w] can help with a failure of this kind */
    fun helps(w: Way, why: Why): Boolean = when (why) {
        Why.DNS -> w.doh
        Why.CONNECT -> w.doh || w.swap == SWAP_SCHEME || w.sys || w.cr
        Why.SSL -> w.doh || (w.lenient && lenientOk) || w.swap == SWAP_SCHEME || w.cr
        else -> true
    }

    fun linkFor(url: String, w: Way): String = when (w.swap) {
        SWAP_EXT -> swapExt(url) ?: url
        SWAP_SCHEME -> when {
            url.startsWith("http://", true) -> "https://" + url.substring(7)
            url.startsWith("https://", true) -> "http://" + url.substring(8)
            else -> url
        }
        else -> url
    }

    private fun canSwapExt(url: String): Boolean = swapExt(url) != null

    /** an Xtream live link in its other output: .../123.ts <-> .../123.m3u8 */
    private fun swapExt(url: String): String? {
        if (url.contains("/movie/", true) || url.contains("/series/", true)) return null
        val m = LIVE_RX.find(url) ?: return null
        val nx = if (m.groupValues[2].equals(".m3u8", true)) ".ts" else ".m3u8"
        return m.groupValues[1] + nx + m.groupValues[3]
    }

    // ---------------- remembering what worked ----------------

    private fun hostKey(url: String): String = try { "way:" + (URL(url).host ?: "") } catch (e: Exception) { "" }

    fun remembered(url: String): Way? {
        val k = hostKey(url)
        return if (k.length > 4) Way.parse(prefs.getString(k, null)) else null
    }

    fun remember(url: String, w: Way, plain: Boolean) {
        val k = hostKey(url)
        if (k.length <= 4) return
        try {
            if (plain) prefs.edit().remove(k).apply()
            else prefs.edit().putString(k, w.key()).apply()
        } catch (e: Exception) { }
    }

    // ---------------- reading a failure ----------------

    fun why(t: Throwable?): Why {
        var c = t
        var n = 0
        while (c != null && n++ < 10) {
            when (c) {
                is UnknownHostException -> return Why.DNS
                is SSLException, is CertificateException -> return Why.SSL
                is ConnectException, is NoRouteToHostException, is PortUnreachableException -> return Why.CONNECT
                is SocketTimeoutException ->
                    return if ((c.message ?: "").contains("connect", true)) Why.CONNECT else Why.DROPPED
                is HttpDataSource.InvalidResponseCodeException -> return Why.REFUSED
                is EOFException, is ProtocolException, is SocketException -> return Why.DROPPED
            }
            c = c.cause
        }
        return Why.OTHER
    }

    // ---------------- opening a stream by hand ----------------

    class Found(val url: String, val mime: String?, val kind: String, val way: Way, val ms: Long)
    private class Tried(val found: Found?, val why: Why, val code: Int)

    /** Follows every redirect itself and reads the first bytes that come back:
     *  where the stream really is, and what it really is. Then lets go. */
    private fun attempt(url: String, w: Way, ref: String?): Tried {
        if (w.sys || w.cr) return attemptSys(url, w, ref)
        val t0 = SystemClock.elapsedRealtime()
        var target = linkFor(url, w)
        val cl = client(w, follow = false)
        try {
            var hop = 0
            while (hop++ < 8) {
                val rb = Request.Builder().url(target)
                    .header("User-Agent", w.ua)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Connection", "close")
                if (w.h1) rb.header("Accept-Encoding", "identity")
                if (ref != null) rb.header("Referer", ref)
                val resp = cl.newCall(rb.build()).execute()
                val code = resp.code
                if (code in 300..399) {
                    val loc = resp.header("Location")
                    resp.close()
                    if (loc.isNullOrBlank()) return Tried(null, Why.REFUSED, code)
                    target = URL(URL(target), loc).toString()
                    continue
                }
                if (code !in 200..299) { resp.close(); return Tried(null, Why.REFUSED, code) }

                val ctype = (resp.header("Content-Type") ?: "").lowercase()
                val head = ByteArray(2048)
                var got = 0
                try {
                    val ins = resp.body?.byteStream()
                    if (ins != null) got = readHead(ins, head)
                } catch (e: Exception) { /* enough is enough */ }
                resp.close()
                return judge(target, w, code, ctype, head, got, SystemClock.elapsedRealtime() - t0)
            }
        } catch (e: Exception) {
            return Tried(null, why(e), 0)
        }
        return Tried(null, Why.REFUSED, 0)
    }

    /** the same, through Android's own HTTP code */
    private fun attemptSys(url: String, w: Way, ref: String?): Tried {
        val t0 = SystemClock.elapsedRealtime()
        var target = linkFor(url, w)
        try {
            var hop = 0
            while (hop++ < 8) {
                val eng = if (w.cr) cronet() else null
                if (w.cr && eng == null) return Tried(null, Why.OTHER, 0)
                val c = (if (eng != null) eng.openConnection(URL(target)) else URL(target).openConnection())
                    as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 10000
                c.readTimeout = 15000
                c.setRequestProperty("User-Agent", w.ua)
                for ((k, v) in PLAYER_HEADERS) c.setRequestProperty(k, v)
                if (w.range) c.setRequestProperty("Range", "bytes=0-")
                if (ref != null) c.setRequestProperty("Referer", ref)
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location")
                    c.disconnect()
                    if (loc.isNullOrBlank()) return Tried(null, Why.REFUSED, code)
                    target = URL(URL(target), loc).toString()
                    continue
                }
                if (code !in 200..299) { c.disconnect(); return Tried(null, Why.REFUSED, code) }
                val ctype = (c.contentType ?: "").lowercase()
                val head = ByteArray(2048)
                var got = 0
                try { c.inputStream.use { got = readHead(it, head) } } catch (e: Exception) { }
                c.disconnect()
                return judge(target, w, code, ctype, head, got, SystemClock.elapsedRealtime() - t0)
            }
        } catch (e: Exception) {
            return Tried(null, why(e), 0)
        }
        return Tried(null, Why.REFUSED, 0)
    }

    private fun readHead(ins: java.io.InputStream, head: ByteArray): Int {
        var got = 0
        while (got < head.size) {
            val r = ins.read(head, got, head.size - got)
            if (r <= 0) break
            got += r
        }
        return got
    }

    /** what the first bytes say the stream is - or that it is a page, not a stream */
    private fun judge(target: String, w: Way, code: Int, ctype: String, head: ByteArray, got: Int, ms: Long): Tried {
        val text = if (got > 0) String(head, 0, got, Charsets.ISO_8859_1) else ""
        val mime = when {
            text.startsWith("#EXTM3U") || text.contains("#EXT-X-") -> MimeTypes.APPLICATION_M3U8
            text.contains("<MPD") -> MimeTypes.APPLICATION_MPD
            got > 0 && head[0] == 0x47.toByte() -> MimeTypes.VIDEO_MP2T
            ctype.contains("mpegurl") -> MimeTypes.APPLICATION_M3U8
            ctype.contains("dash+xml") -> MimeTypes.APPLICATION_MPD
            ctype.contains("mp2t") -> MimeTypes.VIDEO_MP2T
            else -> null
        }
        val kind = when {
            mime == MimeTypes.APPLICATION_M3U8 -> "HLS"
            mime == MimeTypes.APPLICATION_MPD -> "DASH"
            mime == MimeTypes.VIDEO_MP2T -> "MPEG-TS"
            got > 8 && text.substring(4, 8) == "ftyp" -> "MP4"
            got > 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() -> "MKV"
            else -> ""
        }
        /* a refusal dressed as a page: html where a stream should be */
        if (got > 0 && (ctype.contains("text/html") || text.trimStart().startsWith("<!DOCTYPE", true) ||
                text.trimStart().startsWith("<html", true)) && mime == null)
            return Tried(null, Why.REFUSED, code)
        return Tried(Found(target, mime, kind, w, ms), Why.OTHER, code)
    }

    /**
     * Tries the ways one after another - only those that can help with the
     * failure just seen - until the stream answers. Returns what was found
     * (or null) and the last HTTP answer the server gave.
     */
    fun find(url: String, ua: String?, ref: String?, vod: Boolean, maxTries: Int): Pair<Found?, Int> {
        val r = search(url, ua, ref, vod, maxTries)
        return Pair(r.found, r.code)
    }

    /** what a search found, its last HTTP answer, and every way's answer in turn */
    class Search(val found: Found?, val code: Int, val trail: String)

    fun search(url: String, ua: String?, ref: String?, vod: Boolean, maxTries: Int): Search {
        val ways = waysFor(url, ua, vod)
        val plain = Way(ua ?: VLC_UA)
        val tried = HashSet<String>()
        val trail = ArrayList<String>()
        var cur = ways.firstOrNull() ?: return Search(null, 0, "")
        var lastCode = 0
        for (n in 0 until maxTries) {
            tried.add(cur.key())
            val r = attempt(url, cur, ref)
            trail.add(cur.label() + ":" + (if (r.found != null) "OK" else if (r.code > 0) r.code.toString() else r.why.name.lowercase()))
            if (r.found != null) {
                remember(url, cur, cur == plain)
                return Search(r.found, r.code, trail.joinToString(" "))
            }
            if (r.code > 0) lastCode = r.code
            cur = ways.firstOrNull { it.key() !in tried && helps(it, r.why) } ?: break
        }
        return Search(null, lastCode, trail.joinToString(" "))
    }
}
