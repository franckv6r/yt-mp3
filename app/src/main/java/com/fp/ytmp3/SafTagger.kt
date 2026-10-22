package com.fp.ytmp3

import android.content.Context
import android.net.Uri
import com.mpatric.mp3agic.ID3v2
import com.mpatric.mp3agic.ID3v24Tag
import com.mpatric.mp3agic.Mp3File
import java.io.File

/** Lecture/ecriture des tags d'un mp3 accessible par SAF (sans copie permanente). */
object SafTagger {

    data class Local(
        val title: String,
        val artist: String,
        val album: String?,
        val year: Int?,
        val duration: Long
    )

    fun read(ctx: Context, uri: Uri, name: String, cacheDir: File): Local? {
        val tmp = File(cacheDir, "saf_read.mp3")
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: return null
            val m = Mp3File(tmp)
            var title = ""
            var artist = ""
            var album: String? = null
            var year: Int? = null
            if (m.hasId3v2Tag()) {
                val t = m.id3v2Tag
                title = t.title ?: ""
                artist = t.artist ?: ""
                album = t.album
                year = t.year?.toIntOrNull()
            }
            if (title.isBlank() || artist.isBlank()) {
                parseName(name)?.let { (y, a, t) ->
                    if (artist.isBlank()) artist = a
                    if (title.isBlank()) title = t
                    if (year == null) year = y
                }
            }
            if (title.isBlank()) title = name.removeSuffix(".mp3")
            if (artist.isBlank()) artist = "inconnu"
            Local(title, artist, album, year, m.lengthInSeconds)
        } catch (_: Exception) {
            null
        } finally {
            tmp.delete()
        }
    }

    fun write(
        ctx: Context,
        uri: Uri,
        cacheDir: File,
        meta: Meta?,
        cover: Pair<ByteArray, String>?,
        lyrics: String?
    ): Boolean {
        val inTmp = File(cacheDir, "saf_in.mp3")
        val outTmp = File(cacheDir, "saf_out.mp3")
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                inTmp.outputStream().use { input.copyTo(it) }
            } ?: return false
            val m = Mp3File(inTmp)
            val tag = if (m.hasId3v2Tag()) m.id3v2Tag else ID3v24Tag()
            if (meta != null) {
                val oldArtist = tag.artist
                val oldAlbum = tag.album
                val artistChanged = !meta.artist.equals(oldArtist, ignoreCase = true)
                val albumChanged = !oldAlbum.isNullOrBlank() && meta.album != null &&
                        !meta.album.equals(oldAlbum, ignoreCase = true)
                if (artistChanged) {
                    tag.clearFrameSet("TSO2")
                    tag.clearFrameSet("TSOP")
                }
                if (albumChanged) {
                    tag.clearFrameSet("TPOS")
                    tag.clearFrameSet("TRCK")
                }
                if (artistChanged || albumChanged) {
                    // IDs MusicBrainz/Acoustid perimes, sans toucher au ReplayGain.
                    tag.clearFrameSet("UFID")
                    clearStaleTxxx(tag)
                }
                // Ne pas ecraser un album artist legitime (compilation, feat.).
                if (tag.albumArtist.isNullOrBlank() ||
                    tag.albumArtist.equals(oldArtist, ignoreCase = true)
                ) {
                    tag.albumArtist = meta.artist
                }
                tag.title = meta.title
                tag.artist = meta.artist
                if (meta.album != null) tag.album = meta.album
                if (meta.year != null) tag.year = meta.year.toString()
            }
            cover?.let { tag.setAlbumImage(it.first, it.second) }
            lyrics?.let { tag.setLyrics(it) }
            m.id3v2Tag = tag
            m.save(outTmp.absolutePath)
            ctx.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                outTmp.inputStream().use { it.copyTo(out) }
            } ?: return false
            true
        } catch (_: Exception) {
            false
        } finally {
            inTmp.delete()
            outTmp.delete()
        }
    }

    private val STALE_TXXX = setOf(
        "ARTISTS", "SCRIPT",
        "MusicBrainz Album Id", "MusicBrainz Artist Id",
        "MusicBrainz Album Artist Id", "MusicBrainz Release Group Id",
        "MusicBrainz Release Track Id", "MusicBrainz Album Type",
        "MusicBrainz Album Status", "MusicBrainz Album Release Country",
        "Acoustid Id"
    )

    /** Supprime les TXXX perimes en gardant ReplayGain et les tags inconnus. */
    private fun clearStaleTxxx(tag: ID3v2) {
        val set = tag.frameSets["TXXX"] ?: return
        val keep = set.frames.filter { txxxDescription(it.data) !in STALE_TXXX }
        if (keep.size == set.frames.size) return
        set.clear()
        keep.forEach { set.addFrame(it) }
        if (set.frames.isEmpty()) tag.clearFrameSet("TXXX")
    }

    private fun txxxDescription(data: ByteArray): String? {
        if (data.isEmpty()) return null
        val enc = data[0].toInt() and 0xFF
        val charset = when (enc) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        if (data.size <= 1) return null
        return String(data, 1, data.size - 1, charset).substringBefore('\u0000')
    }

    internal fun parseName(name: String): Triple<Int?, String, String>? {
        val base = name.replace(Regex("""\.mp3$""", RegexOption.IGNORE_CASE), "")
        val m = Regex("""^(\d{4})\s*-\s*(.+?)\s*-\s*(.+)$""", RegexOption.IGNORE_CASE)
            .find(base)
        if (m != null) {
            return Triple(
                m.groupValues[1].toIntOrNull(),
                m.groupValues[2].trim(),
                m.groupValues[3].trim()
            )
        }
        val parts = base.split(" - ")
        if (parts.size >= 2) {
            return Triple(null, parts.first().trim(), parts.drop(1).joinToString(" - ").trim())
        }
        return null
    }
}
