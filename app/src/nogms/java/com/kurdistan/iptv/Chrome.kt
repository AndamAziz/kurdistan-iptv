package com.kurdistan.iptv

import android.content.Context
import androidx.media3.datasource.HttpDataSource
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Amazon build has no Chrome (Cronet): it comes from Google Play
 * Services, which a Fire TV does not have and whose libraries Amazon refuses.
 * The player simply never offers the Chrome ways - exactly what the other
 * builds already do on a device without Play Services (see app/src/gms).
 */
@Suppress("UNUSED_PARAMETER")
internal class Chrome(context: Context) {
    val ok: Boolean get() = false
    fun factory(ua: String, headers: Map<String, String>): HttpDataSource.Factory? = null
    fun open(url: URL): HttpURLConnection? = null
}
