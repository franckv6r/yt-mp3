package com.fp.ytmp3

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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

    /**
     * Telecharge en 6 morceaux paralleles quand le serveur accepte Range.
     * Retombe sur le telechargement simple sinon.
     */
    fun downloadParallel(
        url: String,
        dest: File,
        parts: Int = 6,
        shouldStop: () -> Boolean = { false },
        onProgress: (Long) -> Unit = {}
    ) {
        val headers = mapOf("Accept-Encoding" to "identity")
        val total = try {
            val head = open(url, headers).apply { requestMethod = "HEAD" }
            try {
                if (head.responseCode >= 400) 0L else head.contentLengthLong
            } finally {
                head.disconnect()
            }
        } catch (_: Exception) {
            0L
        }
        if (parts < 2 || total < 1_000_000L || shouldStop()) {
            download(url, dest, onProgress = onProgress)
            return
        }

        val chunk = (total + parts - 1) / parts
        dest.delete()
        val work = File(dest.parentFile, dest.name + ".parts").apply {
            deleteRecursively(); mkdirs()
        }
        val done = AtomicLong(0L)
        val failure = AtomicReference<Throwable?>()
        val pool = Executors.newFixedThreadPool(parts)
        val latch = CountDownLatch(parts)
        try {
            for (i in 0 until parts) {
                val start = i * chunk
                val end = minOf(total - 1, start + chunk - 1)
                if (start > end) break
                pool.execute {
                    val part = File(work, "part$i")
                    try {
                        if (shouldStop()) throw CancellationException("arrêt")
                        val c = open(
                            url,
                            headers + mapOf("Range" to "bytes=$start-$end")
                        )
                        try {
                            if (c.responseCode != 206) {
                                throw IOException("Range non supporte (HTTP ${c.responseCode})")
                            }
                            c.inputStream.use { input ->
                                part.outputStream().use { out ->
                                    val buf = ByteArray(64 * 1024)
                                    var n: Int
                                    while (input.read(buf).also { n = it } > 0) {
                                        if (shouldStop()) throw CancellationException("arrêt")
                                        out.write(buf, 0, n)
                                        val current = done.addAndGet(n.toLong())
                                        onProgress(current * 100 / total)
                                    }
                                }
                            }
                            val expected = end - start + 1
                            if (part.length() != expected) {
                                throw IOException("morceau incomplet (${part.length()}/$expected)")
                            }
                        } finally {
                            c.disconnect()
                        }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await()
        } finally {
            pool.shutdownNow()
        }
        val error = failure.get()
        if (error != null) {
            work.deleteRecursively()
            if (error is CancellationException) throw error
            throw if (error is IOException) error else IOException(error)
        }
        try {
            dest.outputStream().use { out ->
                for (i in 0 until parts) {
                    val part = File(work, "part$i")
                    if (!part.exists()) continue
                    part.inputStream().use { it.copyTo(out) }
                }
            }
            if (dest.length() != total) {
                throw IOException("taille finale ${dest.length()}/$total")
            }
            onProgress(100)
        } finally {
            work.deleteRecursively()
        }
    }

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
