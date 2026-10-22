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
import androidx.documentfile.provider.DocumentFile
import java.io.File

object MediaStoreUtil {

    internal const val SUBDIR = "yt-mp3"

    /**
     * Publie le mp3 :
     *  - dans le dossier choisi par l'utilisateur (SAF) s'il y en a un,
     *  - sinon dans le vrai dossier Musique via MediaStore.
     * segments = dossiers... + nom de fichier.
     */
    fun publish(ctx: Context, file: File, segments: List<String>, destUri: Uri?): String {
        require(segments.isNotEmpty()) { "nom vide" }
        val dirs = segments.dropLast(1)
        val name = segments.last()

        if (destUri != null) {
            val resolver = ctx.contentResolver
            val tree = DocumentFile.fromTreeUri(ctx, destUri)
                ?: throw IllegalStateException("dossier choisi inaccessible")
            var parent = rootDocument(tree.uri)
            for (d in dirs) {
                parent = findOrCreateDir(ctx, tree.uri, parent, d)
                    ?: throw IllegalStateException("creation du dossier impossible : $d")
            }
            val unique = uniqueSafName(ctx, tree.uri, parent, name, null)
            val doc = DocumentsContract.createDocument(resolver, parent, "audio/mpeg", unique)
                ?: throw IllegalStateException("creation impossible dans le dossier choisi")
            try {
                resolver.openOutputStream(doc)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: throw IllegalStateException("ecriture impossible dans le dossier choisi")
            } catch (e: Exception) {
                try { DocumentsContract.deleteDocument(resolver, doc) } catch (_: Exception) {}
                throw e
            }
            return doc.toString()
        }

        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = ctx.contentResolver
            val rel = relativePath(dirs)
            val unique = uniqueMediaName(ctx, rel, name, -1)
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, unique)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, rel)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore : insertion impossible")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: throw IllegalStateException("MediaStore : flux impossible")
            } catch (e: Exception) {
                try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                throw e
            }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri.toString()
        }

        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            (listOf(SUBDIR) + dirs).joinToString("/")
        )
        dir.mkdirs()
        var dst = File(dir, name)
        var n = 2
        while (dst.exists()) dst = File(dir, withSuffix(name, n++))
        file.copyTo(dst, overwrite = false)
        MediaScannerConnection.scanFile(ctx, arrayOf(dst.absolutePath), arrayOf("audio/mpeg"), null)
        return dst.absolutePath
    }

    /** URI du document racine (createDocument n'accepte pas une URI de tree). */
    internal fun rootDocument(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    internal fun relativePath(dirs: List<String>): String =
        (listOf(Environment.DIRECTORY_MUSIC, SUBDIR) + dirs).joinToString("/") + "/"

    internal fun withSuffix(name: String, n: Int): String {
        val base = if (name.endsWith(".mp3", ignoreCase = true)) name.dropLast(4) else name
        return "$base ($n).mp3"
    }

    // --------------------------------------------------------------- SAF list

    internal data class SafeChild(val uri: Uri, val name: String, val dir: Boolean)

    internal fun children(ctx: Context, tree: Uri, parent: Uri): List<SafeChild> {
        val out = ArrayList<SafeChild>()
        val parentId = if (DocumentsContract.isTreeUri(parent)) {
            DocumentsContract.getTreeDocumentId(parent)
        } else {
            DocumentsContract.getDocumentId(parent)
        }
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        ctx.contentResolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                out.add(
                    SafeChild(
                        DocumentsContract.buildDocumentUriUsingTree(tree, id),
                        name,
                        mime == DocumentsContract.Document.MIME_TYPE_DIR
                    )
                )
            }
        }
        return out
    }

    internal fun findOrCreateDir(ctx: Context, tree: Uri, parent: Uri, name: String): Uri? {
        children(ctx, tree, parent).firstOrNull { it.dir && it.name == name }?.let { return it.uri }
        val parentDoc = if (DocumentsContract.isTreeUri(parent)) rootDocument(parent) else parent
        return try {
            DocumentsContract.createDocument(
                ctx.contentResolver, parentDoc,
                DocumentsContract.Document.MIME_TYPE_DIR, name
            )
        } catch (_: Exception) {
            null
        }
    }

    internal fun uniqueSafName(
        ctx: Context,
        tree: Uri,
        parent: Uri,
        name: String,
        currentId: String?
    ): String {
        val names = children(ctx, tree, parent)
            .filter { !it.dir && DocumentsContract.getDocumentId(it.uri) != currentId }
            .map { it.name.lowercase() }
            .toHashSet()
        if (name.lowercase() !in names) return name
        var n = 2
        while (true) {
            val cand = withSuffix(name, n++)
            if (cand.lowercase() !in names) return cand
        }
    }

    // -------------------------------------------------------- MediaStore list

    internal fun uniqueMediaName(ctx: Context, rel: String, name: String, currentId: Long): String {
        val names = HashSet<String>()
        ctx.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME),
            "${MediaStore.Audio.Media.RELATIVE_PATH}=?",
            arrayOf(rel),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                if (currentId > 0 && c.getLong(0) == currentId) continue
                c.getString(1)?.let { names.add(it.lowercase()) }
            }
        }
        if (name.lowercase() !in names) return name
        var n = 2
        while (true) {
            val cand = withSuffix(name, n++)
            if (cand.lowercase() !in names) return cand
        }
    }

    /** Liste les noms de fichiers presents dans la destination. */
    fun listNames(ctx: Context, destUri: Uri?): List<String> {
        if (destUri != null) {
            val tree = DocumentFile.fromTreeUri(ctx, destUri) ?: return emptyList()
            val out = ArrayList<String>()
            fun collect(dir: DocumentFile) {
                for (f in dir.listFiles()) {
                    if (f.isDirectory) collect(f) else f.name?.let { out.add(it) }
                }
            }
            collect(tree)
            return out
        }

        if (Build.VERSION.SDK_INT >= 29) {
            val out = ArrayList<String>()
            val projection = arrayOf(MediaStore.Audio.Media.DISPLAY_NAME)
            val sel = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, sel, arrayOf("%$SUBDIR%"), null
            )?.use { c ->
                val idx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                while (c.moveToNext()) c.getString(idx)?.let { out.add(it) }
            }
            return out
        }

        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            SUBDIR
        )
        return dir.listFiles()?.map { it.name } ?: emptyList()
    }

    /** Liste les fichiers audio (nom, URI) du dossier choisi. */
    fun listAudio(ctx: Context, destUri: Uri?, recursive: Boolean = false): List<Pair<String, Uri>> {
        if (destUri != null) {
            val tree = DocumentFile.fromTreeUri(ctx, destUri) ?: return emptyList()
            val out = ArrayList<Pair<String, Uri>>()
            fun collect(dir: DocumentFile, recurse: Boolean) {
                for (f in dir.listFiles()) {
                    val n = f.name ?: continue
                    if (f.isDirectory) {
                        if (recurse) collect(f, true)
                    } else if (n.endsWith(".mp3", true) || f.type == "audio/mpeg") {
                        out.add(n to f.uri)
                    }
                }
            }
            collect(tree, recursive)
            return out.sortedBy { it.first.lowercase() }
        }

        if (Build.VERSION.SDK_INT < 29) {
            val root = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                SUBDIR
            )
            val files = if (recursive) {
                root.walkTopDown().filter { it.isFile }.toList()
            } else {
                root.listFiles()?.filter { it.isFile }.orEmpty()
            }
            return files
                .filter { it.name.endsWith(".mp3", true) }
                .map { it.name to Uri.fromFile(it) }
                .sortedBy { it.first.lowercase() }
        }

        val out = ArrayList<Pair<String, Uri>>()
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME)
        val sel = if (recursive) {
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
        } else {
            "${MediaStore.Audio.Media.RELATIVE_PATH} = ?"
        }
        val args = if (recursive) {
            arrayOf("%$SUBDIR%")
        } else {
            arrayOf(Environment.DIRECTORY_MUSIC + "/" + SUBDIR + "/")
        }
        ctx.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, sel, args, null
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val name = c.getString(nIdx) ?: continue
                out.add(name to ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id))
            }
        }
        return out.sortedBy { it.first.lowercase() }
    }
}
