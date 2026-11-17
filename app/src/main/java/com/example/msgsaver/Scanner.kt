package com.example.msgsaver

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

object Prefs {
    fun get(c: Context) = c.getSharedPreferences("prefs", Context.MODE_PRIVATE)
}

/** Copies new Status and Voice-note files from WhatsApp's media folder into app-private storage. */
object Scanner {
    private val mutex = Mutex()

    /** Returns number of newly saved files. */
    suspend fun run(ctx: Context): Int = mutex.withLock {
        withContext(Dispatchers.IO) { doScan(ctx.applicationContext) }
    }

    private suspend fun doScan(ctx: Context): Int {
        val prefs = Prefs.get(ctx)
        val treeStr = prefs.getString("tree", null) ?: return 0
        val root = DocumentFile.fromTreeUri(ctx, Uri.parse(treeStr)) ?: return 0
        if (!root.canRead()) return 0
        val dao = AppDb.get(ctx).dao()
        var saved = 0

        // 1) Statuses (only files you have already viewed in WhatsApp appear here)
        root.findFile(".Statuses")?.listFiles()?.forEach { f ->
            if (f.isFile && save(ctx, dao, "status", f)) saved++
        }

        // 2) Voice notes: only those newer than when monitoring was enabled
        val since = prefs.getLong("enabled_at", System.currentTimeMillis()) - 60 * 60 * 1000
        root.findFile("WhatsApp Voice Notes")?.listFiles()?.forEach { entry ->
            if (entry.isDirectory) {
                entry.listFiles().forEach { f ->
                    if (f.isFile && f.lastModified() >= since && save(ctx, dao, "voice", f)) saved++
                }
            } else if (entry.isFile && entry.lastModified() >= since) {
                if (save(ctx, dao, "voice", entry)) saved++
            }
        }
        return saved
    }

    private suspend fun save(ctx: Context, dao: AppDao, cat: String, f: DocumentFile): Boolean {
        val name = f.name ?: return false
        if (name.startsWith(".nomedia")) return false
        // skip files still being written
        if (f.lastModified() > 0 && System.currentTimeMillis() - f.lastModified() < 3000) return false
        if (dao.fileExists(cat, name) > 0) return false

        val dir = File(ctx.filesDir, "backup/$cat").apply { mkdirs() }
        val out = File(dir, name)
        val ok = try {
            ctx.contentResolver.openInputStream(f.uri)?.use { i ->
                out.outputStream().use { o -> i.copyTo(o) }
                true
            } ?: false
        } catch (e: Exception) { false }
        if (!ok || out.length() == 0L) { out.delete(); return false }

        dao.insertFile(
            BackupFile(
                category = cat, name = name, path = out.absolutePath,
                origModified = f.lastModified(), savedAt = System.currentTimeMillis(), size = out.length()
            )
        )
        return true
    }
}
