package com.kurdistan.iptv

import androidx.activity.ComponentActivity

/**
 * The Amazon build is updated by the Amazon Appstore alone: it neither asks
 * Google Play (there is none on a Fire TV, and Amazon refuses its library)
 * nor installs an apk itself. The page hides its update row (STORE_ONLY).
 */
@Suppress("UNUSED_PARAMETER")
internal class StoreUpdate(activity: ComponentActivity, private val say: (String) -> Unit) {
    fun run(resumeOnly: Boolean = false) { if (!resumeOnly) say("noplay") }

    companion object {
        const val STORE_ONLY = true
    }
}
