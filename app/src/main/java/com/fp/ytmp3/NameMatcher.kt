package com.fp.ytmp3

import java.text.Normalizer

/**
 * Compare un titre de playlist ("Artiste - Titre", avec bruit YouTube) aux noms
 * de fichiers existants ("ANNEE - Artiste - Titre.mp3").
 */
object NameMatcher {

    private val noise = Regex(
        """\b(official|audio|video|videos|clip|clips|lyrics?|hd|hq|music|original|full|oficial|officiel|remaster\w*|4k|1080p|feat|ft|remix|mix)\b"""
    )

    data class Fp(val full: Set<String>, val title: Set<String>)

    fun prepare(names: List<String>): List<Fp> = names.map { n ->
        val core = n
            .replace(Regex("""^\d{4}\s*-\s*"""), "")
            .replace(Regex("""\.mp3$""", RegexOption.IGNORE_CASE), "")
        val parts = core.split(" - ")
        Fp(norm(core), norm(parts.last()))
    }

    fun matchesAny(title: String, fps: List<Fp>): Boolean =
        indexOfMatch(title, fps) >= 0

    /** Normalise le titre une seule fois, puis renvoie l'index du fichier matche. */
    fun indexOfMatch(title: String, fps: List<Fp>): Int {
        val t = norm(title)
        if (t.isEmpty()) return -1
        return fps.indexOfFirst { score(t, it.full) >= 0.75 || score(t, it.title) >= 0.75 }
    }

    private fun score(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / minOf(a.size, b.size)
    }

    private fun norm(s: String): Set<String> {
        var x = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
        x = x.lowercase()
        x = x.replace(
            Regex("""[\(\[]\s*[^\)\]]*\b(official|audio|video|clip|lyrics?|hd|hq|original|full|live|remix|mix|version|oficial|officiel)\b[^\)\]]*[\)\]]"""),
            " "
        )
        x = noise.replace(x, " ")
        x = x.replace(Regex("""['’`]"""), "")
        x = x.replace(Regex("""[^a-z0-9]+"""), " ")
        return x.split(" ").filter {
            it.isNotBlank() && !(it.length == 4 && it.all { c -> c.isDigit() })
        }.toSet()
    }
}
