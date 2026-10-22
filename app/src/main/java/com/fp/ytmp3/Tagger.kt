package com.fp.ytmp3

import android.content.Context
import android.net.Uri
import com.mpatric.mp3agic.ID3v24Tag
import com.mpatric.mp3agic.Mp3File
import java.io.File

object Tagger {

    fun tagAndSave(
        ctx: Context,
        mp3: File,
        meta: Meta,
        cover: Pair<ByteArray, String>?,
        lyrics: String?,
        destUri: Uri?,
        pattern: String
    ): String {
        val m = Mp3File(mp3)
        val tag = if (m.hasId3v2Tag()) m.id3v2Tag else ID3v24Tag()
        tag.title = meta.title
        tag.artist = meta.artist
        meta.album?.let { tag.album = it }
        meta.year?.let { tag.year = it.toString() }
        cover?.let { tag.setAlbumImage(it.first, it.second) }
        lyrics?.let { tag.setLyrics(it) }

        m.id3v2Tag = tag
        val tagged = File(mp3.parentFile, mp3.name + ".tagged")
        m.save(tagged.absolutePath)

        val segments = RenamePattern.render(pattern, meta)
        val where = MediaStoreUtil.publish(ctx, tagged, segments, destUri)
        tagged.delete()
        return where
    }

}
