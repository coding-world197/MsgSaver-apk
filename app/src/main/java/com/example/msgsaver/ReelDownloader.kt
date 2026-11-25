package com.example.msgsaver

import android.content.Context
import android.util.Log
import android.webkit.CookieManager as WebCookies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.math.BigInteger
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
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
 * Route 1: api/v1/media/{id}/info/    -> JSON
 * Route 2: /p/{code}/embed/captioned/ -> HTML scrape
 *
 * 401/403 se bachne ke liye pehle homepage se cookies li jati hain,
 * aur CDN download par WebView ki cookies bhi bheji jati hain.
 */
object ReelDownloader {

    private const val TAG = "REEL"

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val APP_UA =
        "Instagram 269.0.0.18.75 Android (33/13; 420dpi; 1080x2400; samsung; SM-G991B; o1s; exynos2100; en_US; 458229237)"

    private const val IG_APP_ID = "936619743392459"

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
    private val SRCSET = Regex("srcset=\"([^\"]+)\"")

    private val LOGIN_MARKERS = listOf("accounts/login", "LoginAndSignupPage", "loginForm")

    private val cookieStore = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    @Volatile private var warmed = false

    private data class Media(val url: String, val isVideo: Boolean)

    private sealed class Route {
        data class Ok(val media: List<Media>) : Route()
        data class Fail(val status: Int, val login: Boolean) : Route()
    }

    private fun clean(s: String) = s
        .replace("\\u0026", "&")
        .replace("&amp;", "&")
        .replace("\\/", "/")
        .replace("\\u003d", "=")

    /** WebView me user login kar chuka ho to wahi cookies CDN par bhejo. */
    private fun sessionCookie(): String = try {
        WebCookies.getInstance().getCookie("https://www.instagram.com/").orEmpty()
    } catch (e: Exception) { "" }

    /** Ek baar homepage se cookies (csrftoken/mid) le lo — 401/403 ka bada sabab missing cookies hain. */
    private fun ensureCookies() {
        if (warmed) return
        warmed = true
        try {
            CookieHandler.setDefault(cookieStore)
            val c = URL("https://www.instagram.com/").openConnection() as HttpURLConnection
            prep(c)
            c.instanceFollowRedirects = true
            c.inputStream.use { it.readBytes() }
            c.disconnect()
            Log.d(TAG, "cookies warmed")
        } catch (e: Exception) {
            Log.d(TAG, "warm fail ${e.message}")
        }
    }

    private fun prep(c: HttpURLConnection, ua: String = UA) {
        c.connectTimeout = 12_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", ua)
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        c.setRequestProperty("X-IG-App-ID", IG_APP_ID)
        c.setRequestProperty("Referer", "https://www.instagram.com/")
        c.setRequestProperty("Origin", "https://www.instagram.com")
        c.setRequestProperty("Sec-Fetch-Site", "same-origin")
        c.setRequestProperty("Sec-Fetch-Mode", "navigate")
        c.setRequestProperty("Sec-Fetch-Dest", "document")
        c.setRequestProperty("Upgrade-Insecure-Requests", "1")
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

    /** share/ links ko asli shortcode tak resolve karta hai. */
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
            ?: return@withContext ReelResult.Error("Invalid link. Instagram Reel/Post ka poora URL paste karo.")

        ensureCookies()

        val failures = mutableListOf<Route.Fail>()
        var found: List<Media>? = null

        when (val r = apiRoute(code)) {
            is Route.Ok -> found = r.media
            is Route.Fail -> failures += r
        }
        if (found == null) {
            when (val r = embedRoute(code)) {
                is Route.Ok -> found = r.media
                is Route.Fail -> failures += r
            }
        }

        val list = found
        if (list.isNullOrEmpty()) {
            Probe.log(ctx, "reel fail $code: " + failures.joinToString { "s=${it.status} login=${it.login}" })
            return@withContext ReelResult.Error(reasonFor(failures))
        }

        val dir = File(ctx.filesDir, "backup/reel").apply { mkdirs() }
        val dao = AppDb.get(ctx).dao()
        val saved = mutableListOf<BackupFile>()
        var failed = 0

        // Video pehle. Ek video + ek image = reel ka poster, duplicate mat banao.
        val ordered = list.sortedByDescending { it.isVideo }
        val posterOnly = ordered.count { it.isVideo } == 1 && ordered.count { !it.isVideo } == 1

        for (m in ordered) {
            Log.d(TAG, "dl video=${m.isVideo} ${m.url.take(90)}")
            if (posterOnly && !m.isVideo && saved.any { it.name.endsWith(".mp4") }) {
                Log.d(TAG, "poster skip (video saved)")
                continue
            }
            val file = saveMedia(m.url, dir, m.isVideo)
            if (file == null) { failed++; continue }
            val bf = BackupFile(
                category = "reel", name = file.name, path = file.absolutePath,
                origModified = System.currentTimeMillis(), savedAt = System.currentTimeMillis(),
                size = file.length(), isBiz = false
            )
            dao.insertFile(bf)
            saved += bf
        }

        Probe.log(ctx, "reel ok $code saved=${saved.size} failed=$failed of=${list.size}")

        if (saved.isEmpty()) {
            ReelResult.Error(
                "Media URL mile par download fail hua ($failed/${list.size}). “Search in app” se try karo."
            )
        } else {
            ReelResult.Ok(saved)
        }
    }

