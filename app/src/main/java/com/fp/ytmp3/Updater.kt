package com.fp.ytmp3

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

data class UpdateInfo(
    val extractorVersion: Int,
    val extractorUrl: String?,
    val apkVersionCode: Int,
    val apkUrl: String?
)

object Updater {

    private const val UA = "yt-mp3-poc/0.2 ( local )"

    /**
     * Manifeste attendu :
     * {
     *   "extractor": {"version": 2, "url": "https://.../extractor.dex"},
     *   "apk":       {"versionCode": 2, "url": "https://.../app-debug.apk"}
     * }
     */
    fun fetch(manifestUrl: String): UpdateInfo {
        val o = JSONObject(Http.text(manifestUrl, mapOf("User-Agent" to UA)))
        val ex = o.optJSONObject("extractor")
        val apk = o.optJSONObject("apk")
        return UpdateInfo(
            ex?.optInt("version", 0) ?: 0,
            ex?.optString("url")?.takeIf { it.isNotBlank() },
            apk?.optInt("versionCode", 0) ?: 0,
            apk?.optString("url")?.takeIf { it.isNotBlank() }
        )
    }

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
}
