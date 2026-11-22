package com.example.msgsaver

import android.content.Context
import android.os.FileObserver
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/** Debug: WhatsApp kaunse folder mein file banata hai, woh log karta hai. */
object Probe {
    private val obs = mutableListOf<FileObserver>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun logPath(ctx: Context) = File(ctx.filesDir, "probe.txt")

    fun start(ctx: Context) {
        if (obs.isNotEmpty()) return
        val app = ctx.applicationContext
        val roots = directRoots()
        log(app, "=== probe start: roots=${roots.size} allFiles=${hasAllFiles()} ===")
        if (roots.isEmpty()) {
            log(app, "!! All files access missing ya WhatsApp folder nahi mila")
            return
        }
        for ((_, mediaRoot) in roots) {
            val waDir = mediaRoot.parentFile ?: continue
            val targets = mutableListOf<File>()
            targets += File(waDir, ".Shared")
            targets += mediaRoot
            mediaRoot.listFiles()?.filter { it.isDirectory }?.forEach { d ->
                targets += d
                d.listFiles()?.filter { it.isDirectory }?.forEach { targets += it }
            }
            targets.forEach { t ->
                if (!t.isDirectory) {
                    log(app, "skip (no dir) ${t.absolutePath}")
                    return@forEach
                }
                attach(app, t)
            }
        }
    }

    private fun attach(ctx: Context, dir: File) {
        val o = object : FileObserver(dir, CREATE or MOVED_TO or CLOSE_WRITE) {
            override fun onEvent(e: Int, name: String?) {
                if (name == null) return
                log(ctx, "${dir.absolutePath}/$name")
            }
        }
        val ok = runCatching { o.startWatching() }.isSuccess
        log(ctx, "watch ${if (ok) "OK" else "FAIL"} ${dir.absolutePath}")
        obs += o
    }

    fun log(ctx: Context, line: String) {
        runCatching {
            val f = logPath(ctx)
            if (f.length() > 300_000) f.delete()
            f.appendText("${fmt.format(Date())}  $line\n")
        }
    }

    fun stop() {
        obs.forEach { runCatching { it.stopWatching() } }
        obs.clear()
    }

    fun read(ctx: Context): String =
        runCatching { logPath(ctx).readText().takeLast(9000) }.getOrDefault("")
}
