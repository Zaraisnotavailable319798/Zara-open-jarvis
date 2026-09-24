package com.openjarvis.intelligence

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.openjarvis.accessibility.JarvisAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class JarvisNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val notifications = mutableListOf<JarvisNotification>()

    companion object {

        private val PRIVACY_PROTECTED_APPS = setOf(
            "com.google.android.apps.nbu.paisa.user",
            "com.phonepe.app",
            "net.one97.paytm",
            "com.bankofamerica.cashpromobile",
            "com.chase",
            "com.wellsfargo",
            "com.usbank",
            "com.citi",
            "com.barclays",
            "com.hsbc",
            "com.db",
            "com.americanexpress",
            "com.discover",
            "com.paypal",
            "com.squareup",
            "com.stripe",
            "com.razorpay"
        )

        private val messagingApps = setOf(
            "com.whatsapp",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.instagram.android",
            "com.facebook.orca",
            "org.telegram.messenger",
            "com.slack",
            "com.discord"
        )

        fun shouldProcessNotification(packageName: String): Boolean {
            return packageName !in PRIVACY_PROTECTED_APPS
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!shouldProcessNotification(sbn.packageName)) {
            return
        }

        val parsed = parseNotification(sbn)

        synchronized(notifications) {
            notifications.removeAll {
                it.id == parsed.id &&
                it.packageName == parsed.packageName
            }

            notifications.add(parsed)

            // Keep memory usage under control.
            if (notifications.size > 500) {
                notifications.removeAt(0)
            }
        }

        scope.launch {
            // Notification logging can be added here later when
            // Graphify exposes the required notification API.
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(notifications) {
            notifications.removeAll {
                it.id == sbn.id &&
                it.packageName == sbn.packageName
            }
        }
    }

    private fun parseNotification(
        sbn: StatusBarNotification
    ): JarvisNotification {

        val extras = sbn.notification.extras

        val title = extras
            .getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()
            .orEmpty()

        val body = extras
            .getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()
            .orEmpty()

        val appLabel = try {
            packageManager
                .getApplicationLabel(
                    packageManager.getApplicationInfo(
                        sbn.packageName,
                        0
                    )
                )
                .toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        val isMessaging = sbn.packageName in messagingApps

        /*
         * EXTRA_SENDER_TEXT is not available on all Android SDK versions.
         * For messaging notifications, use the notification title as the
         * best available sender fallback.
         */
        val sender = if (isMessaging) {
            title.takeIf { it.isNotBlank() }
        } else {
            null
        }

        return JarvisNotification(
            id = sbn.id,
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = title,
            body = body,
            timestamp = sbn.postTime,
            isMessaging = isMessaging,
            sender = sender
        )
    }

    suspend fun replyToNotification(
        sender: String,
        packageName: String,
        reply: String
    ): Boolean {

        return try {
            val notification = synchronized(notifications) {
                notifications.find {
                    it.packageName == packageName &&
                    (
                        it.sender?.contains(
                            sender,
                            ignoreCase = true
                        ) == true ||
                        it.title.contains(
                            sender,
                            ignoreCase = true
                        )
                    )
                }
            } ?: return false

            val service = JarvisAccessibilityService.instance
                ?: return false

            service.tapByText("Reply")

            service.typeText(reply)

            service.tapByText("Send")

            true
        } catch (e: Exception) {
            false
        }
    }

    fun getUnread(
        packageName: String? = null
    ): List<JarvisNotification> {

        return synchronized(notifications) {
            if (packageName != null) {
                notifications.filter {
                    it.packageName == packageName
                }
            } else {
                notifications.toList()
            }
        }
    }

    fun getSummary(): String {

        val snapshot = synchronized(notifications) {
            notifications.toList()
        }

        val grouped = snapshot.groupBy {
            it.packageName
        }

        return grouped.entries
            .sortedByDescending {
                it.value.size
            }
            .take(5)
            .joinToString(", ") { (_, msgs) ->

                val app = msgs.firstOrNull()?.appLabel
                    ?: "Unknown"

                val count = msgs.size

                val lastSender = msgs
                    .lastOrNull()
                    ?.sender

                if (!lastSender.isNullOrBlank()) {
                    "$count $app from $lastSender"
                } else {
                    "$count $app"
                }
            }
    }

    fun clearAll(
        packageName: String? = null
    ) {

        synchronized(notifications) {
            if (packageName != null) {
                notifications.removeAll {
                    it.packageName == packageName
                }
            } else {
                notifications.clear()
            }
        }
    }

    data class JarvisNotification(
        val id: Int,
        val packageName: String,
        val appLabel: String,
        val title: String,
        val body: String,
        val timestamp: Long,
        val isMessaging: Boolean,
        val sender: String?
    )
}
