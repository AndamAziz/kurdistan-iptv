package com.kurdistan.iptv

import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The TV remote inside the player: the channel keys, the arrows with nothing on
 * the picture, the number keys and Last / Recall, on the real activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteKeysTest {
    private lateinit var ctl: ActivityController<MainActivity>
    private val act get() = ctl.get()

    /* 30 channels in the list; nothing answers at these addresses, which does not
       matter here: only which channel is on is looked at */
    private val all = (1..30).map { "http://127.0.0.1:9/live/u/p/$it.ts" }

    private fun field(name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(act)

    private fun now(): String? = field("currentUrl") as String?
    private fun index(): Int = (field("player") as ExoPlayer).currentMediaItemIndex

    private fun idle(ms: Long = 0) {
        if (ms > 0) shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun press(code: Int) {
        val t = SystemClock.uptimeMillis()
        act.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        act.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        idle()
    }

    @Before fun open() {
        ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val bridge = act.AndroidBridge(act)
        val chans = JSONArray()
        all.forEachIndexed { i, u -> chans.put(JSONObject().put("c", "Kurdish").put("n", "Ch ${i + 1}").put("u", u)) }
        bridge.setChannels(chans.toString())
        /* the page plays a group of ten: channels 1-10 */
        val group = JSONArray()
        for (i in 0 until 10) group.put(JSONObject().put("n", "Ch ${i + 1}").put("u", all[i]))
        bridge.playList(group.toString(), 4)
        idle()
        assertEquals(all[4], now())
    }

    @Test fun channelKeysStepAndGoRound() {
        press(KeyEvent.KEYCODE_CHANNEL_UP)
        assertEquals(5, index())
        press(KeyEvent.KEYCODE_CHANNEL_DOWN); press(KeyEvent.KEYCODE_CHANNEL_DOWN)
        assertEquals(3, index())
        /* the arrows, with nothing over the picture, are channel keys too */
        act.findViewById<androidx.media3.ui.PlayerView>(R.id.playerView).hideController()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(4, index())
        repeat(5) { press(KeyEvent.KEYCODE_PAGE_DOWN) }
        assertEquals("a channel list goes round", 9, index())
    }

    @Test fun numbersOpenThatChannelOfTheWholeList() {
        press(KeyEvent.KEYCODE_2); press(KeyEvent.KEYCODE_5)       /* two digits: the list has 30 */
        idle(400)
        assertEquals(all[24], now())
        press(KeyEvent.KEYCODE_7)                                 /* one digit: waits, then opens */
        idle(1600)
        assertEquals(all[6], now())
        press(KeyEvent.KEYCODE_9); press(KeyEvent.KEYCODE_9)       /* no channel 99: stays */
        idle(400)
        assertEquals(all[6], now())
    }

    @Test fun recallGoesBackToTheChannelBefore() {
        press(KeyEvent.KEYCODE_CHANNEL_UP)                        /* 5 -> 6 */
        press(KeyEvent.KEYCODE_LAST_CHANNEL)
        assertEquals(all[4], now())
        press(KeyEvent.KEYCODE_LAST_CHANNEL)
        assertEquals(all[5], now())
    }
}
