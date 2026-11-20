package com.example.msgsaver

import android.content.Context
import android.graphics.Bitmap
import android.os.FileObserver
import kotlinx.coroutines.*
import java.io.File

/**
 * View-once media ko turant pakadta hai.
 * "Private" folders mein sirf view-once media aata hai, isliye folder khud hi signal hai.
 */
object ViewOnceWatcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val observers = mutableListOf<FileObserver>()
    private var started = false

    private val TYPES = listOf("WhatsApp Images", "WhatsApp Video")

    @Synchronized
    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        for ((_, mediaRoot) in directRoots()) {
            for (t in TYPES) {
                val parent = File(mediaRoot, t)
                if (!parent.isDirectory) continue
                val priv = File(parent, "Private")
                if (priv.isDirectory) attach(app, priv, parent)

                // "Private" folder lazily banta hai -> parent par nazar rakho
                val parentObs = object : FileObserver(parent, CREATE or MOVED_TO) {
                    override fun onEvent(event: Int, name: String?) {
                        if (name == "Private" && priv.isDirectory) attach(app, priv, parent)
                    }
                }
                runCatching { parentObs.startWatching() }
                observers += parentObs
            }
        }
    }

    private fun attach(ctx: Context, dir: File, parent: File) {
        val obs = object : FileObserver(dir, CREATE or MOVED_TO) {
            override fun onEvent(event: Int, name: String?) {
                name ?: return
                val f = File(dir, name)
                if (!f.isFile) return
                scope.launch { runCatching { Scanner.captureViewOnce(ctx, f, parent) } }
            }
        }
        runCatching { obs.startWatching() }
        observers += obs
    }

    @Synchronized
    fun stop() {
        observers.forEach { runCatching { it.stopWatching() } }
        observers.clear()
        started = false
    }
}

/**
 * Notification sirf trigger hai. Asli file Private folder se aati hai.
 * Fallback (notification preview) sirf tab jab na view-once file mile, na normal photo aayi ho.
 */
object Armer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var until = 0L
    @Volatile private var lastHit = 0L
    @Volatile private var lastNotifSave = 0L

    fun arm(ttlMs: Long = 90_000L) { until = System.currentTimeMillis() + ttlMs }
    fun isArmed() = System.currentTimeMillis() < until
    fun hit() { lastHit = System.currentTimeMillis() }

    fun armWithBitmap(ctx: Context, bmp: Bitmap?) {
        val app = ctx.applicationContext
        // User fallback band kar sakta hai (Settings)
        if (!Prefs.get(app).getBoolean("notif_fallback", true)) return

        val t = System.currentTimeMillis()
        arm()
        if (bmp == null) return

        scope.launch {
            delay(3000)

            // 1) View-once file mil gayi -> fallback ki zaroorat nahi
            if (lastHit >= t) return@launch

            // 2) Normal photo aayi (regular folder mein nayi file) -> yeh view-once nahi hai
            if (Scanner.regularImageSince(app, t)) return@launch

            // 3) WhatsApp ne same notification dobara bheji -> duplicate na banao
            if (System.currentTimeMillis() - lastNotifSave < 2000L) return@launch
            lastNotifSave = System.currentTimeMillis()

            saveBitmap(app, bmp)
        }
    }

    private suspend fun saveBitmap(ctx: Context, bmp: Bitmap) {
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(ctx.filesDir, "backup/viewonce").apply { mkdirs() }
                val key = "notif_${System.currentTimeMillis()}.jpg"
                val out = File(dir, key)
                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                if (out.length() == 0L) { out.delete(); return@runCatching }
                AppDb.get(ctx).dao().insertFile(
                    BackupFile(
                        category = "viewonce", name = key, path = out.absolutePath,
                        origModified = System.currentTimeMillis(), savedAt = System.currentTimeMillis(),
                        size = out.length()
                    )
                )
                hit()
            }
        }
    }
}
