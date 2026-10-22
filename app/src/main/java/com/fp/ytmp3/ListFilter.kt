package com.fp.ytmp3

import java.text.Normalizer

private val marks = Regex("""\p{M}+""")

private fun fold(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "").lowercase()

/** Filtre partage par les deux listes ; requete nettoyee, sans casse ni accents. */
internal fun <T> filterByQuery(items: List<T>, query: String, text: (T) -> String): List<T> {
    val q = fold(query.trim())
    return if (q.isEmpty()) items else items.filter { fold(text(it)).contains(q) }
}
