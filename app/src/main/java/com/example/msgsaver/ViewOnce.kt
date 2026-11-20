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
 * Notification se sirf itna pata chalta hai ke view-once media aa raha hai.
 * Photo ke liye EXTRA_PICTURE fallback: agar 4 second tak folder se kuch nahi mila,
 * to notification wali image save kar do.
 */
object Armer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var until = 0L
    @Volatile var lastHit = 0L

    fun arm(ttlMs: Long = 90_000L) { until = System.currentTimeMillis() + ttlMs }
    fun isArmed() = System.currentTimeMillis() < until
    fun hit() { lastHit = System.currentTimeMillis() }

    fun armWithBitmap(ctx: Context, bmp: Bitmap?) {
        val t = System.currentTimeMillis()
        arm()
        if (bmp == null) return
        scope.launch {
            delay(4000)
            if (lastHit < t) saveBitmap(ctx.applicationContext, bmp)
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
            }
        }
    }
}
