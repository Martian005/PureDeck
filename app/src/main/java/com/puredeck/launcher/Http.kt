package com.puredeck.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpStatusException(val code: Int) : IOException("TMDB returned HTTP $code")

/** Blocking helpers: call from Dispatchers.IO. */
internal object Http {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    fun bytes(url: String): ByteArray = open(url) { it.inputStream.use { stream -> stream.readBytes() } }

    fun text(url: String): String = open(url) { it.inputStream.bufferedReader().use { reader -> reader.readText() } }

    /** RGB_565 halves memory for opaque backdrops (matters on the 2 GB W800D); logos need alpha. */
    fun bitmap(url: String, config: Bitmap.Config): Bitmap? = open(url) { connection ->
        val options = BitmapFactory.Options().apply { inPreferredConfig = config }
        connection.inputStream.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun <T> open(url: String, block: (HttpURLConnection) -> T): T {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw HttpStatusException(connection.responseCode)
            block(connection)
        } finally {
            connection.disconnect()
        }
    }
}
