package com.example.msgsaver

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

sealed class ReelResult {
    data class Ok(val files: List<BackupFile>) : ReelResult()
    data class Error(val message: String) : ReelResult()
}

/**
 * Public Instagram Reel/Post ka media laata hai.
 * Sirf PUBLIC accounts. Embed page parse hota hai kyunki normal page JavaScript maangta hai.
 * Instagram markup badle to sirf yeh file theek karni padegi.
 */
object ReelDownloader {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    private val SHORTCODE = Regex("instagram\\.com/(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)")

    private val VIDEO_PATTERNS = listOf(
        Regex("\"video_url\":\"(https:[^\"]+?)\""),
        Regex("property=\"og:video(?::secure_url)?\" content=\"([^\"]+)\""),
        Regex("<video[^>]+src=\"([^\"]+)\"")
    )
    private val IMAGE_PATTERN = Regex("\"display_url\":\"(https:[^\"]+?)\"")

    private fun clean(s: String) = s.replace("\\u0026", "&").replace("\\/", "/")

    suspend fun download(ctx: Context, rawUrl: String): ReelResult = withContext(Dispatchers.IO) {
        val url = rawUrl.trim()
        val shortcode = SHORTCODE.find(url)?.groupValues?.get(1)
            ?: return@withContext ReelResult.Error("That doesn't look like an Instagram link.")

        val html = listOfNotNull(
            "https://www.instagram.com/reel/$shortcode/embed/captioned/",
            "https://www.instagram.com/p/$shortcode/embed/captioned/",
            "https://www.instagram.com/reel/$shortcode/embed/",
            url
        ).firstNotNullOfOrNull { fetch(it) }
            ?: return@withContext ReelResult.Error(
                "Couldn't open that link. It may be a private account, or the post was removed."
            )

        val urls = LinkedHashSet<Pair<String, Boolean>>()
        VIDEO_PATTERNS.forEach { p -> p.findAll(html).forEach { urls += clean(it.groupValues[1]) to true } }
        IMAGE_PATTERN.findAll(html).forEach { urls += clean(it.groupValues[1]) to false }

        val media = urls.distinctBy { it.first.substringBefore('?') }.take(20)
        if (media.isEmpty()) {
            return@withContext ReelResult.Error(
                "Couldn't find media on that page. The account may be private, or Instagram changed the layout."
            )
        }

        val dir = File(ctx.filesDir, "backup/reel").apply { mkdirs() }
        val dao = AppDb.get(ctx).dao()
        val saved = mutableListOf<BackupFile>()
        var failed = 0

        for ((mediaUrl, isVideo) in media) {
            val name = "ig_${UUID.randomUUID().toString().take(8)}.${if (isVideo) "mp4" else "jpg"}"
            val out = File(dir, name)
            if (!fetchBinary(mediaUrl, out) || out.length() == 0L) {
                out.delete(); failed++; continue
            }
            val bf = BackupFile(
                category = "reel", name = name, path = out.absolutePath,
                origModified = System.currentTimeMillis(), savedAt = System.currentTimeMillis(),
                size = out.length(), isBiz = false
            )
            dao.insertFile(bf)
            saved += bf
        }

        if (saved.isEmpty()) {
            ReelResult.Error("Media link mila par download fail hua ($failed). Dobara try karo.")
        } else {
            ReelResult.Ok(saved)
        }
    }

    private fun fetch(pageUrl: String): String? = try {
        val c = URL(pageUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 12_000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().readText()
    } catch (e: Exception) { null }

    private fun fetchBinary(url: String, out: File): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 45_000
        c.setRequestProperty("User-Agent", UA)
        if (c.responseCode !in 200..299) false
        else {
            c.inputStream.use { i -> out.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
            true
        }
    } catch (e: Exception) { false }
}
