package com.kurdistan.iptv

import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Google Play build has no web player, so Movies Online is never offered. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoEmbedTest {
    @Test fun noWebPlayerInThisBuild() {
        val m = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertFalse(m.AndroidBridge(m).embedOk())
    }
}
