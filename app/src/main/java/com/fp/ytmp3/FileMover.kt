package com.fp.ytmp3

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

/** Renomme/deplace un fichier existant vers les segments du motif. */
object FileMover {

    fun move(ctx: Context, treeUri: Uri?, source: Uri, segments: List<String>): Uri? {
        if (segments.isEmpty()) return null
        return if (treeUri != null) moveSaf(ctx, treeUri, source, segments)
        else moveMedia(ctx, source, segments)
    }

    // ------------------------------------------------------------------- SAF

    private fun moveSaf(ctx: Context, tree: Uri, source: Uri, segments: List<String>): Uri? {
        val resolver = ctx.contentResolver
        val sourceId = DocumentsContract.getDocumentId(source)
        val parent = findParent(ctx, tree, sourceId) ?: return null
        var targetParent = MediaStoreUtil.rootDocument(tree)
        for (d in segments.dropLast(1)) {
            targetParent = MediaStoreUtil.findOrCreateDir(ctx, tree, targetParent, d) ?: return null
        }
        val targetName = MediaStoreUtil.uniqueSafName(ctx, tree, targetParent, segments.last(), sourceId)

        if (parent.first == targetParent && parent.second == targetName) return source

        if (parent.first == targetParent) {
            try {
                DocumentsContract.renameDocument(resolver, source, targetName)?.let { return it }
            } catch (_: Exception) {
            }
            return copySaf(ctx, source, targetParent, targetName)
        }

        val moved = try {
            DocumentsContract.moveDocument(resolver, source, parent.first, targetParent)
        } catch (_: Exception) {
            null
        }
        if (moved != null) {
            val actual = nameOf(ctx, tree, targetParent, moved)
            if (actual == targetName) return moved
            return try {
                DocumentsContract.renameDocument(resolver, moved, targetName) ?: moved
            } catch (_: Exception) {
                moved
            }
        }
        return copySaf(ctx, source, targetParent, targetName)
    }

    private fun findParent(ctx: Context, tree: Uri, sourceId: String): Pair<Uri, String>? {
        // ExternalStorageProvider encode le chemin dans l'ID : une seule requete
        // suffit. Le parcours recursif ci-dessous prenait ~30 s sur 400 fichiers.
        try {
            val parentId = sourceId.substringBeforeLast('/', "")
            val parent = if (parentId.isBlank()) {
                MediaStoreUtil.rootDocument(tree)
            } else {
                DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
            }
            val name = MediaStoreUtil.children(ctx, tree, parent).firstOrNull {
                DocumentsContract.getDocumentId(it.uri) == sourceId
            }?.name
            if (name != null) return parent to name
        } catch (_: Exception) {
        }
        fun walk(parent: Uri): Pair<Uri, String>? {
            for (c in MediaStoreUtil.children(ctx, tree, parent)) {
                if (DocumentsContract.getDocumentId(c.uri) == sourceId) return parent to c.name
                if (c.dir) walk(c.uri)?.let { return it }
            }
            return null
        }
        return walk(MediaStoreUtil.rootDocument(tree))
    }

    private fun nameOf(ctx: Context, tree: Uri, parent: Uri, doc: Uri): String? {
        val id = try { DocumentsContract.getDocumentId(doc) } catch (_: Exception) { return null }
        return MediaStoreUtil.children(ctx, tree, parent).firstOrNull {
            DocumentsContract.getDocumentId(it.uri) == id
        }?.name
    }

    private fun copySaf(ctx: Context, source: Uri, parent: Uri, name: String): Uri? {
        val resolver = ctx.contentResolver
        val doc = try {
            DocumentsContract.createDocument(resolver, parent, "audio/mpeg", name)
        } catch (_: Exception) {
            null
        } ?: return null
        return try {
            val input = resolver.openInputStream(source)
            val output = resolver.openOutputStream(doc)
            if (input == null || output == null) {
                input?.close(); output?.close()
                try { DocumentsContract.deleteDocument(resolver, doc) } catch (_: Exception) {}
                return null
            }
            input.use { i -> output.use { o -> i.copyTo(o) } }
            try { DocumentsContract.deleteDocument(resolver, source) } catch (_: Exception) {}
            doc
        } catch (_: Exception) {
            try { DocumentsContract.deleteDocument(resolver, doc) } catch (_: Exception) {}
            null
        }
    }

    // ------------------------------------------------------------ MediaStore

    private fun moveMedia(ctx: Context, source: Uri, segments: List<String>): Uri? {
        val resolver = ctx.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val rel = MediaStoreUtil.relativePath(segments.dropLast(1))
            val id = try { ContentUris.parseId(source) } catch (_: Exception) { -1L }
            val name = MediaStoreUtil.uniqueMediaName(ctx, rel, segments.last(), id)
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.RELATIVE_PATH, rel)
            }
            val n = try { resolver.update(source, values, null, null) } catch (_: Exception) { 0 }
            return if (n > 0) source else null
        }

        val data = try {
            resolver.query(
                source, arrayOf(MediaStore.Audio.Media.DATA), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) {
            null
        } ?: return null
        val src = File(data)
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            (listOf(MediaStoreUtil.SUBDIR) + segments.dropLast(1)).joinToString("/")
        )
        if (!dir.exists() && !dir.mkdirs()) return null
        var dst = File(dir, segments.last())
        var n = 2
        while (dst.exists() && dst.absolutePath != src.absolutePath) {
            dst = File(dir, MediaStoreUtil.withSuffix(segments.last(), n++))
        }
        if (dst.absolutePath != src.absolutePath) {
            if (!src.renameTo(dst)) {
                try {
                    src.copyTo(dst, overwrite = false)
                    src.delete()
                } catch (_: Exception) {
                    return null
                }
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, dst.name)
            put(MediaStore.Audio.Media.DATA, dst.absolutePath)
        }
        resolver.update(source, values, null, null)
        MediaScannerConnection.scanFile(ctx, arrayOf(dst.absolutePath), arrayOf("audio/mpeg"), null)
        return source
    }
}
