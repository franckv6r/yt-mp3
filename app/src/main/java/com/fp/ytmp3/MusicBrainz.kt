package com.fp.ytmp3

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.Normalizer
import java.util.concurrent.CancellationException

data class Meta(
    val artist: String,
    val title: String,
    val year: Int?,
    val album: String?,
    val rgid: String?,
    val coverUrl: String? = null,
    val matched: Boolean = false
)

/**
 * Resolution des metadonnees :
 *  1. MusicBrainz (base ouverte, 1 requete/seconde)
 *  2. iTunes (cache + throttle ~20/min)
 *  3. Deezer (dernier recours, 50 req/5 s)
 * Le resultat est mis en cache ; aucun echec n'est mis en cache.
 */
object MusicBrainz {

    private const val UA = "yt-mp3-poc/0.4 ( local )"
    private const val CATALOG_CONNECT_MS = 8_000
    private const val CATALOG_READ_MS = 15_000

    private fun checkStop(shouldStop: () -> Boolean) {
        if (shouldStop()) throw CancellationException("arret")
    }

    private val mbLock = Any()
    private var lastMb = 0L

    private val itLock = Any()
    private var lastItunes = 0L

    private val dzLock = Any()
    private var lastDeezer = 0L

    private val NOISE = setOf(
        "official", "officiel", "oficial", "video", "videos", "clip", "clips",
        "audio", "lyrics", "lyric", "hd", "hq", "4k", "1080p", "music",
        "original", "full", "remaster", "remastered", "remasterise",
        "feat", "ft", "topic", "vevo"
    )

    private val BAD_SECONDARY = setOf(
        "Live", "Compilation", "Remix", "DJ-mix", "Mixtape", "Interview", "Spokenword",
        "Soundtrack"
    )

    // ---------------------------------------------------------------- lookup

    fun lookup(
        ctx: Context,
        videoTitle: String,
        uploader: String,
        uploadYear: Int? = null,
        shouldStop: () -> Boolean = { false }
    ): Meta {
        checkStop(shouldStop)
        val raw = videoTitle.trim().ifBlank { uploader.trim() }
        val channel = cleanChannel(uploader)
        cacheGet(ctx, raw, channel)?.let { return it }

        val mb = musicBrainz(raw, channel, shouldStop)
        if (mb != null) {
            if (mb.year == null || mb.album == null || mb.rgid == null) {
                checkStop(shouldStop)
                val extra = itunes(raw, channel, shouldStop) ?: deezer(raw, channel, shouldStop)
                if (extra != null) {
                    val merged = mb.copy(
                        year = mb.year ?: extra.year,
                        album = mb.album ?: extra.album,
                        coverUrl = mb.coverUrl ?: extra.coverUrl
                    )
                    cachePut(ctx, raw, channel, merged)
                    return merged
                }
            }
            cachePut(ctx, raw, channel, mb)
            return mb
        }
        checkStop(shouldStop)
        itunes(raw, channel, shouldStop)?.let { cachePut(ctx, raw, channel, it); return it }
        checkStop(shouldStop)
        deezer(raw, channel, shouldStop)?.let { cachePut(ctx, raw, channel, it); return it }

        return fallback(raw, uploader, uploadYear)
    }

    // ----------------------------------------------------------- MusicBrainz

