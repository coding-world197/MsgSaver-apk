package com.example.msgsaver

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

sealed class ReelResult {
    data class Ok(val files: List<BackupFile>) : ReelResult()
    data class Error(val message: String) : ReelResult()
}

/** Instagram-only downloader powered by InterAPI. No app-owned backend is required. */
object ReelDownloader {
    private const val TAG = "REEL"
    private const val API_ENDPOINT = "https://api.interapi.dev/api/v1/instagram"
    private const val UA = "MsgSaver/4.2 (Android)"

    private data class Media(val url: String, val isVideo: Boolean, val qualityArea: Long)

    suspend fun download(ctx: Context, rawUrl: String): ReelResult = withContext(Dispatchers.IO) {
        val postUrl = rawUrl.trim()
        if (!isValidInstagramUrl(postUrl)) {
            return@withContext ReelResult.Error("Invalid Instagram link. Public Reel, post ya carousel ka poora link paste karo.")
        }

        val apiKey = BuildConfig.INTERAPI_KEY.trim()
        if (apiKey.isBlank() || apiKey == "YOUR_INTERAPI_KEY") {
            return@withContext ReelResult.Error(
                "InterAPI key configure nahi hai. Project ke local.properties mein INTERAPI_KEY add karo, phir app rebuild karo."
            )
        }

        val response = requestMedia(postUrl, apiKey)
        if (response.first !in 200..299 || response.second == null) {
            return@withContext ReelResult.Error(apiError(response.first, response.second))
        }

        val media = parseMedia(response.second!!)
        if (media.isEmpty()) {
            return@withContext ReelResult.Error("InterAPI ne is post ke liye downloadable media nahi diya. Link public aur available hona chahiye.")
        }

        val dir = File(ctx.filesDir, "backup/reel").apply { mkdirs() }
        val dao = AppDb.get(ctx).dao()
        val saved = mutableListOf<BackupFile>()
        var failed = 0

        // Keep API order (carousel slide order) and save every returned slide.
        for (item in media) {
            val file = saveMedia(item.url, dir, item.isVideo)
            if (file == null) {
                failed++
                continue
            }
            val now = System.currentTimeMillis()
            val backup = BackupFile(
                category = "reel",
                name = file.name,
                path = file.absolutePath,
                origModified = now,
                savedAt = now,
                size = file.length(),
                isBiz = false
            )
            dao.insertFile(backup)
            saved += backup
        }

        Probe.log(ctx, "InterAPI Instagram saved=${saved.size} failed=$failed total=${media.size}")
        when {
            saved.isEmpty() -> ReelResult.Error("Media mil gaya lekin file download nahi hui. Dobara try karo; API ke media links expire bhi ho sakte hain.")
            failed > 0 -> ReelResult.Ok(saved)
            else -> ReelResult.Ok(saved)
        }
    }

    private fun isValidInstagramUrl(value: String): Boolean = try {
        val u = URL(value)
        u.protocol == "https" && (u.host == "instagram.com" || u.host.endsWith(".instagram.com")) &&
            (u.path.contains("/p/") || u.path.contains("/reel/") || u.path.contains("/reels/") || u.path.contains("/tv/"))
    } catch (_: Exception) {
        false
    }

    private fun requestMedia(postUrl: String, apiKey: String): Pair<Int, String?> {
        var connection: HttpURLConnection? = null
        return try {
            val encoded = URLEncoder.encode(postUrl, Charsets.UTF_8.name())
            connection = (URL("$API_ENDPOINT?url=$encoded").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("X-API-Key", apiKey)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", UA)
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }
            Log.d(TAG, "InterAPI HTTP $status bodyLength=${body?.length ?: 0}")
            status to body
        } catch (e: Exception) {
            Log.e(TAG, "InterAPI request failed", e)
            -1 to null
        } finally {
            connection?.disconnect()
        }
    }

