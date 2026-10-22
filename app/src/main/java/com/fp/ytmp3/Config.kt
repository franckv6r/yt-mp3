package com.fp.ytmp3

import android.content.SharedPreferences

/**
 * Valeurs par defaut (cote developpeur) + acces aux valeurs effectives.
 * Si l'utilisateur a enregistre une valeur dans les Parametres, elle prime.
 */
object Config {

    // Valeurs par defaut de l'application. Laisser vide pour desactiver la fonction.
    const val DEFAULT_MANIFEST_URL = ""
    const val DEFAULT_EXTRACTOR_URL = ""
    const val DEFAULT_RENAME_PATTERN = "%YYYY% - %artist% - %title%"

    private const val KEY_PATTERN = "renamePattern"

    /** URLs de mise a jour reservees au developpeur (non exposees dans Parametres). */
    fun manifest(): String = DEFAULT_MANIFEST_URL

    fun extractor(): String = DEFAULT_EXTRACTOR_URL

    fun renamePattern(prefs: SharedPreferences): String {
        val v = prefs.getString(KEY_PATTERN, "") ?: ""
        return v.takeIf { it.isNotBlank() } ?: DEFAULT_RENAME_PATTERN
    }

    fun saveRenamePattern(prefs: SharedPreferences, value: String) {
        val v = value.trim().ifBlank { DEFAULT_RENAME_PATTERN }
        prefs.edit().putString(KEY_PATTERN, v).apply()
    }

    /** Revient aux valeurs par defaut de l'application. */
    fun reset(prefs: SharedPreferences) {
        prefs.edit()
            .remove(KEY_PATTERN)
            .remove("manifestUrl")
            .remove("extractorUrl")
            .apply()
    }
}
