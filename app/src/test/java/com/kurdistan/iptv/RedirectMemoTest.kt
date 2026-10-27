package com.kurdistan.iptv

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.Clock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.test.utils.FakeAudioRenderer
import androidx.media3.test.utils.FakeVideoRenderer
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * A film from an Xtream panel, played by the real player: the panel answers
 * every request with a redirect to the server that has the file, and the file
 * is an mp4 whose index is at the end (read at the start, the end, the start).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RedirectMemoTest {
    private fun res(n: String) = javaClass.classLoader!!.getResourceAsStream(n)?.readBytes()
    @Volatile private var film = res("moov_at_end.mp4")!!
    @Volatile private var filmType = "video/mp4"
    private val server = MockWebServer()
    private val panel = AtomicInteger()
    private val file = AtomicInteger()
    private val refused = AtomicInteger()
    /** a token the file server takes only once, as some panels hand out */
    @Volatile private var singleUse = false
    /** an address used again answers 200 with a web page, as an expired link on some servers */
    @Volatile private var pageWhenReused = false
    private val used = HashSet<String>()

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                if (path.startsWith("/movie/")) {
                    val n = panel.incrementAndGet()
                    return MockResponse().setResponseCode(302)
                        .setHeader("Location", "/lb/1.mp4?token=t$n")
                        .setBodyDelay(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .setHeadersDelay(PANEL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
                if (path.startsWith("/lb/")) {
                    synchronized(used) {
                        if ((singleUse || pageWhenReused) && !used.add(path)) {
                            refused.incrementAndGet()
                            return if (singleUse) MockResponse().setResponseCode(403)
                            else MockResponse().setHeader("Content-Type", "text/html; charset=utf-8")
                                .setBody("<html><body>Link expired</body></html>")
                        }
                    }
                    file.incrementAndGet()
                    return ranged(request.getHeader("Range"))
                }
                return MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun stop() { server.shutdown() }

    private fun ranged(range: String?): MockResponse {
        val m = Regex("bytes=(\\d+)-(\\d*)").find(range ?: "")
            ?: return MockResponse().setBody(Buffer().write(film)).setHeader("Accept-Ranges", "bytes")
                .setHeader("Content-Type", filmType)
        val from = m.groupValues[1].toInt()
        val to = m.groupValues[2].toIntOrNull()?.coerceAtMost(film.size - 1) ?: (film.size - 1)
        return MockResponse().setResponseCode(206)
            .setHeader("Accept-Ranges", "bytes")
            .setHeader("Content-Range", "bytes $from-$to/${film.size}")
            .setHeader("Content-Type", filmType)
            .setBody(Buffer().write(film, from, to - from + 1))
    }

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun play(http: DataSource.Factory, seekMs: Long = -1): Long {
        val renderers = RenderersFactory { h, v, a, _, _ ->
            val w = Clock.DEFAULT.createHandler(h.looper, null)
            arrayOf<Renderer>(FakeVideoRenderer(w, v), FakeAudioRenderer(w, a))
        }
        val p = TestExoPlayerBuilder(ctx)
            .setRenderersFactory(renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http)))
            .build()
        try {
            var error: Exception? = null
            p.addListener(object : Player.Listener {
                override fun onPlayerError(e: androidx.media3.common.PlaybackException) { error = e }
            })
            val t0 = System.nanoTime()
            p.setMediaItem(MediaItem.fromUri(server.url("/movie/user/pass/1.$ext").toString()))
            p.prepare()
            try { TestPlayerRunHelper.run(p).untilState(Player.STATE_READY) } catch (e: Exception) {
                println("DEBUG state=${p.playbackState} buffered=${p.bufferedPosition} panel=$panel file=$file err=${p.playerError} reqs=${server.requestCount}")
                throw e
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (seekMs >= 0) {
                p.seekTo(seekMs)
                TestPlayerRunHelper.run(p).untilPendingCommandsAreFullyHandled()
                TestPlayerRunHelper.run(p).untilState(Player.STATE_READY)
            }
            assertNull("no playback error", error)
            return ms
        } finally {
            p.release()
        }
    }

    /** what the player asked with before: a plain OkHttp data source */
    private fun before(): DataSource.Factory = OkHttpDataSource.Factory(OkHttpClient())

    @Volatile private var ext = "mp4"

    private fun now(way: Way? = null): DataSource.Factory =
        StreamNet(ctx).also { n -> if (way != null) n.way = way }.dataSourceFactory()

    @Test fun panelIsAskedOnceInsteadOfEveryOpen() {
        val oldMs = play(before())
        val oldPanel = panel.getAndSet(0)
        file.set(0)
        val newMs = play(now())
        val newPanel = panel.get()
        println("before: $oldPanel panel trips, ${oldMs} ms to the first picture")
        println("now:    $newPanel panel trips, ${newMs} ms to the first picture")
        assertTrue("an index at the end means several opens ($oldPanel)", oldPanel >= 3)
        assertEquals(1, newPanel)
        assertTrue("faster ($oldMs -> $newMs ms)", newMs < oldMs)
    }

    @Test fun aSeekGoesStraightToTheFileServer() {
        play(now(), seekMs = 8_000)
        assertEquals(1, panel.get())
    }

    @Test fun aTokenThatWorksOnlyOnceStillPlays() {
        singleUse = true
        val t0 = System.nanoTime()
        play(now(), seekMs = 8_000)
        println("single-use token: ${(System.nanoTime() - t0) / 1_000_000} ms, panel=$panel refused=$refused")
        assertTrue("the refused address was seen", refused.get() >= 1)
        /* once refused, this film is asked through the panel as before: one
           wasted request in all, never one per open */
        assertEquals(1, refused.get())
    }

    @Test fun anExpiredLinkThatAnswersWithAPageStillPlays() {
        pageWhenReused = true
        play(now(), seekMs = 8_000)
        assertEquals("the page was met once, then the panel as before", 1, refused.get())
    }

    @Test fun androidsOwnHttpCodeToo() {
        /* the way that sends what VLC sends through Android's own HTTP code */
        play(now(Way(StreamNet.VLC_UA, sys = true, range = true)), seekMs = 8_000)
        println("android http: panel=$panel file=$file")
        assertTrue("never more trips than before", panel.get() <= 4)
    }

    /** other files, when they are next to the test (made by ffmpeg, not kept in git) */
    @Test fun otherContainers() {
        for ((n, type) in listOf("faststart.mp4" to "video/mp4", "film.mkv" to "video/x-matroska",
                                 "film.webm" to "video/webm")) {
            val b = res(n) ?: continue
            film = b; filmType = type; ext = n.substringAfterLast('.')
            panel.set(0); file.set(0)
            val ms = play(now(), seekMs = 6_000)
            println("$n: panel=$panel file=$file first picture ${ms} ms")
            assertEquals(1, panel.get())
        }
    }

    @Test fun liveStreamsAreNeverRemembered() {
        val live = android.net.Uri.parse("http://host:8080/live/u/p/123.ts")
        val hls = android.net.Uri.parse("http://host:8080/live/u/p/123.m3u8")
        val movie = android.net.Uri.parse("http://host:8080/movie/u/p/77.mkv")
        val episode = android.net.Uri.parse("http://host/series/u/p/5.mp4?x=1")
        assertTrue(!RedirectMemo.isFile(live) && !RedirectMemo.isFile(hls))
        assertTrue(RedirectMemo.isFile(movie) && RedirectMemo.isFile(episode))
    }

    companion object { const val PANEL_MS = 400L }
}
