package com.example.msgsaver

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

sealed class ReelResult {
    data class Ok(val file: BackupFile) : ReelResult()
    data class Error(val message: String) : ReelResult()
}

/**
 * Downloads a public Instagram Reel/Post video from its share link.
 * Only works for PUBLIC accounts - Instagram does not expose private content this way.
 * Uses Instagram's own public "embed" page, since the regular post page usually needs
 * JavaScript (and often a login) to render, while the embed page stays mostly static HTML.
 * This can still stop working if Instagram changes that page's structure.
 */
object ReelDownloader {

    private val SHORTCODE = Regex("instagram\\.com/(?:reel|p|tv)/([A-Za-z0-9_-]+)")

    private val VIDEO_URL_PATTERNS = listOf(
        Regex("\"video_url\":\"(https:[^\"]+?)\""),
        Regex("property=\"og:video(?::secure_url)?\" content=\"([^\"]+)\""),
        Regex("contentUrl\"\\s*:\\s*\"(https:[^\"]+?)\""),
        Regex("<video[^>]+src=\"([^\"]+)\"")
    )

    suspend fun download(ctx: Context, rawUrl: String): ReelResult = withContext(Dispatchers.IO) {
        val url = rawUrl.trim()
        if (!url.contains("instagram.com")) {
            return@withContext ReelResult.Error("That doesn't look like an Instagram link.")
        }
        val shortcode = SHORTCODE.find(url)?.groupValues?.get(1)
        try {
            // 1) Try the embed page first - this is the page most "paste link" tools use,
            // since it stays mostly static HTML even without logging in.
            val embedUrls = listOfNotNull(
                shortcode?.let { "https://www.instagram.com/reel/$it/embed/captioned/" },
                shortcode?.let { "https://www.instagram.com/p/$it/embed/captioned/" },
                shortcode?.let { "https://www.instagram.com/reel/$it/embed/" }
            )
            var html: String? = null
            for (u in embedUrls) { html = fetch(u); if (html != null) break }

            // 2) Fall back to the normal page if the embed page didn't load
            if (html == null) html = fetch(url)

            if (html == null) {
                return@withContext ReelResult.Error(
                    "Couldn't open that link. It may be a private account, or the post was removed."
                )
            }

            val videoUrl = VIDEO_URL_PATTERNS.firstNotNullOfOrNull { it.find(html!!)?.groupValues?.get(1) }
                ?.replace("\\u0026", "&")?.replace("\\/", "/")
                ?: return@withContext ReelResult.Error(
                    "Couldn't find a video on that page. This can happen if the account is private, " +
                        "if it's a photo post rather than a video, or if Instagram changed their page layout."
                )

            val name = "ig_${UUID.randomUUID().toString().take(8)}.mp4"
            val dir = File(ctx.filesDir, "backup/reel").apply { mkdirs() }
            val out = File(dir, name)
            val ok = fetchBinary(videoUrl, out)
            if (!ok || out.length() == 0L) {
                out.delete()
                return@withContext ReelResult.Error("The video link was found but the download failed. Try again.")
            }

            val dao = AppDb.get(ctx).dao()
            val bf = BackupFile(
                category = "reel", name = name, path = out.absolutePath,
                origModified = System.currentTimeMillis(), savedAt = System.currentTimeMillis(),
                size = out.length(), isBiz = false
            )
            dao.insertFile(bf)
            ReelResult.Ok(bf)
        } catch (e: Exception) {
            ReelResult.Error("Something went wrong: ${e.message ?: "unknown error"}")
        }
    }

    private fun fetch(pageUrl: String): String? = try {
        val c = URL(pageUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        )
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().readText()
    } catch (e: Exception) { null }

    private fun fetchBinary(url: String, out: File): Boolean {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        )
        if (c.responseCode !in 200..299) return false
        c.inputStream.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        return true
    }
}
