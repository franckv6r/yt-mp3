package com.fp.ytmp3

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

object Updater {

    private const val UA = "yt-mp3-poc/0.2 ( local )"

    fun downloadApk(ctx: Context, url: String): File {
        val f = File(ctx.cacheDir, "update.apk")
        Http.download(url, f)
        return f
    }

    /** Ouvre l'installateur Android (l'utilisateur valide l'installation). */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
    }

    fun currentVersionCode(ctx: Context): Int {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    }

    fun currentVersionName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
}

/** Derniere release GitHub + APK a installer. */
data class GitHubRelease(val tag: String, val version: String, val apkUrl: String)

object GitHubUpdater {

    private const val UA = "yt-mp3-poc/0.4 ( local )"

    fun latest(repo: String): GitHubRelease {
        if (repo.isBlank()) throw IllegalStateException("depot GitHub non configure")
        val url = "https://api.github.com/repos/$repo/releases/latest"
        val json = Http.text(
            url,
            mapOf("User-Agent" to UA, "Accept" to "application/vnd.github+json")
        )
        val o = JSONObject(json)
        val tag = o.optString("tag_name").ifBlank { "unknown" }
        val assets = o.optJSONArray("assets") ?: throw IllegalStateException("release sans fichier")
        var apk: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (!a.optString("name").endsWith(".apk", ignoreCase = true)) continue
            val u = a.optString("browser_download_url")
            if (u.isNotBlank()) { apk = u; break }
        }
        return GitHubRelease(
            tag = tag,
            version = versionOf(tag),
            apkUrl = apk ?: throw IllegalStateException("aucun APK dans la release")
        )
    }

    /** v0.5.1 / 0.5.1 -> liste de nombres ; compare sans dependre du prefixe. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = numbers(latest)
        val b = numbers(current)
        if (a.isEmpty() || b.isEmpty()) return latest != current
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun versionOf(tag: String): String =
        Regex("""v?(\d+(?:\.\d+)*)""").find(tag)?.groupValues?.get(1) ?: tag

    private fun numbers(v: String): List<Int> =
        Regex("""\d+""").findAll(v).mapNotNull { it.value.toIntOrNull() }.toList()
}
