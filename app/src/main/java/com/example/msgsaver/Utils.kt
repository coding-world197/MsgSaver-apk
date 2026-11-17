package com.example.msgsaver

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

fun formatTime(ms: Long): String {
    val now = Calendar.getInstance()
    val t = Calendar.getInstance().apply { timeInMillis = ms }
    val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun same(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(ms))
    return when {
        same(now, t) -> "Today, $time"
        same(y, t) -> "Yesterday, $time"
        else -> SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(ms))
    }
}

fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "mp4" -> "video/mp4"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "opus", "ogg" -> "audio/ogg"
    "m4a" -> "audio/mp4"
    else -> "*/*"
}

fun openFile(ctx: Context, path: String) {
    try {
        val f = File(path)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(f.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "No app found to open this file", Toast.LENGTH_SHORT).show()
    }
}

/** Copies a backed-up status into the phone gallery (Pictures/MsgSaver or Movies/MsgSaver). */
fun saveToGallery(ctx: Context, f: BackupFile): Boolean {
    return try {
        val isVideo = f.name.endsWith(".mp4", true)
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val folder = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(f.name))
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/MsgSaver")
        }
        val uri = ctx.contentResolver.insert(collection, values) ?: return false
        ctx.contentResolver.openOutputStream(uri)?.use { o ->
            File(f.path).inputStream().use { it.copyTo(o) }
        }
        true
    } catch (e: Exception) { false }
}

object VoicePlayer {
    private var mp: MediaPlayer? = null
    var playing by mutableStateOf<String?>(null)
        private set

    fun toggle(path: String) {
        if (playing == path) { stop(); return }
        stop()
        try {
            mp = MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
            playing = path
        } catch (e: Exception) { stop() }
    }

    fun stop() {
        mp?.release(); mp = null; playing = null
    }
}
