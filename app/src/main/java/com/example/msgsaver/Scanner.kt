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
    fun get(c: Context) =
        c.getSharedPreferences("prefs", Context.MODE_PRIVATE)
}

/** True when the user switched on All files access (Android 11+). */
fun hasAllFiles(): Boolean =
    Build.VERSION.SDK_INT >= 30 &&
            Environment.isExternalStorageManager()

/** WhatsApp / WhatsApp Business media folders. */
fun directRoots(): List<Pair<Boolean, File>> {
    if (!hasAllFiles()) return emptyList()

    val base = Environment.getExternalStorageDirectory()
    val out = mutableListOf<Pair<Boolean, File>>()

    val wa = listOf(
        "Android/media/com.whatsapp/WhatsApp/Media",
        "WhatsApp/Media"
    )

    val biz = listOf(
        "Android/media/com.whatsapp.w4b/WhatsApp Business/Media",
        "WhatsApp Business/Media"
    )

    wa.map { File(base, it) }
        .firstOrNull { it.isDirectory && it.canRead() }
        ?.let { out.add(false to it) }

    biz.map { File(base, it) }
        .firstOrNull { it.isDirectory && it.canRead() }
        ?.let { out.add(true to it) }

    return out
}

/** Folder picked by the user through the system folder picker. */
fun safRoot(ctx: Context): DocumentFile? {
    val s = Prefs.get(ctx).getString("tree", null) ?: return null

    return DocumentFile.fromTreeUri(ctx, Uri.parse(s))
        ?.takeIf { it.canRead() }
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

    override fun children(): List<Node> =
        f.listFiles()?.map { FNode(it) } ?: emptyList()

    override fun child(name: String): Node? =
        File(f, name).takeIf { it.exists() }?.let { FNode(it) }

    override fun open(ctx: Context): InputStream? =
        try {
            f.inputStream()
        } catch (e: Exception) {
            null
        }
}

private class DNode(val d: DocumentFile) : Node {
    override val name: String get() = d.name ?: ""
    override val isDir: Boolean get() = d.isDirectory
    override val modified: Long get() = d.lastModified()
    override val length: Long get() = d.length()

    override fun children(): List<Node> =
        d.listFiles().map { DNode(it) }

    override fun child(name: String): Node? =
        d.findFile(name)?.let { DNode(it) }

    override fun open(ctx: Context): InputStream? =
        try {
            ctx.contentResolver.openInputStream(d.uri)
        } catch (e: Exception) {
            null
        }
}

/** Copies new Status, Voice-note and view-once files. */
object Scanner {

    private val mutex = Mutex()

    private val PRIVATE_TYPES = listOf(
        "WhatsApp Images",
        "WhatsApp Video"
    )

    private val IMAGE_EXT = setOf(
        "jpg",
        "jpeg",
        "png",
        "webp"
    )

