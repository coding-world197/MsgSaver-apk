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
 * Asli source Private folder hai. Notification wala preview sirf LAST RESORT hai
 * (jab auto-download off ho). Default OFF — warna Once tab har aam photo se bhar jata hai.
 * ON hone par bhi yeh khud ko theek karta hai: agar baad mein regular photo ya
 * Private file mil jaye to provisional item delete ho jata hai.
 */
object Armer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastHit = 0L
    @Volatile private var lastNotifSave = 0L

    fun hit() { lastHit = System.currentTimeMillis() }

    fun armWithBitmap(ctx: Context, bmp: Bitmap?) {
        val app = ctx.applicationContext
        if (!Prefs.get(app).getBoolean("notif_fallback", false)) return
        if (bmp == null) return

        val t = System.currentTimeMillis()
        scope.launch {
            delay(3000)

            // Private folder se file aa gayi -> preview ki zaroorat nahi
            if (lastHit >= t) return@launch

            // Duplicate notification guard
            if (System.currentTimeMillis() - lastNotifSave < 2000L) return@launch
            lastNotifSave = System.currentTimeMillis()

            val hitBefore = lastHit
            val id = saveBitmap(app, bmp) ?: return@launch

            // 3 minute tak nazar rakho: agar asli file aa jaye to provisional hata do
            repeat(60) {
                delay(3000)
                if (lastHit > hitBefore) { deleteItem(app, id); return@launch }
                if (Scanner.regularImageSince(app, t - 5000L)) { deleteItem(app, id); return@launch }
            }
        }
    }

    private suspend fun saveBitmap(ctx: Context, bmp: Bitmap): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(ctx.filesDir, "backup/viewonce").apply { mkdirs() }
            val key = "notif_${System.currentTimeMillis()}.jpg"
            val out = File(dir, key)
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            if (out.length() == 0L) { out.delete(); return@runCatching null }
            val id = AppDb.get(ctx).dao().insertFile(
                BackupFile(
                    category = "viewonce", name = key, path = out.absolutePath,
                    origModified = System.currentTimeMillis(),
                    savedAt = System.currentTimeMillis(), size = out.length()
                )
            )
            if (id > 0) id else null
        }.getOrNull()
    }

    private suspend fun deleteItem(ctx: Context, id: Long) = withContext(Dispatchers.IO) {
        runCatching {
            val dao = AppDb.get(ctx).dao()
            val f = dao.byId(id) ?: return@runCatching
            File(f.path).delete()
            dao.deleteFile(f)
        }
    }
}
