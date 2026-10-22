package com.fp.ytmp3

/**
 * Transforme un motif utilisateur en segments de chemin surs.
 * "%artist%/%title%" donne [artiste, titre.mp3] : un "/" cree un sous-dossier.
 */
object RenamePattern {

    const val DEFAULT = "%YYYY% - %artist% - %title%"

    private val RESERVED = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    /** Dernier element = nom de fichier (.mp3 ajoute si absent), les precedents = dossiers. */
    fun render(pattern: String, meta: Meta): List<String> {
        val raw = pattern.ifBlank { DEFAULT }.split('/', '\\')
        val out = ArrayList<String>()
        for ((index, part) in raw.withIndex()) {
            val last = index == raw.lastIndex
            var s = sanitize(cleanup(substitute(part, meta)))
            if (s.isBlank()) {
                if (last) out.add("inconnu")
                continue
            }
            out.add(s)
        }
        if (out.isEmpty()) out.add("inconnu")
        var name = out.last()
        if (!name.endsWith(".mp3", ignoreCase = true)) {
            name = name.take(116).trimEnd(' ', '.') + ".mp3"
        }
        out[out.lastIndex] = name.ifBlank { "inconnu.mp3" }
        return out
    }

    internal fun substitute(part: String, meta: Meta): String {
        var s = part
        s = s.replace("%YYYY%", meta.year?.toString() ?: "", ignoreCase = true)
        s = s.replace("%year%", meta.year?.toString() ?: "", ignoreCase = true)
        s = s.replace("%artist%", meta.artist, ignoreCase = true)
        s = s.replace("%title%", meta.title, ignoreCase = true)
        s = s.replace("%album%", meta.album ?: "", ignoreCase = true)
        return s
    }

    /** Supprime les separateurs orphelins laisses par une variable vide. */
    internal fun cleanup(s: String): String {
        var x = s.replace(Regex("""\s+"""), " ").trim()
        x = x.replace(Regex("""(?:\s*-\s*){2,}"""), " - ")
        x = x.replace(Regex("""^[\s\-–—_]+|[\s\-–—_]+$"""), "")
        return x.trim()
    }

    internal fun sanitize(s: String): String {
        var x = s.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
        x = x.replace(Regex("""\s+"""), " ").trim()
        x = x.trim('.', ' ')
        if (x.isEmpty() || x.all { it == '.' }) return ""
        if (x.substringBefore('.').uppercase() in RESERVED) x = "_$x"
        if (x.startsWith('.')) x = "_$x"
        if (x.length > 120) x = x.take(120).trimEnd(' ', '.')
        return x
    }
}
