package com.example.msgsaver

import android.app.Notification
import android.graphics.Bitmap
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.*

class WhatsAppListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private val packages = setOf("com.whatsapp", "com.whatsapp.w4b")

    // Text WhatsApp shows when a message is deleted for everyone (add your language in Settings)
    private val deletedPhrases = listOf(
        "this message was deleted",
        "message was deleted",
        "यह मैसेज डिलीट किया गया था"
    )

    private fun isDeletedText(text: String): Boolean {
        val t = text.lowercase()
        if (deletedPhrases.any { t.contains(it.lowercase()) }) return true
        val custom = Prefs.get(applicationContext).getString("custom_deleted", "")?.trim().orEmpty()
        return custom.isNotEmpty() && t.contains(custom.lowercase())
    }

    override fun onListenerConnected() {
        ViewOnceWatcher.start(applicationContext)
        Probe.start(applicationContext)
        // Background loop: copy new status / voice / view-once files every 30 seconds
        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                try { Scanner.run(applicationContext) } catch (_: Exception) {}
                delay(30_000)
            }
        }
    }

    override fun onListenerDisconnected() {
        ViewOnceWatcher.stop()
        Probe.stop()
        loop?.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in packages) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = n.extras
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        val bmp = firstBitmap(extras)
        val looksMedia = bmp != null ||
            text?.contains("photo", true) == true ||
            text?.contains("video", true) == true ||
            text?.contains("voice", true) == true

        Probe.log(applicationContext, "NOTIF text=${text?.take(40)} bmp=${bmp != null}")

        if (looksMedia) {
            Armer.arm()
            Armer.armWithBitmap(applicationContext, bmp)
        }

        if (text == null) return
        if (sender == "WhatsApp" || text.matches(Regex("\\d+ new messages.*"))) return

        val dao = AppDb.get(applicationContext).dao()
        scope.launch {
            if (isDeletedText(text)) {
                dao.markLatestDeleted(sender)
            } else {
                val now = System.currentTimeMillis()
                if (dao.recentDuplicates(sender, text, now - 60_000) == 0) {
                    dao.insert(SavedMessage(sender = sender, text = text, timestamp = sbn.postTime))
                }
            }
        }
    }

    /** Android 12+ image EXTRA_PICTURE mein nahi, reduced.images mein deta hai. */
    private fun firstBitmap(extras: Bundle): Bitmap? {
        @Suppress("DEPRECATION")
        (extras.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap)?.let { return it }
        extras.getParcelableArray(Notification.EXTRA_REDUCED_IMAGES)?.forEach { p ->
            if (p is Bitmap) return p
        }
        return null
    }

    override fun onDestroy() {
        ViewOnceWatcher.stop()
        Probe.stop()
        scope.cancel()
        super.onDestroy()
    }
}
