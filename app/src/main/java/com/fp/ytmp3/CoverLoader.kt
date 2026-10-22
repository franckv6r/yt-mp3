package com.fp.ytmp3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.Collections
import java.util.concurrent.Executors

/** Charge les pochettes ID3 des MP3 locaux, avec cache memoire. */
object CoverLoader {

    private val cache = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val io = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "yt-mp3-cover").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val missing: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val loading: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun load(ctx: Context, uri: Uri, onResult: (Bitmap?) -> Unit) {
        val key = uri.toString()
        cache.get(key)?.let { onResult(it); return }
        if (missing.contains(key)) {
            onResult(null)
            return
        }
        if (!loading.add(key)) return
        val app = ctx.applicationContext
        io.execute {
            val bitmap = try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(app, uri)
                    retriever.embeddedPicture?.let { decode(it) }
                } finally {
                    retriever.release()
                }
            } catch (_: Exception) {
                null
            } finally {
                loading.remove(key)
            }
            if (bitmap != null) cache.put(key, bitmap) else missing.add(key)
            main.post { onResult(bitmap) }
        }
    }

    fun evict(uri: Uri) {
        val key = uri.toString()
        cache.remove(key)
        missing.remove(key)
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
