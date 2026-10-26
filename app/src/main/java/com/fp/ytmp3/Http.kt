package com.fp.ytmp3

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Http {

    fun bytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectMs: Int = 20000,
        readMs: Int = 60000
    ): ByteArray =
        open(url, headers, connectMs, readMs).let { c ->
            try {
                val code = c.responseCode
                val input = if (code >= 400) c.errorStream else c.inputStream
                val data = input?.readBytes() ?: ByteArray(0)
                if (code >= 400) throw IOException("HTTP $code: ${String(data).take(200)}")
                data
            } finally {
                c.disconnect()
            }
        }

    fun text(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectMs: Int = 20000,
        readMs: Int = 60000
    ): String = String(bytes(url, headers, connectMs, readMs), Charsets.UTF_8)

    /**
     * Telechargement avec reprise. `append=true` demande a YouTube la suite
     * du fichier ; si le serveur renvoie 200, on repart de zero.
     */
    fun download(
        url: String,
        dest: File,
        append: Boolean = false,
        onProgress: (Long) -> Unit = {}
    ) {
        val existing = if (append && dest.exists()) dest.length() else 0L
        val headers = linkedMapOf("Accept-Encoding" to "identity")
        if (existing > 0) headers["Range"] = "bytes=$existing-"
        val c = open(url, headers)
        try {
            val code = c.responseCode
            if (code >= 400) throw IOException("HTTP $code")
            val resumed = code == 206 && existing > 0
            val base = if (resumed) existing else 0L
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                FileOutputStreamCompat(dest, resumed).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) {
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            onProgress((base + done) * 100 / (base + total))
                        } else {
                            onProgress(-1)
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun FileOutputStreamCompat(file: File, append: Boolean) =
        java.io.FileOutputStream(file, append)

    private fun open(
        url: String,
        headers: Map<String, String>,
        connectMs: Int = 20000,
        readMs: Int = 60000
    ): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = connectMs
        c.readTimeout = readMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "yt-mp3-poc/0.1")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        return c
    }
}
