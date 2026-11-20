package com.example.msgsaver

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

object Prefs {
    fun get(c: Context) = c.getSharedPreferences("prefs", Context.MODE_PRIVATE)
}

/** True when the user switched on "All files access" for this app (Android 11+). */
fun hasAllFiles(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

/** WhatsApp / WhatsApp Business media folders that can be read directly (needs All files access). */
fun directRoots(): List<Pair<Boolean, File>> {
    if (!hasAllFiles()) return emptyList()
    val base = Environment.getExternalStorageDirectory()
    val out = mutableListOf<Pair<Boolean, File>>()
    val wa = listOf("Android/media/com.whatsapp/WhatsApp/Media", "WhatsApp/Media")
    val biz = listOf("Android/media/com.whatsapp.w4b/WhatsApp Business/Media", "WhatsApp Business/Media")
    wa.map { File(base, it) }.firstOrNull { it.isDirectory && it.canRead() }?.let { out.add(false to it) }
    biz.map { File(base, it) }.firstOrNull { it.isDirectory && it.canRead() }?.let { out.add(true to it) }
    return out
}

/** Folder picked by the user through the system folder picker (fallback method). */
fun safRoot(ctx: Context): DocumentFile? {
    val s = Prefs.get(ctx).getString("tree", null) ?: return null
    return DocumentFile.fromTreeUri(ctx, Uri.parse(s))?.takeIf { it.canRead() }
}

private interface Node {
    val name: String
    val isDir: Boolean
    val modified: Long
    val length: Long
    fun children(): List<Node>
    fun child(name: String): Node?
    fun open(ctx: Context): InputStream?
}

private class FNode(val f: File) : Node {
    override val name: String get() = f.name
    override val isDir: Boolean get() = f.isDirectory
    override val modified: Long get() = f.lastModified()
    override val length: Long get() = f.length()
    override fun children(): List<Node> = f.listFiles()?.map { FNode(it) } ?: emptyList()
    override fun child(name: String): Node? = File(f, name).takeIf { it.exists() }?.let { FNode(it) }
    override fun open(ctx: Context): InputStream? = try { f.inputStream() } catch (e: Exception) { null }
}

private class DNode(val d: DocumentFile) : Node {
    override val name: String get() = d.name ?: ""
    override val isDir: Boolean get() = d.isDirectory
    override val modified: Long get() = d.lastModified()
    override val length: Long get() = d.length()
    override fun children(): List<Node> = d.listFiles().map { DNode(it) }
    override fun child(name: String): Node? = d.findFile(name)?.let { DNode(it) }
    override fun open(ctx: Context): InputStream? =
        try { ctx.contentResolver.openInputStream(d.uri) } catch (e: Exception) { null }
}

/** Copies new Status, Voice-note and view-once files from WhatsApp's media folder into app-private storage. */
object Scanner {
    private val mutex = Mutex()
    private val PRIVATE_TYPES = listOf("WhatsApp Images", "WhatsApp Video")

    /** Returns number of newly saved files. */
    suspend fun run(ctx: Context): Int = mutex.withLock {
        withContext(Dispatchers.IO) { doScan(ctx.applicationContext) }
    }

    /** FileObserver se aane wali call (instant capture, koi 3s wait nahi). */
    suspend fun captureViewOnce(ctx: Context, file: File, parent: File) {
        val biz = parent.absolutePath.contains("w4b")
        saveViewOnce(ctx.applicationContext, AppDb.get(ctx).dao(), FNode(file), biz)
    }

    private suspend fun doScan(ctx: Context): Int {
        val prefs = Prefs.get(ctx)
        val roots = mutableListOf<Pair<Boolean, Node>>()
        directRoots().forEach { (biz, f) -> roots.add(biz to FNode(f)) }
        if (roots.isEmpty()) {
            val saf = safRoot(ctx)
            if (saf != null) {
                val isBiz = prefs.getString("tree", "")?.contains("w4b") == true
                roots.add(isBiz to DNode(saf))
            }
        }
        if (roots.isEmpty()) return 0

        if (!prefs.contains("enabled_at")) prefs.edit().putLong("enabled_at", System.currentTimeMillis()).apply()
        val since = prefs.getLong("enabled_at", 0L) - 60 * 60 * 1000
        val dao = AppDb.get(ctx).dao()
        var saved = 0

        for ((biz, root) in roots) {
            // Statuses (only ones already downloaded by WhatsApp, i.e. viewed)
            val status = if (root.name == ".Statuses") root else root.child(".Statuses")
            status?.children()?.forEach { f ->
                if (!f.isDir && save(ctx, dao, "status", f, biz)) saved++
            }

            // Voice notes: only those newer than when monitoring was enabled
            val voice = if (root.name == "WhatsApp Voice Notes") root else root.child("WhatsApp Voice Notes")
            voice?.children()?.forEach { entry ->
                if (entry.isDir) {
                    entry.children().forEach { f ->
                        if (!f.isDir && f.modified >= since && save(ctx, dao, "voice", f, biz)) saved++
                    }
                } else if (entry.modified >= since && save(ctx, dao, "voice", entry, biz)) {
                    saved++
                }
            }

            // View-once (Private folders) — observer miss ho jaye to yeh backup sweep utha lega
            for (t in PRIVATE_TYPES) {
                val priv = root.child(t)?.child("Private") ?: continue
                priv.children().forEach { f ->
                    if (!f.isDir && saveViewOnce(ctx, dao, f, biz)) saved++
                }
            }
        }
        return saved
    }

    private suspend fun save(ctx: Context, dao: AppDao, cat: String, f: Node, biz: Boolean): Boolean {
        val name = f.name
        if (name.isEmpty() || name.startsWith(".nomedia")) return false
        // skip files still being written
        if (f.modified > 0 && System.currentTimeMillis() - f.modified < 3000) return false
        val key = if (biz) "biz_$name" else name
        if (dao.fileExists(cat, key) > 0) return false

        val dir = File(ctx.filesDir, "backup/$cat").apply { mkdirs() }
        val out = File(dir, key)
        val ok = try {
            f.open(ctx)?.use { i ->
                out.outputStream().use { o -> i.copyTo(o) }
                true
            } ?: false
        } catch (e: Exception) { false }
        if (!ok || out.length() == 0L) { out.delete(); return false }

        dao.insertFile(
            BackupFile(
                category = cat, name = key, path = out.absolutePath,
                origModified = f.modified, savedAt = System.currentTimeMillis(), size = out.length(),
                isBiz = biz
            )
        )
        return true
    }

    /**
     * View-once ke liye alag path: 3 second ka wait nahi, copy retry loop.
     * WhatsApp yeh file view karte hi delete kar deta hai, isliye turant copy zaroori hai.
     */
    private suspend fun saveViewOnce(ctx: Context, dao: AppDao, f: Node, biz: Boolean): Boolean {
        val name = f.name
        if (name.isEmpty() || name.startsWith(".nomedia")) return false
        val key = "${f.modified}_${if (biz) "biz_" else ""}$name"
        if (dao.fileExists("viewonce", key) > 0) return false

        val dir = File(ctx.filesDir, "backup/viewonce").apply { mkdirs() }
        val out = File(dir, key)
        var copied = 0L
        for (attempt in 0 until 6) {
            try {
                f.open(ctx)?.use { i -> out.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
                copied = out.length()
                if (copied > 0 && copied == f.length) break
            } catch (_: Exception) { }
            if (copied > 0 && attempt >= 3) break
            delay(120L * (attempt + 1))
        }
        if (copied == 0L) { out.delete(); return false }

        dao.insertFile(
            BackupFile(
                category = "viewonce", name = key, path = out.absolutePath,
                origModified = f.modified, savedAt = System.currentTimeMillis(), size = copied,
                isBiz = biz
            )
        )
        Armer.hit()
        return true
    }
}