    /** Failures se sahi message chunta hai — private sirf tab jab login redirect mile. */
    private fun reasonFor(failures: List<Route.Fail>): String {
        val codes = failures.map { it.status }
        return when {
            failures.any { it.login } -> "Yeh account private hai (login maang raha hai)."
            codes.any { it == 404 } -> "Post delete ho gayi hai ya ID galat hai."
            codes.any { it == 429 } -> "Instagram ne request limit kar di hai. Thodi der baad try karo."
            codes.any { it == 401 || it == 403 } -> "Instagram ne request block ki (403/401). WiFi/data switch karke ya thodi der baad try karo."
            codes.any { it == -1 } -> "Network fail. Internet check karke dobara try karo."
            else -> "Media metadata nahi mila. Instagram ka response badal gaya ho sakta hai — " +
                "private account zaroori nahi. Thodi der baad try karo."
        }
    }

    /* ---------------------------- ROUTE 1: API ---------------------------- */

    private fun apiRoute(code: String): Route {
        val id = shortcodeToId(code) ?: return Route.Fail(404, false)
        var lastStatus = -1
        for (host in listOf("https://i.instagram.com", "https://www.instagram.com")) {
            for (ua in listOf(APP_UA, UA)) {
                val (status, body) = fetchText("$host/api/v1/media/$id/info/", ua, "application/json")
                if (status !in 200..299 || body == null) { lastStatus = status; continue }
                val parsed = parseApi(body)
                if (parsed != null) return Route.Ok(parsed)
                return Route.Fail(status, false)
            }
        }
        return Route.Fail(lastStatus, false)
    }

    private fun parseApi(body: String): List<Media>? {
        return try {
            val items = JSONObject(body).optJSONArray("items") ?: return null
            if (items.length() == 0) return null

            val out = LinkedHashSet<Media>()

            fun bestOf(arr: org.json.JSONArray?): String? {
                arr ?: return null
                var best: String? = null
                var bestArea = -1L
                for (i in 0 until arr.length()) {
                    val v = arr.getJSONObject(i)
                    val u = v.optString("url")
                    if (u.isEmpty()) continue
                    val area = v.optInt("width").toLong() * v.optInt("height").toLong()
                    if (area > bestArea) { bestArea = area; best = u }
                }
                return best
            }

            val item = items.getJSONObject(0)
            Log.d(TAG, "api keys=${item.keys().asSequence().joinToString(",").take(240)}")

            bestOf(item.optJSONArray("video_versions"))?.let { out += Media(it, true) }

            item.optJSONArray("carousel_media")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    bestOf(m.optJSONArray("video_versions"))?.let { out += Media(it, true) }
                    bestOf(m.optJSONArray("image_versions2"))?.let { out += Media(it, false) }
                }
            }
            if (out.isEmpty()) {
                bestOf(item.optJSONArray("image_versions2"))?.let { out += Media(it, false) }
            }

            Log.d(TAG, "api parsed v=${out.count { it.isVideo }} i=${out.count { !it.isVideo }}")

