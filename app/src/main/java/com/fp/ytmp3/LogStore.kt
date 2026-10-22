package com.fp.ytmp3

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Journal partage entre l'ecran principal et l'ecran Journal.
 * Conserve les 5000 dernieres lignes en memoire et 5 Mo sur disque
 * (job.log + job.1.log ... job.4.log, 1 Mo par fichier).
 */
object LogStore {

    private const val MAX_LINES = 5000
    private const val MAX_FILE_BYTES = 1_000_000L
    private const val MAX_FILES = 5
    private const val CURRENT = "job.log"

    private val lines = ArrayList<String>()
    private var appCtx: Context? = null
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "yt-mp3-log").apply { isDaemon = true }
    }
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** A appeler au demarrage de l'activite, avant tout log. */
    @Synchronized
    fun init(ctx: Context) {
        if (appCtx != null) return
        appCtx = ctx.applicationContext
        load()
    }

    @Synchronized
    fun add(s: String) {
        val line = synchronized(time) { time.format(Date()) } + "  " + s
        lines.add(line)
        if (lines.size > MAX_LINES) lines.removeAt(0)
        Log.d("yt-mp3", line)
        val ctx = appCtx ?: return
        io.execute { append(ctx, line) }
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        val ctx = appCtx ?: return
        io.execute {
            for (i in 0 until MAX_FILES) File(ctx.filesDir, name(i)).delete()
        }
    }

    private fun name(index: Int) = if (index == 0) CURRENT else "job.$index.log"

    private fun append(ctx: Context, line: String) {
        try {
            val dir = ctx.filesDir
            val current = File(dir, CURRENT)
            val bytes = line.toByteArray(Charsets.UTF_8).size + 1
            if (current.length() + bytes > MAX_FILE_BYTES) rotate(dir)
            current.appendText(line + "\n")
        } catch (e: Exception) {
            Log.w("yt-mp3", "log write failed", e)
        }
    }

    /** job.log -> job.1.log -> ... -> job.4.log (le plus ancien est supprime). */
    internal fun rotate(dir: File) {
        File(dir, name(MAX_FILES - 1)).delete()
        for (i in MAX_FILES - 2 downTo 1) {
            val from = File(dir, name(i))
            if (from.exists()) from.renameTo(File(dir, name(i + 1)))
        }
        val current = File(dir, CURRENT)
        if (current.exists() && !current.renameTo(File(dir, name(1)))) current.delete()
    }

    private fun load() {
        val ctx = appCtx ?: return
        try {
            // Seuls les deux fichiers les plus recents sont relus (2 Mo max) :
            // le reste des 5 Mo reste disponible sur disque.
            val tail = ArrayDeque<String>()
            for (i in 1 downTo 0) {
                val f = File(ctx.filesDir, name(i))
                if (!f.exists()) continue
                f.bufferedReader().useLines { seq ->
                    seq.forEach { line ->
                        if (line.isNotBlank()) {
                            tail.addLast(line)
                            if (tail.size > MAX_LINES) tail.removeFirst()
                        }
                    }
                }
            }
            lines.clear()
            lines.addAll(tail)
        } catch (e: Exception) {
            Log.w("yt-mp3", "log load failed", e)
        }
    }
}
