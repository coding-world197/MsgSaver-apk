package com.example.msgsaver

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONTokener
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/**
 * In-app browser. Desktop UA par Instagram asli <video src=".mp4"> deta hai.
 * Player ko force play karke mp4 request pakdi jati hai; blob: player ho to
 * blob ke bytes page ke andar hi base64 me nikaal liye jaate hain.
 */
class ReelWebActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var dl: Button

    private val videos = CopyOnWriteArraySet<String>()
    private val images = CopyOnWriteArraySet<String>()
    private val ui = Handler(Looper.getMainLooper())
    private var ticks = 0
    private var hasBlob = false
    private var blobB64: String? = null
    private var blobErr: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val url = intent.getStringExtra("url").orEmpty()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#111111"))
        }
        status = TextView(this).apply {
            text = "Page khul raha hai..."
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        dl = Button(this).apply {
            text = "Download"
            isEnabled = false
            setOnClickListener { save() }
        }
        bar.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(dl)
        root.addView(bar)

        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                // intent:// se Instagram app par bounce rok do
                return r.url.toString().startsWith("intent:")
            }

            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url.toString()
                if (isVideoUrl(u)) addVideo(u)
                return null
            }

            override fun onPageFinished(v: WebView, u: String) {
                super.onPageFinished(v, u)
                ticks = 0
                kick()
            }
        }

        web.loadUrl(url)
    }

    private fun isVideoUrl(u: String) =
        u.contains(".mp4") || u.contains(".m4v") || u.contains(".webm") || u.contains("/v/t2/")

    /** Har 1s: player kick + probe + blob poll. 15 tick tak. */
    private fun kick() {
        if (ticks > 15) return
        ticks++
        web.evaluateJavascript(KICK_JS) { }
        probe()
        if (hasBlob && videos.isEmpty() && blobB64 == null && blobErr == null) {
            web.evaluateJavascript(GRAB_JS) { }
        }
        pollBlob()
        ui.postDelayed({ kick() }, 1000)
    }

    private fun addVideo(u: String) {
        if (u.startsWith("blob:") || u.startsWith("data:")) return
        if (videos.add(u)) ui.post { refresh() }
    }

    private fun probe() {
        web.evaluateJavascript(PROBE_JS) { result -> parseProbe(result) }
    }

    private fun parseProbe(result: String?) {
        val raw = unquote(result) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val u = arr.optString(i)
                if (u.isEmpty()) continue
                when {
                    u.startsWith("blob:") -> hasBlob = true
                    u.startsWith("data:") -> if (blobB64 == null) blobB64 = u
                    isVideoUrl(u) -> videos.add(u)
                    else -> images.add(u)
                }
            }
            refresh()
        } catch (e: Exception) { }
    }

    private fun pollBlob() {
        if (blobB64 != null) return
        web.evaluateJavascript("window.__reelB64 || window.__reelErr || ''") { r ->
            val s = unquote(r) ?: return@evaluateJavascript
            when {
                s.startsWith("data:") -> { blobB64 = s; refresh() }
                s.isNotEmpty() -> {
                    blobErr = s
                    Log.d("REEL", "blob: $s")
                    refresh()
                }
            }
        }
    }

    private fun unquote(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try {
            if (raw.startsWith("\"")) JSONTokener(raw).nextValue() as String else raw
        } catch (e: Exception) { null }
    }

    private fun refresh() {
        val hasVideo = videos.isNotEmpty() || blobB64 != null
        dl.isEnabled = hasVideo
        status.text = when {
            videos.isNotEmpty() -> "${videos.size} video mila. Download dabao."
            blobB64 != null -> "Video mil gaya. Download dabao."
            blobErr != null -> "Video block ho gaya ($blobErr). Player par tap karo."
            hasBlob -> "Video load ho raha hai..."
            images.isNotEmpty() -> "Poster mila, video load ho raha hai..."
            else -> "Video dhoondh rahe hain..."
        }
    }

    private fun save() {
        if (videos.isEmpty() && blobB64 != null) { saveBlob(); return }

        // Video ho to sirf video. Video na ho to carousel photos.
        val list = if (videos.isNotEmpty()) {
            videos.map { it to true }
        } else {
            images.map { it to false }
        }
        val unique = list.distinctBy { it.first.substringBefore('?') }.take(20)
        if (unique.isEmpty()) return

        dl.isEnabled = false
        status.text = "Downloading..."

        CoroutineScope(Dispatchers.IO).launch {
            val dir = File(filesDir, "backup/reel").apply { mkdirs() }
            val dao = AppDb.get(this@ReelWebActivity).dao()
            val cookie = CookieManager.getInstance().getCookie("https://www.instagram.com/") ?: ""
            var ok = 0

            for ((mediaUrl, isVideo) in unique) {
                val name = "ig_${UUID.randomUUID().toString().take(8)}.${if (isVideo) "mp4" else "jpg"}"
                val out = File(dir, name)
                if (!pull(mediaUrl, out, cookie, isVideo) || out.length() == 0L) {
                    out.delete()
                    continue
                }
                dao.insertFile(
                    BackupFile(
                        category = "reel", name = name, path = out.absolutePath,
                        origModified = System.currentTimeMillis(), savedAt = System.currentTimeMillis(),
                        size = out.length(), isBiz = false
                    )
                )
                ok++
            }

            done(ok)
        }
    }

    private fun saveBlob() {
        val data = blobB64 ?: return
        dl.isEnabled = false
        status.text = "Downloading..."

        CoroutineScope(Dispatchers.IO).launch {
            var ok = 0
            try {
                val bytes = Base64.decode(data.substringAfter("base64,", ""), Base64.DEFAULT)
                if (bytes.isNotEmpty()) {
                    val dir = File(filesDir, "backup/reel").apply { mkdirs() }
                    val name = "ig_${UUID.randomUUID().toString().take(8)}.mp4"
                    val out = File(dir, name)
                    out.writeBytes(bytes)
                    AppDb.get(this@ReelWebActivity).dao().insertFile(
                        BackupFile(
                            category = "reel", name = name, path = out.absolutePath,
                            origModified = System.currentTimeMillis(), savedAt = System.currentTimeMillis(),
                            size = out.length(), isBiz = false
                        )
                    )
                    ok = 1
                }
            } catch (e: Exception) {
                Log.d("REEL", "blob decode fail ${e.message}")
            }
            done(ok)
        }
    }

    private fun done(ok: Int) {
        ui.post {
            Toast.makeText(this@ReelWebActivity, "$ok saved", Toast.LENGTH_SHORT).show()
            if (ok > 0) {
                setResult(Activity.RESULT_OK, Intent())
                finish()
            } else {
                refresh()
                status.text = "Download fail. Player par tap karke dobara try karo."
            }
        }
    }

    private fun pull(url: String, out: File, cookie: String, isVideo: Boolean): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 90_000
        c.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        )
        c.setRequestProperty("Referer", "https://www.instagram.com/")
        c.setRequestProperty("Accept", if (isVideo) "video/mp4,video/*,*/*;q=0.8" else "image/*,*/*;q=0.8")
        c.setRequestProperty("X-IG-App-ID", "936619743392459")
        c.setRequestProperty("Range", "bytes=0-")
        if (cookie.isNotEmpty()) c.setRequestProperty("Cookie", cookie)
        val st = c.responseCode
        if (st !in 200..299) {
            Log.d("REEL", "pull HTTP $st")
            false
        } else {
            c.inputStream.use { i -> out.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
            true
        }
    } catch (e: Exception) {
        Log.d("REEL", "pull fail ${e.message}")
        false
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }

    companion object {
        /** Player force play: overlay hatao, video play, poster par tap. */
        private const val KICK_JS = """
            (function(){
              document.querySelectorAll('[aria-label="Close"],[aria-label="Close modal"]').forEach(function(b){ b.click(); });
              document.querySelectorAll('video').forEach(function(v){
                try { v.muted = true; if (v.paused) v.play(); } catch(e){}
              });
              var p = document.querySelector('.EmbeddedMediaImage, img[srcset*="cdninstagram"], [aria-label="Play"]');
              if (p) try { p.click(); } catch(e){}
            })()
        """

        private const val PROBE_JS = """
            (function(){
              var out = [];
              document.querySelectorAll('video').forEach(function(v){
                if (v.src) out.push(v.src);
                if (v.currentSrc) out.push(v.currentSrc);
                v.querySelectorAll('source').forEach(function(s){ if (s.src) out.push(s.src); });
              });
              var vg = document.querySelector('meta[property="og:video"]');
              if (vg && vg.content) out.push(vg.content);
              var im = document.querySelector('meta[property="og:image"]');
              if (im && im.content) out.push(im.content);
              return JSON.stringify(out);
            })()
        """

        /** blob: player ho to bytes page ke andar hi base64 me nikaal lo. */
        private const val GRAB_JS = """
            (function(){
              if (window.__reelB64 || window.__reelBusy) return;
              var v = document.querySelector('video');
              var src = v ? (v.currentSrc || v.src || '') : '';
              if (!src || src.indexOf('blob:') !== 0) return;
              window.__reelBusy = 1;
              var x = new XMLHttpRequest();
              x.open('GET', src, true);
              x.responseType = 'blob';
              x.onload = function(){
                if (x.status !== 200 && x.status !== 0) { window.__reelErr = 'xhr' + x.status; window.__reelBusy = 0; return; }
                var b = x.response;
                if (!b || b.size === 0) { window.__reelErr = 'empty'; window.__reelBusy = 0; return; }
                if (b.size > 30000000) { window.__reelErr = 'big'; window.__reelBusy = 0; return; }
                var fr = new FileReader();
                fr.onload = function(){ window.__reelB64 = fr.result; window.__reelBusy = 0; };
                fr.onerror = function(){ window.__reelErr = 'read'; window.__reelBusy = 0; };
                fr.readAsDataURL(b);
              };
              x.onerror = function(){ window.__reelErr = 'net'; window.__reelBusy = 0; };
              x.send();
            })()
        """
    }
}
