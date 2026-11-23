package com.example.msgsaver

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

sealed class ReelResult {
    data class Ok(val files: List<BackupFile>) : ReelResult()
    data class Error(val message: String) : ReelResult()
}

/**
 * Public Instagram Reel/Post ka media laata hai. Sirf PUBLIC accounts.
 *
 * Route 1: api/v1/media/{id}/info/   -> primary, JSON deta hai
 * Route 2: /p/{code}/embed/captioned/ -> fallback scrape
 *
 * Mobile Chrome UA use MAT karo. Instagram uspar 200 ke saath khaali
 * JS shell bhejta hai, jisme video_url hota hi nahi.
 */
object ReelDownloader {

    private const val TAG = "REEL"

    // Desktop UA - HTML/JSON route ke liye
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    // App UA - api/v1 route ke liye (kuch IPs par desktop UA "useragent mismatch" deta hai)
    private const val APP_UA =
        "Instagram 269.0.0.18.75 Android (33/13; 420dpi; 1080x2400; samsung; SM-G991B; o1s; exynos2100; en_US; 458229237)"

    private const val IG_APP_ID = "936619743392459"

    // reel/..., p/..., share/reel/..., user/reel/..., tv/... sab handle karta hai
    private val SHORTCODE =
        Regex("""instagram\.com/(?:[^/?#]+/)?(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)""")

    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private val VIDEO_PATTERNS = listOf(
        Regex("\"video_url\":\"(https:[^\"]+?)\""),
        Regex("property=\"og:video(?::secure_url)?\" content=\"([^\"]+)\""),
        Regex("<video[^>]+src=\"([^\"]+)\"")
    )
    private val IMAGE_PATTERN = Regex("\"display_url\":\"(https:[^\"]+?)\"")

    private fun clean(s: String) = s.replace("\\u0026", "&").replace("\\/", "/")

    private fun prep(c: HttpURLConnection, ua: String = UA) {
        c.connectTimeout = 12_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", ua)
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        c.setRequestProperty("X-IG-App-ID", IG_APP_ID)
        c.setRequestProperty("Referer", "https://www.instagram.com/")
    }

    private fun shortcodeToId(code: String): String? {
        var n = BigInteger.ZERO
        val b64 = BigInteger.valueOf(64L)
        for (ch in code) {
            val i = ALPHABET.indexOf(ch)
            if (i < 0) return null
            n = n.multiply(b64).add(BigInteger.valueOf(i.toLong()))
        }
        return n.toString()
    }

    /** share/ links ko asli shortcode tak resolve karta hai (manual redirect). */
    private fun locationOf(u: String): String? = try {
        val c = URL(u).openConnection() as HttpURLConnection
        prep(c)
        c.instanceFollowRedirects = false
        if (c.responseCode in 300..399) c.getHeaderField("Location") else null
    } catch (e: Exception) { null }

    private fun resolveCode(url: String): String? {
        val direct = SHORTCODE.find(url)?.groupValues?.get(1)
        if (direct != null && direct.length == 11) return direct

        var current = url
        var hops = 0
        while (hops < 4) {
            val next = locationOf(current) ?: break
            current = if (next.startsWith("http")) next else "https://www.instagram.com$next"
            val code = SHORTCODE.find(current)?.groupValues?.get(1)
            if (code != null && code.length == 11) return code
            hops++
        }
        return direct
    }

    suspend fun download(ctx: Context, rawUrl: String): ReelResult = withContext(Dispatchers.IO) {
        val code = resolveCode(rawUrl.trim())
            ?: return@withContext ReelResult.Error("That doesn't look like an Instagram link.")

        val media = apiMedia(code) ?: embedMedia(code)
            ?: run {
                Probe.log(ctx, "reel fail: $code")
                return@withContext ReelResult.Error(
                    "Media nahi mila. Account private ho sakta hai ya Instagram ne request block ki."
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

    /** Route 1: public media info API. Dono UA try karta hai. */
    private fun apiMedia(code: String): List<Pair<String, Boolean>>? {
        val id = shortcodeToId(code) ?: return null
        val body = listOf(UA, APP_UA).firstNotNullOfOrNull { ua ->
            fetch("https://www.instagram.com/api/v1/media/$id/info/", ua, "application/json")
        } ?: return null

        return try {
            val items = JSONObject(body).optJSONArray("items") ?: return null
            if (items.length() == 0) return null
            val out = LinkedHashSet<Pair<String, Boolean>>()

            fun addVideos(o: JSONObject) {
                o.optJSONArray("video_versions")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.getJSONObject(i).optString("url")
                            .takeIf { it.isNotEmpty() }?.let { out += it to true }
                    }
                }
            }

            fun addImage(o: JSONObject) {
                o.optJSONArray("image_versions2")?.getJSONObject(0)?.optString("url")
                    ?.takeIf { it.isNotEmpty() }?.let { out += it to false }
            }

            val item = items.getJSONObject(0)
            addVideos(item)
            item.optJSONArray("carousel_media")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    addVideos(m)
                    addImage(m)
                }
            }
            if (out.isEmpty()) addImage(item)

            if (out.isEmpty()) null
            else out.distinctBy { it.first.substringBefore('?') }.take(20)
        } catch (e: Exception) {
            Log.d(TAG, "api parse fail: ${e.message}")
            null
        }
    }

    /** Route 2: embed page scrape. /p/ path pehle, kyunki reel par bhi chalta hai. */
    private fun embedMedia(code: String): List<Pair<String, Boolean>>? {
        val html = listOf(
            "https://www.instagram.com/p/$code/embed/captioned/",
            "https://www.instagram.com/p/$code/embed/"
        ).firstNotNullOfOrNull { fetch(it) } ?: return null

        val urls = LinkedHashSet<Pair<String, Boolean>>()
        VIDEO_PATTERNS.forEach { p -> p.findAll(html).forEach { urls += clean(it.groupValues[1]) to true } }
        IMAGE_PATTERN.findAll(html).forEach { urls += clean(it.groupValues[1]) to false }

        val media = urls.distinctBy { it.first.substringBefore('?') }.take(20)
        return media.ifEmpty { null }
    }

    private fun fetch(
        pageUrl: String,
        ua: String = UA,
        accept: String = "text/html,application/xhtml+xml,*/*;q=0.8"
    ): String? = try {
        val c = URL(pageUrl).openConnection() as HttpURLConnection
        prep(c, ua)
        c.setRequestProperty("Accept", accept)
        val status = c.responseCode
        if (status !in 200..299) {
            Log.d(TAG, "HTTP $status  $pageUrl")
            null
        } else {
            val body = c.inputStream.bufferedReader().readText()
            Log.d(TAG, "HTTP $status  len=${body.length}  $pageUrl")
            body
        }
    } catch (e: Exception) {
        Log.d(TAG, "fetch fail ${e.message}  $pageUrl")
        null
    }

    private fun fetchBinary(url: String, out: File): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 45_000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Referer", "https://www.instagram.com/")
        if (c.responseCode !in 200..299) false
        else {
            c.inputStream.use { i -> out.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
            true
        }
    } catch (e: Exception) { false }
}