            if (out.isEmpty()) null
            else out.distinctBy { it.url.substringBefore('?') }.take(20).toList()
        } catch (e: Exception) {
            Log.d(TAG, "api parse fail: ${e.message}")
            null
        }
    }

    /* --------------------------- ROUTE 2: EMBED --------------------------- */

    private fun embedRoute(code: String): Route {
        var lastStatus = -1
        var sawLogin = false

        for (attempt in 0..1) {
            for (path in listOf("p", "reel")) {
                val (status, body) = fetchText(
                    "https://www.instagram.com/$path/$code/embed/captioned/", UA, "text/html,*/*;q=0.8"
                )
                if (status !in 200..299 || body == null) { lastStatus = status; continue }

                val login = LOGIN_MARKERS.any { body.contains(it) } && !body.contains("video_url")
                if (login) { sawLogin = true; continue }

                val parsed = parseEmbed(body)
                Log.d(TAG, "embed $path parsed v=${parsed?.count { it.isVideo }} i=${parsed?.count { !it.isVideo }}")
                if (parsed != null) return Route.Ok(parsed)
                lastStatus = status
            }
            if (attempt == 0) { warmed = false; ensureCookies() }
        }
        return Route.Fail(lastStatus, sawLogin)
    }

    private fun parseEmbed(html: String): List<Media>? {
        val out = LinkedHashSet<Media>()

        VIDEO_PATTERNS.forEach { p ->
            p.findAll(html).forEach { out += Media(clean(it.groupValues[1]), true) }
        }

        val bigImages = LinkedHashSet<String>()
        SRCSET.findAll(html).forEach { m ->
            val best = m.groupValues[1].split(',').mapNotNull { part ->
                val bits = part.trim().split(Regex("\\s+"))
                val u = bits.getOrNull(0) ?: return@mapNotNull null
                if (!u.startsWith("https")) return@mapNotNull null
                val w = bits.getOrNull(1)?.removeSuffix("w")?.toIntOrNull() ?: 0
                w to u
            }.maxByOrNull { it.first }
            best?.let { bigImages += clean(it.second) }
        }
        if (bigImages.isEmpty()) {
            IMAGE_PATTERN.findAll(html).forEach { bigImages += clean(it.groupValues[1]) }
        }
        bigImages.forEach { out += Media(it, false) }

        val list = out.distinctBy { it.url.substringBefore('?') }.take(20)
        return list.ifEmpty { null }
    }

    /* ------------------------------ FETCH ------------------------------ */

    /** @return status to body. status -1 = network/exception. */
    private fun fetchText(pageUrl: String, ua: String, accept: String): Pair<Int, String?> {
        var c: HttpURLConnection? = null
        return try {
            c = URL(pageUrl).openConnection() as HttpURLConnection
            prep(c, ua)
            c.instanceFollowRedirects = true
            c.setRequestProperty("Accept", accept)
            val status = c.responseCode
            if (status !in 200..299) {
                Log.d(TAG, "HTTP $status  $pageUrl")
                status to null
            } else {
                val body = c.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "HTTP $status len=${body.length}  $pageUrl")
                status to body
            }
        } catch (e: Exception) {
            Log.d(TAG, "fetch fail ${e.message}  $pageUrl")
            -1 to null
        } finally {
            try { c?.disconnect() } catch (_: Exception) {}
        }
    }

    /** Desktop UA fail ho to app UA se dobara. */
    private fun saveMedia(url: String, dir: File, isVideo: Boolean): File? {
        for (ua in listOf(UA, APP_UA)) {
            val f = saveMediaOnce(url, dir, isVideo, ua)
            if (f != null) return f
        }
        return null
    }

    /**
     * Validation ke bagair kuch save nahi hota:
     * status -> Content-Type -> HTML reject -> poora stream -> Content-Length -> file signature.
     */
    private fun saveMediaOnce(url: String, dir: File, isVideo: Boolean, ua: String): File? {
        var c: HttpURLConnection? = null
        val tmp = File(dir, "tmp_${UUID.randomUUID().toString().take(8)}")
        try {
            c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 90_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", ua)
            c.setRequestProperty("Referer", "https://www.instagram.com/")
            c.setRequestProperty(
                "Accept",
                if (isVideo) "video/mp4,video/*;q=0.9,*/*;q=0.8" else "image/webp,image/*,*/*;q=0.8"
            )
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            c.setRequestProperty("X-IG-App-ID", IG_APP_ID)
            c.setRequestProperty("Range", "bytes=0-")
            sessionCookie().takeIf { it.isNotEmpty() }?.let { c.setRequestProperty("Cookie", it) }

            val status = c.responseCode
            Log.d(TAG, "bin $status $ua ${url.take(80)}")
            if (status !in 200..299) return null

            val ctype = c.contentType?.lowercase().orEmpty()
            Log.d(TAG, "bin type=$ctype len=${c.contentLengthLong}")
            if (ctype.startsWith("text/")) {
                Log.d(TAG, "bin HTML instead of media ($ctype)")
                return null
            }
            if (ctype.isNotEmpty() && !ctype.startsWith("image/") && !ctype.startsWith("video/")) {
                Log.d(TAG, "bin unexpected type $ctype")
                return null
            }

            val expected = c.contentLengthLong
            var written = 0L

            c.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        written += n
                    }
                    output.flush()
                }
            }

            if (written <= 0L) { tmp.delete(); Log.d(TAG, "bin empty"); return null }
            if (expected > 0L && written != expected) {
                tmp.delete(); Log.d(TAG, "bin truncated $written/$expected"); return null
            }

            val head = ByteArray(16)
            val headLen = FileInputStream(tmp).use { it.read(head) }
            val ext = signatureExt(head, headLen) ?: run { tmp.delete(); Log.d(TAG, "bin unknown sig"); return null }

            val typeOk = if (isVideo) ext in setOf("mp4", "webm") else ext in setOf("jpg", "png", "webp")
            if (!typeOk) { tmp.delete(); Log.d(TAG, "bin type mismatch $ext"); return null }

            val out = File(dir, "ig_${UUID.randomUUID().toString().take(8)}.$ext")
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
            Log.d(TAG, "saved $ext ${out.length()}b")
            return out
        } catch (e: Exception) {
            tmp.delete()
            Log.d(TAG, "bin fail ${e.message}")
            return null
        } finally {
            try { c?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun signatureExt(head: ByteArray, len: Int): String? {
        fun at(i: Int) = if (i < len) head[i].toInt() and 0xFF else -1
        if (at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) return "jpg"
        if (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47) return "png"
        if (len >= 8 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp") return "mp4"
        if (len >= 12 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF") return "webp"
        if (at(0) == 0x1A && at(1) == 0x45) return "webm"
        return null
    }
}
