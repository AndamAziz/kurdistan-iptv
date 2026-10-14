package com.kurdistan.iptv

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/**
 * The OpenSubtitles "movie hash": the file's size plus every 64-bit little-endian
 * word of its first and its last 64 KB, added up. A subtitle made for exactly this
 * release is found by it, so its words land on the voice with no shifting.
 */
object OsHash {
    const val CHUNK = 65536

    /** size: the whole file's length in bytes; head / tail: its first and last [CHUNK] bytes */
    fun of(size: Long, head: ByteArray, tail: ByteArray): String {
        var h = size
        h += sum(head)
        h += sum(tail)
        return String.format("%016x", h)
    }

    private fun sum(b: ByteArray): Long {
        var s = 0L
        var i = 0
        while (i + 8 <= b.size) {
            var w = 0L
            for (k in 7 downTo 0) w = (w shl 8) or (b[i + k].toLong() and 0xFF)
            s += w
            i += 8
        }
        return s
    }

    /** the hash of a stream read with two small ranged requests, or null when it cannot be read that way */
    fun compute(url: String, ua: String, referer: String?): String? {
        return try {
            val first = ranged(url, ua, referer, 0L, CHUNK - 1L) ?: return null
            val size = first.second ?: return null          /* the server must say how long the file is */
            if (size < CHUNK * 2L || first.first.size < CHUNK) return null
            val last = ranged(url, ua, referer, size - CHUNK, size - 1) ?: return null
            if (last.first.size < CHUNK) return null
            of(size, first.first.copyOf(CHUNK), last.first.copyOf(CHUNK))
        } catch (e: Exception) { null }
    }

    /** bytes [from, to] of [url] and the whole file's size (from Content-Range); null on any refusal */
    private fun ranged(url: String, ua: String, referer: String?, from: Long, to: Long): Pair<ByteArray, Long?>? {
        var u = url
        for (hop in 0..5) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", ua)
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Range", "bytes=$from-$to")
            if (!referer.isNullOrBlank()) c.setRequestProperty("Referer", referer)
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val next = c.getHeaderField("Location") ?: return null
                    u = URL(URL(u), next).toString()
                    continue
                }
                if (code != 206) return null           /* 200 would be the whole file: not read */
                val total = Regex("/(\\d+)\\s*$").find(c.getHeaderField("Content-Range") ?: "")?.groupValues?.get(1)?.toLongOrNull()
                return Pair(readUpTo(c.inputStream, (to - from + 1).toInt()), total)
            } finally {
                c.disconnect()
            }
        }
        return null
    }

    private fun readUpTo(s: InputStream, n: Int): ByteArray {
        val out = ByteArrayOutputStream(n)
        val buf = ByteArray(8192)
        while (out.size() < n) {
            val r = s.read(buf, 0, minOf(buf.size, n - out.size()))
            if (r < 0) break
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }
}

/** What the timing tools need to know about a subtitle file. */
object SubSync {
    private val TIME = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})")

    /** when each cue of an SRT starts, in ms, in order */
    fun cueStarts(srt: String): LongArray {
        val out = ArrayList<Long>()
        for (line in srt.lineSequence()) {
            if (!line.contains("-->")) continue
            val m = TIME.find(line) ?: continue
            val (h, mi, se, fr) = m.destructured
            out.add(h.toLong() * 3600000 + mi.toLong() * 60000 + se.toLong() * 1000 + fr.padEnd(3, '0').toLong())
        }
        return out.toLongArray()
    }

    /**
     * "A line has just started being spoken": the new timing shift that makes the cue
     * nearest to now (with the present [shift]) start exactly at [pos]. Null when no
     * cue is within [window] ms - the file is too far off to tell which line is meant.
     */
    fun shiftToNow(starts: LongArray, shift: Long, pos: Long, window: Long = 45000L): Long? {
        var best = -1
        var bd = Long.MAX_VALUE
        for (i in starts.indices) {
            val d = abs(starts[i] + shift - pos)
            if (d < bd) { bd = d; best = i }
        }
        if (best < 0 || bd > window) return null
        return (pos - starts[best]).coerceIn(-600000L, 600000L)
    }
}
