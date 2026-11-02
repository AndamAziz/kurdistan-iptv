package com.kurdistan.iptv

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A subtitle fetched while a film plays is added by replacing the playing item
 * with the same address plus the file. Media3 treats an item with the same
 * address as the same item and only updates it in place, without the new file,
 * unless something it does compare differs: the custom cache key that
 * MainActivity.mediaItem() derives from the subtitle files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubtitleReloadTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val film = "https://example.com/movie/u/p/1.mp4"

    private fun sub(path: String) = MediaItem.SubtitleConfiguration.Builder(Uri.parse("file://$path"))
        .setMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage("ckb").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()

    private fun withSubs(key: String?, vararg paths: String) = MediaItem.Builder().setUri(film)
        .setCustomCacheKey(key).setSubtitleConfigurations(paths.map { sub(it) }).build()

    @Test fun withoutTheKeyTheNewFileIsLostInPlace() {
        val playing = DefaultMediaSourceFactory(ctx).createMediaSource(MediaItem.fromUri(film))
        /* the old behaviour: the player would "update" the item and drop the subtitle */
        assertTrue(playing.canUpdateMediaItem(withSubs(null, "/c/s_ckb.srt")))
    }

    @Test fun theKeyMakesThePlayerBuildTheItemAnew() {
        val playing = DefaultMediaSourceFactory(ctx).createMediaSource(MediaItem.fromUri(film))
        assertFalse(playing.canUpdateMediaItem(withSubs("subs:a", "/c/s_ckb.srt")))
        /* a second language later, or a new timing: a new key, built anew again */
        val one = DefaultMediaSourceFactory(ctx).createMediaSource(withSubs("subs:a", "/c/s_ckb.srt"))
        assertFalse(one.canUpdateMediaItem(withSubs("subs:b", "/c/s_ckb.srt", "/c/s_en.srt")))
        /* nothing changed: still a cheap update in place */
        assertTrue(one.canUpdateMediaItem(withSubs("subs:a", "/c/s_ckb.srt")))
    }
}
