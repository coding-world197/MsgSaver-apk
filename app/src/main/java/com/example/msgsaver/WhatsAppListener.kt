package com.example.msgsaver

import android.app.Notification
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

    // Notification text that means "media only" — view-once ke liye trigger
    private val mediaPhrases = setOf("photo", "video", "voice message")

    private fun isDeletedText(text: String): Boolean {
        val t = text.lowercase()
        if (deletedPhrases.any { t.contains(it.lowercase()) }) return true
        val custom = Prefs.get(applicationContext).getString("custom_deleted", "")?.trim().orEmpty()
        return custom.isNotEmpty() && t.contains(custom.lowercase())
    }

    override fun onListenerConnected() {
        ViewOnceWatcher.start(applicationContext)
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
        loop?.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in packages) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = n.extras
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        // View-once detect: media notification mein sirf "Photo" / "Video" / "Voice message" aata hai
        if (text != null && text.trim().lowercase() in mediaPhrases) {
            val bmp = extras.getParcelable(Notification.EXTRA_PICTURE) as? android.graphics.Bitmap
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

    override fun onDestroy() {
        ViewOnceWatcher.stop()
        scope.cancel()
        super.onDestroy()
    }
}
