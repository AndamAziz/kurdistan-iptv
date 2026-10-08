package com.kurdistan.iptv

import okhttp3.OkHttpClient

/** The Google Play build always checks certificates (see app/src/direct). */
object Lenient {
    fun apply(b: OkHttpClient.Builder): Boolean = false
}
