package com.kurdistan.iptv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * MAC Portal (Stalker / Ministra) links.
 *
 * A portal does not give a channel's or a film's address in its lists: it gives
 * a "cmd", and the address is asked for with create_link at the moment it is
 * played (the answer often holds a one-time token). The page therefore hands
 * the player the create_link request itself, marked with kiptv_stk=1:
 *
 *   <portal>/portal.php?type=itv&action=create_link&cmd=...&mac=00:1A:79:..&kiptv_stk=1
 *
 * and this turns it into the real address when the player opens it - for the
 * first picture, for the next channel, after a reconnect or a seek. Everything
 * else in the player sees an ordinary http address and works as before.
 */
object Stalker {
    const val MARK = "kiptv_stk=1"
    private const val MAG_UA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 " +
        "(KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
    private const val TOKEN_MS = 10 * 60_000L
    private const val LINK_MS = 45_000L

    private class Tok(val token: String, val at: Long)
    private class Link(val url: String, val at: Long)

    private val tokens = ConcurrentHashMap<String, Tok>()
    private val links = ConcurrentHashMap<String, Link>()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun isLink(url: String?): Boolean = url != null && url.contains(MARK)

    /** the portal's own address and the MAC of a create_link request */
    private fun parts(link: String): Pair<String, String>? {
        val u = Uri.parse(link)
        val mac = u.getQueryParameter("mac") ?: return null
        val api = link.substringBefore('?')
        return Pair(api, mac)
    }

    private fun headers(b: Request.Builder, mac: String, token: String?): Request.Builder {
        b.header("User-Agent", MAG_UA)
        b.header("X-User-Agent", "Model: MAG250; Link: WiFi")
        b.header("Cookie", "mac=" + Uri.encode(mac) + "; stb_lang=en; timezone=UTC")
        if (token != null) b.header("Authorization", "Bearer $token")
        return b
    }

    private fun js(url: String, mac: String, token: String?): JSONObject {
        val req = headers(Request.Builder().url(url), mac, token).build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string() ?: ""
            if (!r.isSuccessful && r.code != 401) throw IOException("portal HTTP ${r.code}")
            val o = try { JSONObject(body) } catch (e: Exception) { throw IOException("portal: not JSON") }
            return o
        }
    }

    private fun token(api: String, mac: String, fresh: Boolean): String {
        val key = "$api|$mac"
        val have = tokens[key]
        if (!fresh && have != null && System.currentTimeMillis() - have.at < TOKEN_MS) return have.token
        val o = js("$api?type=stb&action=handshake&token=&JsHttpRequest=1-xml&mac=" + Uri.encode(mac), mac, null)
        val t = o.optJSONObject("js")?.optString("token").orEmpty()
        if (t.isBlank()) throw IOException("portal: " + (o.optJSONObject("js")?.optString("error") ?: "no token"))
        /* most portals want the profile asked for once before a token is good */
        try { js("$api?type=stb&action=get_profile&JsHttpRequest=1-xml&mac=" + Uri.encode(mac), mac, t) } catch (e: Exception) { }
        tokens[key] = Tok(t, System.currentTimeMillis())
        return t
    }

    /** "ffmpeg http://..." / "auto http://..." -> the address */
    private fun clean(cmd: String): String {
        var c = cmd.trim()
        val sp = c.indexOf(' ')
        if (sp > 0 && !c.substring(0, sp).contains("://")) c = c.substring(sp + 1).trim()
        return c
    }

    /** the address a create_link request stands for; asked again when [fresh] */
    fun resolve(link: String, fresh: Boolean = false): String {
        if (!fresh) links[link]?.let { if (System.currentTimeMillis() - it.at < LINK_MS) return it.url }
        val (api, mac) = parts(link) ?: throw IOException("portal: no MAC in link")
        val ask = link.replace("&" + MARK, "").replace("?" + MARK + "&", "?")
        var o = js(ask, mac, token(api, mac, fresh))
        var err = o.optJSONObject("js")?.optString("error").orEmpty()
        if (err.isNotBlank() || o.optJSONObject("js") == null) {
            /* an old token: one new handshake, then ask again */
            o = js(ask, mac, token(api, mac, true))
            err = o.optJSONObject("js")?.optString("error").orEmpty()
        }
        val cmd = o.optJSONObject("js")?.optString("cmd").orEmpty()
        val url = clean(cmd)
        if (!url.startsWith("http", true) && !url.startsWith("rtmp", true) && !url.startsWith("rtsp", true))
            throw IOException("portal: " + (err.ifBlank { "no address for this item" }))
        links[link] = Link(url, System.currentTimeMillis())
        return url
    }
}

/**
 * Opens a MAC Portal create_link request as the address it stands for; every
 * other address goes straight through. A kept address that no longer opens
 * (its token ran out) is asked for once more.
 */
class StalkerSource(private val upstream: DataSource) : DataSource {
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val raw = dataSpec.uri.toString()
        if (!Stalker.isLink(raw)) { opened = true; return upstream.open(dataSpec) }
        val first = Stalker.resolve(raw)
        return try {
            val n = upstream.open(dataSpec.withUri(Uri.parse(first)))
            opened = true
            n
        } catch (e: IOException) {
            try { upstream.close() } catch (x: Exception) { }
            val again = Stalker.resolve(raw, fresh = true)
            val n = upstream.open(dataSpec.withUri(Uri.parse(again)))
            opened = true
            n
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        if (opened) upstream.read(buffer, offset, length) else C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        opened = false
        upstream.close()
    }
}
