package com.kurdistan.iptv

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * A MAC Portal item, played by the real player: its address is the portal's
 * create_link request (kiptv_stk=1). The portal wants the MAC cookie and a
 * token from a handshake, and answers with the film's real address, which the
 * player then opens. A token the portal has forgotten is replaced once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StalkerTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private val handshakes = AtomicInteger()
    private val links = AtomicInteger()
    @Volatile private var token = "tok1"
    @Volatile private var forgetFirst = false

    @Before fun up() {
        val film = Buffer().readFrom(javaClass.classLoader!!.getResourceAsStream("moov_at_end.mp4"))
        val bytes = film.readByteArray()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val u = request.requestUrl!!
                if (u.encodedPath == "/film.mp4") {
                    return MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(bytes))
                }
                val cookieOk = (request.getHeader("Cookie") ?: "").contains("mac=00%3A1A%3A79%3A00%3A00%3A01")
                if (!cookieOk) return js("""{"error":"Unknown MAC"}""")
                return when (u.queryParameter("action")) {
                    "handshake" -> { handshakes.incrementAndGet(); js("""{"token":"$token"}""") }
                    "get_profile" -> js("""{"id":1}""")
                    "create_link" -> {
                        links.incrementAndGet()
                        val bearer = request.getHeader("Authorization")
                        if (forgetFirst) { forgetFirst = false; token = "tok2"; return js("""{"error":"Invalid or expired token"}""") }
                        if (bearer != "Bearer $token") js("""{"error":"Invalid or expired token"}""")
                        else js("""{"id":"1","cmd":"ffmpeg ${server.url("/film.mp4")}"}""")
                    }
                    else -> js("[]")
                }
            }
        }
        server.start()
    }

    @After fun down() { server.shutdown() }

    private fun js(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"js":$body}""")

    private fun link(cmd: String) = server.url("/portal.php").toString() +
        "?type=vod&action=create_link&cmd=" + cmd + "&JsHttpRequest=1-xml&mac=00%3A1A%3A79%3A00%3A00%3A01&kiptv_stk=1"

    private fun play(url: String): Int {
        val factory = DataSource.Factory { StalkerSource(DefaultHttpDataSource.Factory().createDataSource()) }
        val p = TestExoPlayerBuilder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build()
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        TestPlayerRunHelper.runUntilPlaybackState(p, Player.STATE_READY)
        val s = p.playbackState
        p.release()
        return s
    }

    @Test fun aPortalFilmPlays() {
        assertEquals(Player.STATE_READY, play(link("%2Fmedia%2F1.mp4")))
        assertEquals(1, handshakes.get())
        assertTrue(links.get() >= 1)
    }

    @Test fun anOldTokenIsReplacedOnce() {
        forgetFirst = true
        assertEquals(Player.STATE_READY, play(link("%2Fmedia%2F2.mp4")))
        assertTrue("a new handshake after the refusal", handshakes.get() >= 2)
    }

    @Test fun ordinaryAddressesAreUntouched() {
        assertEquals(Player.STATE_READY, play(server.url("/film.mp4").toString()))
        assertEquals(0, handshakes.get())
    }
}
