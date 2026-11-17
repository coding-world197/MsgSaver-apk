package com.example.msgsaver

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val versionCode: Long, val name: String, val url: String, val notes: String)

/** Checks the latest GitHub Release of this project. Release tags look like "v2", "v3" ... */
object Updater {
    // Change this if your GitHub username or repository name is different
    const val REPO = "coding-world197/MSG-server"

    suspend fun latest(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            if (c.responseCode != 200) return@withContext null
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val tag = j.getString("tag_name")
            val code = tag.filter { it.isDigit() }.toLongOrNull() ?: return@withContext null
            var url = j.optString("html_url")
            val assets = j.optJSONArray("assets")
            if (assets != null && assets.length() > 0) {
                url = assets.getJSONObject(0).getString("browser_download_url")
            }
            UpdateInfo(code, j.optString("name", tag), url, j.optString("body"))
        } catch (e: Exception) {
            null
        }
    }
}

fun currentVersionCode(ctx: Context): Long =
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

fun currentVersionName(ctx: Context): String =
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
