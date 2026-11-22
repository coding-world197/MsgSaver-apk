package com.example.msgsaver

import android.content.Context
import android.graphics.Bitmap
import android.os.FileObserver
import kotlinx.coroutines.*
import java.io.File

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

            // Naye WhatsApp versions view-once media yahan rakhte hain
            val shared = File(mediaRoot.parentFile, ".Shared")
            if (shared.isDirectory) attachShared(app, shared, mediaRoot)
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

    /** .Shared mein normal media bhi aata hai, isliye sirf armed window mein uthao. */
    private fun attachShared(ctx: Context, dir: File, mediaRoot: File) {
        val obs = object : FileObserver(dir, CREATE or MOVED_TO) {
            override fun onEvent(event: Int, name: String?) {
                name ?: return
                if (!Armer.isArmed()) return
                val f = File(dir, name)
                if (!f.isFile) return
                scope.launch { runCatching { Scanner.captureViewOnce(ctx, f, mediaRoot) } }
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

object Armer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var until = 0L
    @Volatile private var lastHit = 0L
    @Volatile private var lastNotifSave = 0L

    fun arm(ttlMs: Long = 20_000L) { until = System.currentTimeMillis() + ttlMs }
    fun isArmed() = System.currentTimeMillis() < until
    fun hit() { lastHit = System.currentTimeMillis() }

    fun armWithBitmap(ctx: Context, bmp: Bitmap?) {
        val app = ctx.applicationContext
        val t = System.currentTimeMillis()
        arm()
        if (!Prefs.get(app).getBoolean("notif_fallback", true)) return
        if (bmp == null) return

        scope.launch {
            delay(3000)
            if (lastHit >= t) return@launch
            if (Scanner.regularImageSince(app, t - 5000L)) return@launch
            if (System.currentTimeMillis() - lastNotifSave < 2000L) return@launch
            lastNotifSave = System.currentTimeMillis()

            val hitBefore = lastHit
            val id = saveBitmap(app, bmp) ?: return@launch

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