    private fun musicBrainz(raw: String, channel: String, shouldStop: () -> Boolean): Meta? {
        checkStop(shouldStop)
        val clean = clean(raw)
        val parsed = splitArtistTitle(clean)
        val queries = ArrayList<String>()
        if (parsed != null) {
            queries.add("recording:\"${q(parsed.second)}\" AND artist:\"${q(parsed.first)}\"")
        } else if (channel.isNotBlank()) {
            queries.add("recording:\"${q(clean)}\" AND artist:\"${q(channel)}\"")
        }
        queries.add(clean)

        var best: JSONObject? = null
        var bestRel = Release(null, null, null)
        var bestScore = 0.0
        for (query in queries.distinct()) {
            checkStop(shouldStop)
            val arr = mb("recording", query, shouldStop) ?: continue
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val artist = credit(r)
                val title = r.optString("title")
                if (!matches(raw, channel, artist, title)) continue
                val rel = pickRelease(r.optJSONArray("releases"))
                var score = matchScore(raw, channel, artist, title) + r.optInt("score") / 1000.0
                // Un vrai album studio prime sur un live/compilation ou une prise sans date.
                score += when {
                    rel.studio -> 0.10
                    rel.album != null -> 0.02
                    else -> 0.0
                }
                // Une prise canonique est presente sur beaucoup de sorties ;
                // une reprise/live isolee en a une ou deux.
                val releaseCount = r.optJSONArray("releases")?.length() ?: 0
                score += minOf(releaseCount, 50) * 0.001
                rel.year?.let { score += (10000 - it) / 1_000_000.0 }
                if (score > bestScore) {
                    bestScore = score
                    best = r
                    bestRel = rel
                }
            }
            if (best != null) break
        }
        val r = best ?: return null
        val rel = bestRel
        return Meta(
            artist = credit(r).ifBlank { channel },
            title = r.optString("title"),
            year = rel.year,
            album = rel.album,
            rgid = rel.rgid,
            matched = true
        )
    }

    internal data class Release(val album: String?, val year: Int?, val rgid: String?, val studio: Boolean = false)

    /** Choisit la sortie studio la plus ancienne, pas un live/compilation. */
    internal fun pickRelease(arr: JSONArray?): Release {
        if (arr == null) return Release(null, null, null)
        data class Cand(
            val album: String,
            val year: Int,
            val rgid: String,
            val type: String,
            val secondary: Set<String>,
            val index: Int
        )

        val all = ArrayList<Cand>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val rg = r.optJSONObject("release-group") ?: continue
            val id = rg.optString("id")
            if (id.isBlank()) continue
            val date = r.optString("date").ifBlank { rg.optString("first-release-date") }
            val year = date.take(4).toIntOrNull() ?: continue
            val secondary = HashSet<String>()
            rg.optJSONArray("secondary-types")?.let { a ->
                for (k in 0 until a.length()) secondary.add(a.optString(k))
            }
            all.add(
                Cand(
                    album = rg.optString("title").ifBlank { r.optString("title") },
                    year = year,
                    rgid = id,
                    type = rg.optString("primary-type"),
                    secondary = secondary,
                    index = i
                )
            )
        }
        if (all.isEmpty()) return Release(null, null, null)
        val studio = all.filter { c -> c.secondary.none { it in BAD_SECONDARY } }
        val pool = studio.ifEmpty { all }
        val rank = mapOf("Album" to 0, "EP" to 1, "Single" to 2)
        val best = pool.sortedWith(
            compareBy({ rank[it.type] ?: 3 }, { it.year }, { it.index })
        ).first()
        return Release(best.album, best.year, best.rgid, studio.isNotEmpty())
    }

    private fun credit(o: JSONObject): String {
        val credits = o.optJSONArray("artist-credit") ?: return ""
        val names = ArrayList<String>()
        for (i in 0 until credits.length()) {
            val c = credits.optJSONObject(i) ?: continue
            c.optJSONObject("artist")?.optString("name")?.takeIf { it.isNotBlank() }?.let { names.add(it) }
        }
        return names.joinToString(" & ")
    }

    private fun mb(entity: String, query: String, shouldStop: () -> Boolean): JSONArray? {
        val url = "https://musicbrainz.org/ws/2/$entity?query=${enc(query)}&fmt=json&limit=25"
        repeat(2) { attempt ->
            checkStop(shouldStop)
            try {
                mbWait()
                val o = JSONObject(
                    Http.text(url, mapOf("User-Agent" to UA), CATALOG_CONNECT_MS, CATALOG_READ_MS)
                )
                return o.optJSONArray(if (entity == "recording") "recordings" else "release-groups")
            } catch (_: Exception) {
                if (attempt == 0) Thread.sleep(1500)
            }
        }
        return null
    }

    private fun mbWait() {
        synchronized(mbLock) {
            val wait = 1100 - (System.currentTimeMillis() - lastMb)
            if (wait > 0) Thread.sleep(wait)
            lastMb = System.currentTimeMillis()
        }
    }

    // ---------------------------------------------------------------- iTunes

    private fun itunes(raw: String, channel: String, shouldStop: () -> Boolean): Meta? {
        checkStop(shouldStop)
        itunesWait()
        val url = "https://itunes.apple.com/search?term=${enc(clean(raw))}" +
                "&entity=song&limit=15&country=FR"
        val json = try {
            Http.text(url, mapOf("User-Agent" to UA), CATALOG_CONNECT_MS, CATALOG_READ_MS)
        } catch (_: Exception) {
            return null
        }
        return parseItunes(json, raw, channel)
    }

    internal fun parseItunes(json: String, raw: String, channel: String): Meta? {
        val results = try {
            JSONObject(json).optJSONArray("results")
        } catch (_: Exception) {
            null
        } ?: return null
        var best: Meta? = null
        var bestScore = 0.0
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val artist = o.optString("artistName")
            val title = o.optString("trackName")
            if (artist.isBlank() || title.isBlank()) continue
            if (!matches(raw, channel, artist, title)) continue
            val score = matchScore(raw, channel, artist, title)
            if (score <= bestScore) continue
            bestScore = score
            best = Meta(
                artist = artist,
                title = title,
                year = o.optString("releaseDate").take(4).toIntOrNull(),
                album = o.optString("collectionName").ifBlank { null },
                rgid = null,
                coverUrl = artwork(o.optString("artworkUrl100")),
                matched = true
            )
        }
        return best
    }

    private fun itunesWait() {
        synchronized(itLock) {
            // ~19 requetes/minute, marge sous la limite Apple (~20/min).
            val wait = 3100 - (System.currentTimeMillis() - lastItunes)
            if (wait > 0) Thread.sleep(wait)
            lastItunes = System.currentTimeMillis()
        }
    }

    private fun artwork(url: String): String? {
        if (url.isBlank()) return null
        return url.replace("100x100bb", "600x600bb")
            .replace("100x100-75", "600x600-75")
    }

    // ---------------------------------------------------------------- Deezer

    internal data class Dz(val meta: Meta, val albumId: Long?)

    private fun deezer(raw: String, channel: String, shouldStop: () -> Boolean): Meta? {
        checkStop(shouldStop)
        deezerWait()
        val url = "https://api.deezer.com/search?q=${enc(clean(raw))}&limit=15"
        val json = try {
            Http.text(url, mapOf("User-Agent" to UA), CATALOG_CONNECT_MS, CATALOG_READ_MS)
        } catch (_: Exception) {
            return null
        }
        var best = parseDeezer(json, raw, channel) ?: return null
        val id = best.albumId
        if (id != null) {
            checkStop(shouldStop)
            deezerWait()
            try {
                val a = JSONObject(Http.text("https://api.deezer.com/album/$id", mapOf("User-Agent" to UA), CATALOG_CONNECT_MS, CATALOG_READ_MS))
                val year = a.optString("release_date").take(4).toIntOrNull()
                best = Dz(
                    best.meta.copy(
                        year = year ?: best.meta.year,
                        album = a.optString("title").ifBlank { best.meta.album }
                    ),
                    null
                )
            } catch (_: Exception) {
            }
        }
        return best.meta
    }

    internal fun parseDeezer(json: String, raw: String, channel: String): Dz? {
        val data = try {
            JSONObject(json).optJSONArray("data")
        } catch (_: Exception) {
            null
        } ?: return null
        var best: Dz? = null
        var bestScore = 0.0
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val artist = o.optJSONObject("artist")?.optString("name") ?: ""
            val title = o.optString("title")
            if (artist.isBlank() || title.isBlank()) continue
            if (!matches(raw, channel, artist, title)) continue
            val score = matchScore(raw, channel, artist, title)
            if (score <= bestScore) continue
            val album = o.optJSONObject("album")
            bestScore = score
            best = Dz(
                Meta(
                    artist = artist,
                    title = title,
                    year = null,
                    album = album?.optString("title")?.ifBlank { null },
                    rgid = null,
                    coverUrl = album?.optString("cover_xl")?.ifBlank { null },
                    matched = true
                ),
                album?.optLong("id")
            )
        }
        return best
    }

    private fun deezerWait() {
        synchronized(dzLock) {
            val wait = 250 - (System.currentTimeMillis() - lastDeezer)
            if (wait > 0) Thread.sleep(wait)
            lastDeezer = System.currentTimeMillis()
        }
    }

    // --------------------------------------------------------------- matching

    /**
     * Un resultat est retenu si le titre est present dans le texte source ET si
     * l'artiste est soit dans le titre, soit dans le nom de la chaine.
     * Evite les reprises/karaoke et les faux positifs sur un titre seul.
     */
    internal fun matches(raw: String, channel: String, artist: String, title: String): Boolean {
        val rt = rawTokens(raw)
        val at = tokens(artist, filterNoise = false)
        val tt = tokens(title, filterNoise = false)
        if (rt.isEmpty() || tt.isEmpty()) return false
        val titleCov = overlap(tt, rt).toDouble() / tt.size
        if (titleCov < 0.74) return false
        val all = at + tt
        val allCov = overlap(all, rt).toDouble() / all.size
        val artistInRaw = at.isNotEmpty() && rt.containsAll(at)
        val artistInChannel = inChannel(at, channel)
        // Un suffixe de catalogue ("(Live)", "Remastered") ne doit pas faire
        // echouer un titre dont l'artiste est deja confirme.
        return allCov >= 0.999 || (titleCov >= 0.82 && (artistInRaw || artistInChannel))
    }

    internal fun matchScore(raw: String, channel: String, artist: String, title: String): Double {
        val rt = rawTokens(raw)
        val at = tokens(artist, filterNoise = false)
        val tt = tokens(title, filterNoise = false)
        if (rt.isEmpty() || tt.isEmpty()) return 0.0
        val titleCov = overlap(tt, rt).toDouble() / tt.size
        val all = at + tt
        val allCov = overlap(all, rt).toDouble() / all.size
        val artistOk = (at.isNotEmpty() && rt.containsAll(at)) || inChannel(at, channel)
        return 0.6 * titleCov + 0.3 * allCov + (if (artistOk) 0.1 else 0.0)
    }

    private fun inChannel(artistTokens: Set<String>, channel: String): Boolean {
        if (artistTokens.isEmpty()) return false
        val ch = tokens(channel).ifEmpty { tokens(channel, filterNoise = false) }
            .sorted().joinToString("")
        if (ch.isEmpty()) return false
        return ch.contains(artistTokens.sorted().joinToString(""))
    }

    private fun overlap(a: Set<String>, b: Set<String>): Int = a.count { it in b }

    internal fun tokens(s: String, filterNoise: Boolean = true): Set<String> {
        var x = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase()
        // "Papa's" et "Papas" doivent produire les memes tokens.
        x = x.replace(Regex("""['’`]"""), "")
        x = x.replace(Regex("""[^a-z0-9]+"""), " ")
        return x.split(" ").filter { it.isNotBlank() && (!filterNoise || it !in NOISE) }.toSet()
    }

    private fun rawTokens(s: String): Set<String> =
        tokens(s).ifEmpty { tokens(s, filterNoise = false) }

    // -------------------------------------------------------------- fallback

    private fun fallback(rawTitle: String, uploader: String, uploadYear: Int?): Meta {
        val parsed = splitArtistTitle(clean(rawTitle))
        val artist = parsed?.first?.takeIf { it.isNotBlank() }
            ?: uploader.trim().ifBlank { "inconnu" }
        val title = parsed?.second?.takeIf { it.isNotBlank() }
            ?: clean(rawTitle).ifBlank { rawTitle }
        return Meta(
            artist = artist,
            title = title,
            year = uploadYear?.takeIf { it > 1900 },
            album = null,
            rgid = null,
            matched = false
        )
    }

    private fun splitArtistTitle(t: String): Pair<String, String>? {
        for (sep in listOf(" - ", " – ", " — ", " | ")) {
            val i = t.indexOf(sep)
            if (i > 0) {
                return t.substring(0, i).trim() to clean(t.substring(i + sep.length))
            }
        }
        return null
    }

    private fun cleanChannel(uploader: String): String {
        var x = uploader
        x = x.replace(Regex("""\s*[-–—]\s*topic$""", RegexOption.IGNORE_CASE), "")
        x = x.replace(Regex("""\s*vevo$""", RegexOption.IGNORE_CASE), "")
        x = x.replace(Regex("""\s*\(?(official|officiel)\)?$""", RegexOption.IGNORE_CASE), "")
        return x.trim()
    }

    // ---------------------------------------------------------------- covers

    /** Pochette : Cover Art Archive, puis Deezer/iTunes si CAA est indisponible. */
    fun cover(meta: Meta, shouldStop: () -> Boolean = { false }): Pair<ByteArray, String>? {
        checkStop(shouldStop)
        if (!meta.rgid.isNullOrBlank()) {
            // CAA redirige vers archive.org, parfois inaccessible : timeout court
            // pour basculer vite sur Deezer/iTunes.
            image(
                "https://coverartarchive.org/release-group/${meta.rgid}/front-500",
                connectMs = 6000,
                readMs = 8000
            )?.let { return it }
        }
        checkStop(shouldStop)
        meta.coverUrl?.let { url -> image(url)?.let { return it } }

        // CAA renvoie vers archive.org, parfois inaccessible : on demande l'artwork
        // a un catalogue (Deezer d'abord car plus permissif, puis iTunes).
        checkStop(shouldStop)
        val q = listOf(meta.artist, meta.title).joinToString(" ")
        deezerArt(q, meta.artist, shouldStop)?.let { url -> image(url)?.let { return it } }
        checkStop(shouldStop)
        itunesArt(q, meta.artist, shouldStop)?.let { url -> image(url)?.let { return it } }
        return null
    }

    private fun image(
        url: String,
        connectMs: Int = CATALOG_CONNECT_MS,
        readMs: Int = 20000
    ): Pair<ByteArray, String>? = try {
        val data = Http.bytes(url, mapOf("User-Agent" to UA), connectMs, readMs)
        if (data.isEmpty()) null else data to sniff(data)
    } catch (_: Exception) {
        null
    }

    private fun deezerArt(raw: String, channel: String, shouldStop: () -> Boolean): String? {
        checkStop(shouldStop)
        deezerWait()
        return try {
            val url = "https://api.deezer.com/search?q=${enc(clean(raw))}&limit=10"
            val json = Http.text(url, mapOf("User-Agent" to UA), CATALOG_CONNECT_MS, CATALOG_READ_MS)
            parseDeezer(json, raw, channel)?.meta?.coverUrl
        } catch (_: Exception) {
            null
        }
    }

    private fun itunesArt(raw: String, channel: String, shouldStop: () -> Boolean): String? {
        checkStop(shouldStop)
        itunesWait()
        return try {
            val url = "https://itunes.apple.com/search?term=${enc(clean(raw))}" +
                    "&entity=song&limit=10&country=FR"
            val json = Http.text(url, mapOf("User-Agent" to UA), CATALOG_CONNECT_MS, CATALOG_READ_MS)
            parseItunes(json, raw, channel)?.coverUrl
        } catch (_: Exception) {
            null
        }
    }

    private fun sniff(b: ByteArray): String =
        if (b.size > 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) "image/jpeg"
        else "image/png"

    // ---------------------------------------------------------------- lyrics

    private fun textRetry(url: String, shouldStop: () -> Boolean): String? {
        repeat(2) { attempt ->
            checkStop(shouldStop)
            try {
                return Http.text(url, mapOf("User-Agent" to UA), CATALOG_CONNECT_MS, CATALOG_READ_MS)
            } catch (_: Exception) {
                if (attempt == 0) Thread.sleep(1000)
            }
        }
        return null
    }

    /** Paroles via LRCLIB (synchronisees si disponible). */
    fun lyrics(
        artist: String,
        title: String,
        album: String?,
        duration: Long?,
        shouldStop: () -> Boolean = { false }
    ): String? {
        checkStop(shouldStop)
        val params = linkedMapOf(
            "artist_name" to artist,
            "track_name" to title
        )
        album?.let { params["album_name"] = it }
        duration?.let { if (it > 0) params["duration"] = it.toString() }
        try {
            val url = "https://lrclib.net/api/get?" + params.entries.joinToString("&") {
                "${enc(it.key)}=${enc(it.value)}"
            }
            val body = textRetry(url, shouldStop)
            if (body != null) {
                val o = JSONObject(body)
                val l = o.optString("syncedLyrics").ifBlank { o.optString("plainLyrics") }
                if (l.isNotBlank()) return l
            }
        } catch (_: Exception) {
        }
        checkStop(shouldStop)
        return try {
            val url = "https://lrclib.net/api/search?artist_name=${enc(artist)}&track_name=${enc(title)}"
            val body = textRetry(url, shouldStop) ?: return null
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val l = o.optString("syncedLyrics").ifBlank { o.optString("plainLyrics") }
                if (l.isNotBlank()) return l
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    // ----------------------------------------------------------------- cache

    private fun cacheGet(ctx: Context, raw: String, channel: String): Meta? {
        return try {
            val prefs = ctx.getSharedPreferences("meta_cache", Context.MODE_PRIVATE)
            val s = prefs.getString(cacheKey(raw, channel), null) ?: return null
            val o = JSONObject(s)
            Meta(
                artist = o.optString("artist"),
                title = o.optString("title"),
                year = if (o.isNull("year")) null else o.optInt("year"),
                album = if (o.isNull("album")) null else o.optString("album"),
                rgid = if (o.isNull("rgid")) null else o.optString("rgid"),
                coverUrl = if (o.isNull("coverUrl")) null else o.optString("coverUrl"),
                matched = true
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun cachePut(ctx: Context, raw: String, channel: String, meta: Meta) {
        if (!meta.matched) return
        try {
            val prefs = ctx.getSharedPreferences("meta_cache", Context.MODE_PRIVATE)
            if (prefs.all.size > 500) prefs.edit().clear().apply()
            val o = JSONObject()
                .put("artist", meta.artist)
                .put("title", meta.title)
                .put("year", meta.year ?: JSONObject.NULL)
                .put("album", meta.album ?: JSONObject.NULL)
                .put("rgid", meta.rgid ?: JSONObject.NULL)
                .put("coverUrl", meta.coverUrl ?: JSONObject.NULL)
            prefs.edit().putString(cacheKey(raw, channel), o.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun cacheKey(raw: String, channel: String): String {
        // Changer la version invalide les anciennes reponses (ex. une compile).
        val src = "v3|" + rawTokens(raw).sorted().joinToString(" ") + "|" +
                tokens(channel).ifEmpty { tokens(channel, filterNoise = false) }
                    .sorted().joinToString(" ")
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(src.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // ----------------------------------------------------------------- utils

    private fun q(s: String): String = s.replace("\"", "\\\"")

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Nettoie le bruit YouTube sans casser le couple artiste/titre. */
    internal fun clean(t: String): String {
        var x = t
        x = x.replace(Regex("""^\d{4}\s*[-–—]\s*"""), "")
        x = x.replace(
            Regex("""\s*[-|–—]\s*(official|clip|video|audio|lyrics?|hd|hq|music video|4k).*$""",
                RegexOption.IGNORE_CASE),
            ""
        )
        x = x.replace(
            Regex("""[\(\[]\s*[^\)\]]*\b(official|audio|video|clip|lyrics?|hd|hq|music video|original|full|live|version|remix|mix|oficial|officiel|remaster\w*|4k|1080p)\b[^\)\]]*[\)\]]""",
                RegexOption.IGNORE_CASE),
            " "
        )
        x = x.replace(Regex("""\s*\b(feat|ft)\.?\s.*$""", RegexOption.IGNORE_CASE), "")
        x = x.replace(Regex("""^["“”']+|["“”']+$"""), "")
        x = x.replace(Regex("""\s*\.mp3$""", RegexOption.IGNORE_CASE), "")
        return x.replace(Regex("""\s+"""), " ").trim(' ', '-', '|', '–', '—')
    }
}