    /** Returns number of newly saved files. */
    suspend fun run(ctx: Context): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            doScan(ctx.applicationContext)
        }
    }

    /** FileObserver se aane wali call. */
    suspend fun captureViewOnce(
        ctx: Context,
        file: File,
        parent: File
    ) {
        val biz = parent.absolutePath.contains("w4b")

        saveViewOnce(
            ctx.applicationContext,
            AppDb.get(ctx).dao(),
            FNode(file),
            biz
        )
    }

    /**
     * Kya [since] ke baad regular (view-once ke ilawa)
     * koi photo aayi hai?
     *
     * Agar haan -> notification wali image view-once nahi,
     * aam photo hai.
     */
    suspend fun regularImageSince(
        ctx: Context,
        since: Long
    ): Boolean = withContext(Dispatchers.IO) {

        val tolerance = since - 5000L

        for ((_, mediaRoot) in directRoots()) {

            val root = File(mediaRoot, "WhatsApp Images")
            val kids = root.listFiles() ?: continue

            if (
                kids.any {
                    it.isFile &&
                    it.lastModified() >= tolerance &&
                    it.extension.lowercase() in IMAGE_EXT
                }
            ) {
                return@withContext true
            }
        }

        false
    }

    private suspend fun doScan(ctx: Context): Int {
        val prefs = Prefs.get(ctx)

        val roots = mutableListOf<Pair<Boolean, Node>>()

        directRoots().forEach { (biz, f) ->
            roots.add(biz to FNode(f))
        }

        if (roots.isEmpty()) {
            val saf = safRoot(ctx)

            if (saf != null) {
                val isBiz =
                    prefs.getString("tree", "")?.contains("w4b") == true

                roots.add(isBiz to DNode(saf))
            }
        }

        if (roots.isEmpty()) return 0

        if (!prefs.contains("enabled_at")) {
            prefs.edit()
                .putLong("enabled_at", System.currentTimeMillis())
                .apply()
        }

        val since =
            prefs.getLong("enabled_at", 0L) - 60 * 60 * 1000

        val dao = AppDb.get(ctx).dao()

        var saved = 0

        for ((biz, root) in roots) {

            // Statuses
            val status =
                if (root.name == ".Statuses") root
                else root.child(".Statuses")

            status?.children()?.forEach { f ->
                if (!f.isDir && save(ctx, dao, "status", f, biz)) {
                    saved++
                }
            }

            // Voice notes
            val voice =
                if (root.name == "WhatsApp Voice Notes") root
                else root.child("WhatsApp Voice Notes")

            voice?.children()?.forEach { entry ->

                if (entry.isDir) {
                    entry.children().forEach { f ->
                        if (
                            !f.isDir &&
                            f.modified >= since &&
                            save(ctx, dao, "voice", f, biz)
                        ) {
                            saved++
                        }
                    }
                } else if (
                    entry.modified >= since &&
                    save(ctx, dao, "voice", entry, biz)
                ) {
                    saved++
                }
            }

            // View-once private folders
            for (t in PRIVATE_TYPES) {

                val priv = root.child(t)?.child("Private")
                    ?: continue

                priv.children().forEach { f ->
                    if (!f.isDir && saveViewOnce(ctx, dao, f, biz)) {
                        saved++
                    }
                }
            }
        }

        return saved
    }

    private suspend fun save(
        ctx: Context,
        dao: AppDao,
        cat: String,
        f: Node,
        biz: Boolean
    ): Boolean {

        val name = f.name

        if (name.isEmpty() || name.startsWith(".nomedia")) {
            return false
        }

        // Skip files still being written
        if (
            f.modified > 0 &&
            System.currentTimeMillis() - f.modified < 3000
        ) {
            return false
        }

        val key = if (biz) "biz_$name" else name

        if (dao.fileExists(cat, key) > 0) {
            return false
        }

        val dir = File(ctx.filesDir, "backup/$cat")
            .apply { mkdirs() }

        val out = File(dir, key)

        val ok = try {
            f.open(ctx)?.use { i ->
                out.outputStream().use { o ->
                    i.copyTo(o)
                }
                true
            } ?: false
        } catch (e: Exception) {
            false
        }

        if (!ok || out.length() == 0L) {
            out.delete()
            return false
        }

        dao.insertFile(
            BackupFile(
                category = cat,
                name = key,
                path = out.absolutePath,
                origModified = f.modified,
                savedAt = System.currentTimeMillis(),
                size = out.length(),
                isBiz = biz
            )
        )

        return true
    }

    /** File ka asli type header bytes se. Extension par bharosa nahi. */
    private fun sniffExt(head: ByteArray): String? {
        fun at(i: Int) = if (i < head.size) head[i].toInt() and 0xFF else -1
        if (at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) return "jpg"
        if (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E) return "png"
        if (at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46) return "gif"
        if (head.size >= 12 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp") return "mp4"
        if (head.size >= 4 && String(head, 0, 4, Charsets.US_ASCII) == "OggS") return "ogg"
        if (at(0) == 0x1A && at(1) == 0x45) return "webm"
        if (head.size >= 4 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF") return "webp"
        return null
    }

    /**
     * View-once ke liye alag path:
     * 3 second ka wait nahi, copy retry loop, aur type header se.
     */
    private suspend fun saveViewOnce(
        ctx: Context,
        dao: AppDao,
        f: Node,
        biz: Boolean
    ): Boolean {

        val name = f.name

        if (name.isEmpty() || name.startsWith(".nomedia")) {
            return false
        }

        val keyBase =
            "${f.modified}_${if (biz) "biz_" else ""}$name"

        if (dao.fileExists("viewonce", keyBase) > 0) {
            return false
        }

        // Header padho -> asli type
        var ext: String? = null
        runCatching {
            f.open(ctx)?.use { i ->
                val head = ByteArray(16)
                val n = i.read(head)
                if (n > 0) ext = sniffExt(head.copyOf(n))
            }
        }

        val realExt = ext
        if (realExt == null) {
            Probe.log(ctx, "SKIP unknown-type: $name (${f.length}b)")
            return false
        }

        val dir = File(ctx.filesDir, "backup/viewonce")
            .apply { mkdirs() }

        val key = "$keyBase.$realExt"
        val out = File(dir, key)

        var copied = 0L

        for (attempt in 0 until 6) {

            try {
                f.open(ctx)?.use { i ->
                    out.outputStream().use { o ->
                        i.copyTo(o, 64 * 1024)
                    }
                }

                copied = out.length()

                if (copied > 0 && copied == f.length) {
                    break
                }

            } catch (_: Exception) {
            }

            if (copied > 0 && attempt >= 3) {
                break
            }

            delay(120L * (attempt + 1))
        }

        if (copied == 0L) {
            out.delete()
            Probe.log(ctx, "SKIP copy-failed: $name")
            return false
        }

        dao.insertFile(
            BackupFile(
                category = "viewonce",
                name = key,
                path = out.absolutePath,
                origModified = f.modified,
                savedAt = System.currentTimeMillis(),
                size = copied,
                isBiz = biz
            )
        )

        Armer.hit()
        Probe.log(ctx, "SAVED $realExt ${copied}b <- $name")

        return true
    }
}
