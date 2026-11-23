package com.example.msgsaver

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * In-app browser. Link khulta hai, player jo real CDN link maangta hai wo pakadta hai,
 * phir download karta hai. Private post ho to user yahan login kar sakta hai.
 */
class ReelWebActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var dl: Button

    private val videos = CopyOnWriteArraySet<String>()
    private val images = CopyOnWriteArraySet<String>()
    private val ui = Handler(Looper.getMainLooper())

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
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url.toString()
                if (u.contains(".mp4")) addVideo(u)
                return null
            }

            override fun onPageFinished(v: WebView, u: String) {
                super.onPageFinished(v, u)
                probe()
                ui.postDelayed({ probe() }, 1500)
                ui.postDelayed({ probe() }, 3500)
                refresh()
            }
        }

        web.loadUrl(url)
    }

    private fun addVideo(u: String) {
        if (u.startsWith("blob:") || u.startsWith("data:")) return
        if (videos.add(u)) ui.post { refresh() }
    }

    private fun probe() {
        web.evaluateJavascript(PROBE_JS) { result -> parseProbe(result) }
    }

    private fun parseProbe(result: String?) {
        if (result.isNullOrBlank() || result == "null") return
        try {
            val unquoted =
                if (result.startsWith("\"") && result.endsWith("\""))
                    JSONTokener(result).nextValue() as String
                else result
            val arr = JSONArray(unquoted)
            for (i in 0 until arr.length()) {
                val u = arr.optString(i)
                if (u.isEmpty()) continue
                if (u.contains(".mp4")) videos.add(u) else images.add(u)
            }
            refresh()
        } catch (e: Exception) { }
    }

    private fun refresh() {
        dl.isEnabled = videos.isNotEmpty() || images.isNotEmpty()
        status.text = when {
            videos.isNotEmpty() -> "${videos.size} video mila. Download dabao."
            images.isNotEmpty() -> "${images.size} photo mila. Download dabao."
            else -> "Video load ho raha hai... play dabao"
        }
    }

    private fun save() {
        val list = videos.map { it to true } + images.map { it to false }
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
                if (!pull(mediaUrl, out, cookie) || out.length() == 0L) {
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

            ui.post {
                Toast.makeText(this@ReelWebActivity, "$ok saved", Toast.LENGTH_SHORT).show()
                setResult(Activity.RESULT_OK, Intent())
                finish()
            }
        }
    }

    private fun pull(url: String, out: File, cookie: String): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        )
        c.setRequestProperty("Referer", "https://www.instagram.com/")
        if (cookie.isNotEmpty()) c.setRequestProperty("Cookie", cookie)
        if (c.responseCode !in 200..299) false
        else {
            c.inputStream.use { i -> out.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
            true
        }
    } catch (e: Exception) { false }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val PROBE_JS = """
            (function(){
              var out = [];
              document.querySelectorAll('video').forEach(function(v){
                if (v.src) out.push(v.src);
                v.querySelectorAll('source').forEach(function(s){ if (s.src) out.push(s.src); });
              });
              var vg = document.querySelector('meta[property="og:video"]');
              if (vg && vg.content) out.push(vg.content);
              var im = document.querySelector('meta[property="og:image"]');
              if (im && im.content) out.push(im.content);
              return JSON.stringify(out);
            })()
        """
    }
}