    /** InterAPI may return several quality variants for the same carousel slide.
     * Keep the largest resolution for each media URL path, preserving slide order.
     */
    
private fun parseMedia(body: String): List<Media> {
    return try {
        val json = JSONObject(body)

        if (json.optString("status").equals("error", ignoreCase = true)) {
            emptyList()
        } else {
            val array = json.optJSONArray("media") ?: return emptyList()
            val bestBySlide = LinkedHashMap<String, Media>()

            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue

                val rawUrl = item.optString("url")
                    .takeIf { it.startsWith("https://") }
                    ?: item.optString("download_url")
                        .takeIf { it.startsWith("https://") }
                    ?: continue

                val type = item.optString("type").lowercase()
                val isVideo = type == "video"

                if (!isVideo && type != "photo" && type != "image") continue

                val quality = item.optString("quality")
                val match = Regex(
                    "(\\d+)\\s*x\\s*(\\d+)",
                    RegexOption.IGNORE_CASE
                ).find(quality)

                val area = if (match != null) {
                    match.groupValues[1].toLongOrNull().orZero() *
                        match.groupValues[2].toLongOrNull().orZero()
                } else {
                    Regex("(\\d{3,4})p")
                        .find(quality)
                        ?.groupValues?.get(1)
                        ?.toLongOrNull() ?: 0L
                }

                val pathKey = try {
                    URL(rawUrl).path
                } catch (_: Exception) {
                    rawUrl.substringBefore('?')
                }

                val key = "$type:$pathKey"
                val candidate = Media(rawUrl, isVideo, area)
                val previous = bestBySlide[key]

                if (previous == null || candidate.qualityArea > previous.qualityArea) {
                    bestBySlide[key] = candidate
                }
            }

            bestBySlide.values.toList()
        }
    } catch (e: Exception) {
        Log.e(TAG, "InterAPI response parse failed", e)
        emptyList()
    }
}


    private fun apiError(status: Int, body: String?): String {
        val detail = try { JSONObject(body.orEmpty()).optString("message") } catch (_: Exception) { "" }
        return when (status) {
            -1 -> "Network error. Internet connection check karke dobara try karo."
            400 -> "Instagram link invalid ya unsupported hai."
            401 -> "InterAPI key missing ya invalid hai. local.properties mein key check karo."
            402 -> "InterAPI credits khatam ho gaye hain. Dashboard mein balance check karo."
            403 -> "Ye post private hai ya account disabled hai. Sirf public Instagram posts supported hain."
            404 -> "Instagram post nahi mili. Ho sakta hai delete ho chuki ho."
            429 -> "InterAPI rate limit hit ho gayi. Kuch der baad try karo."
            in 500..599 -> "InterAPI server temporarily unavailable hai. Thodi der baad try karo."
            else -> detail.takeIf { it.isNotBlank() } ?: "Instagram media fetch nahi ho saka (HTTP $status)."
        }
    }

    private fun saveMedia(url: String, dir: File, isVideo: Boolean): File? {
        var connection: HttpURLConnection? = null
        val temp = File(dir, "tmp_${UUID.randomUUID().toString().take(8)}")
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 90_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "*/*")
            }
            val status = connection.responseCode
            if (status !in 200..299) return null
            val contentType = connection.contentType?.lowercase().orEmpty()
            if (contentType.startsWith("text/")) return null

            val expected = connection.contentLengthLong
            var written = 0L
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        written += count
                    }
                    output.flush()
                }
            }
            if (written <= 0L || (expected > 0L && written != expected)) return null

            val header = ByteArray(16)
            val length = FileInputStream(temp).use { it.read(header) }
            val extension = signatureExt(header, length) ?: return null
            if (isVideo && extension !in setOf("mp4", "webm")) return null
            if (!isVideo && extension !in setOf("jpg", "png", "webp")) return null

            val output = File(dir, "ig_${UUID.randomUUID().toString().take(8)}.$extension")
            if (!temp.renameTo(output)) {
                temp.copyTo(output, overwrite = true)
                temp.delete()
            }
            output
        } catch (e: Exception) {
            Log.w(TAG, "Media download failed: ${e.message}")
            null
        } finally {
            temp.delete()
            connection?.disconnect()
        }
    }

    private fun signatureExt(head: ByteArray, len: Int): String? {
        fun at(i: Int) = if (i < len) head[i].toInt() and 0xFF else -1
        if (at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) return "jpg"
        if (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47) return "png"
        if (len >= 8 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp") return "mp4"
        if (len >= 12 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WEBP") return "webp"
        if (at(0) == 0x1A && at(1) == 0x45) return "webm"
        return null
    }
}
