package com.gopal.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Where updates come from. Change `updateRepo` in gradle.properties (CI fills it from the repo automatically). */
object UpdateConfig {
    val REPO: String = BuildConfig.UPDATE_REPO
    val isConfigured: Boolean get() = !REPO.startsWith("GITHUB_USERNAME") && REPO.contains('/')
    /** GitHub redirects /releases/latest/download/<asset> to the newest release's asset. */
    fun versionJsonUrl(repo: String = REPO) = "https://github.com/$repo/releases/latest/download/version.json"
}

data class VersionInfo(val versionCode: Int, val versionName: String, val apkUrl: String, val changelog: String) {
    companion object {
        fun parse(json: String): VersionInfo {
            val o = JSONObject(json)
            val code = o.optInt("versionCode", -1)
            require(code > 0) { "version.json has no versionCode" }
            val url = o.optString("apkUrl")
            require(url.startsWith("https://")) { "version.json apkUrl must be https" }
            val log = when (val c = o.opt("changelog")) {
                is org.json.JSONArray -> (0 until c.length()).joinToString("\n") { "• " + c.optString(it) }
                null -> ""
                else -> c.toString()
            }
            return VersionInfo(code, o.optString("versionName", code.toString()), url, log)
        }
    }
}

object VersionCompare {
    /** Only a strictly higher versionCode is an update (versionName is display-only). */
    fun isNewer(remote: VersionInfo?, installedCode: Int): Boolean = remote != null && remote.versionCode > installedCode

    /** For display / sanity: compares "1.2.10" vs "1.2.9" numerically. */
    fun compareNames(a: String, b: String): Int {
        val pa = a.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val pb = b.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}

class Updater(private val http: OkHttpClient = OkHttpClient()) {

    suspend fun fetch(url: String = UpdateConfig.versionJsonUrl()): VersionInfo = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) throw IOException("No release with version.json yet")
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            VersionInfo.parse(r.body?.string().orEmpty())
        }
    }

    /** Downloads to a .part file and renames only when complete, so a half file is never installed. */
    suspend fun download(url: String, dest: File, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        part.delete()
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download failed: HTTP ${r.code}")
            val body = r.body ?: throw IOException("empty download")
            val total = body.contentLength()
            var done = 0L
            body.byteStream().use { inp ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("Download incomplete")
        }
        dest.delete()
        if (!part.renameTo(dest)) throw IOException("Could not save the APK")
        onProgress(1f)
        dest
    }

    companion object {
        fun canInstall(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

        /** Android 8+: the user must allow "Install unknown apps" for this app once. */
        fun openInstallPermission(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        fun install(ctx: Context, apk: File) {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }
}
