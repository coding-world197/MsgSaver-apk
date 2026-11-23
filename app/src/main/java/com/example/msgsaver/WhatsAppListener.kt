package com.example.msgsaver

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.*

class WhatsAppListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private val waPackages = setOf("com.whatsapp", "com.whatsapp.w4b")
    private val igPackage = "com.instagram.android"

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
        // Background loop: copy new status / voice files every 30 seconds
        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                try { Scanner.run(applicationContext) } catch (_: Exception) {}
                delay(30_000)
            }
        }
    }

    override fun onListenerDisconnected() { loop?.cancel() }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val isWa = sbn.packageName in waPackages
        val isIg = sbn.packageName == igPackage
        if (!isWa && !isIg) return

        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = n.extras
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
        if (sender == "WhatsApp" || sender == "Instagram") return
        if (text.matches(Regex("\\d+ new messages.*"))) return
        // Instagram sometimes bundles "X liked your message" / "X reacted" style lines; skip those
        if (isIg && Regex("(?i)(liked|reacted to|sent a like)").containsMatchIn(text)) return

        val source = if (isIg) "instagram" else "whatsapp"
        val dao = AppDb.get(applicationContext).dao()
        scope.launch {
            if (isWa && isDeletedText(text)) {
                dao.markLatestDeleted(sender)
            } else {
                val now = System.currentTimeMillis()
                if (dao.recentDuplicates(sender, text, now - 60_000) == 0) {
                    dao.insert(SavedMessage(sender = sender, text = text, timestamp = sbn.postTime, source = source))
                }
            }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
