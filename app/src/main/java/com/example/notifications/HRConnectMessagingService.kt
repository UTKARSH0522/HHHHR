package com.example.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.core.BrandConfig
import com.example.data.remote.MarketplaceBackendClient
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles Firebase Cloud Messaging token rotation and incoming push notifications
 * for booking confirmations, Google Meet updates, refunds, and expert payouts.
 */
class HRConnectMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val auth = Firebase.auth
        if (auth.currentUser == null || !BrandConfig.isBackendConfigured) {
            return
        }
        serviceScope.launch {
            runCatching {
                val client = MarketplaceBackendClient(auth)
                client.registerFcmToken(
                    fcmToken = token,
                    deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}".take(64)
                )
            }.onFailure { err ->
                Log.w(TAG, "Failed to sync refreshed FCM token", err)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        ensureNotificationChannel(this)
        if (!hasPostNotificationsPermission(this)) {
            return
        }

        val payload = buildNotificationContent(
            remoteTitle = message.notification?.title ?: message.data["title"],
            remoteBody = message.notification?.body ?: message.data["body"],
            eventType = message.data["eventType"]
        )

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            message.data["relatedBookingId"]?.let { putExtra(EXTRA_BOOKING_ID, it) }
            message.data["eventType"]?.let { putExtra(EXTRA_EVENT_TYPE, it) }
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            (message.data["relatedBookingId"] ?: payload.title).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(payload.title)
            .setContentText(payload.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(this).notify(
                (message.messageId ?: System.currentTimeMillis().toString()).hashCode(),
                notification
            )
        }.onFailure { err ->
            Log.w(TAG, "Unable to post local notification", err)
        }
    }

    companion object {
        private const val TAG = "HRConnectFCM"
        const val CHANNEL_ID = "hrconnect_updates"
        const val CHANNEL_NAME = "Consultations & Payout Updates"
        const val EXTRA_BOOKING_ID = "relatedBookingId"
        const val EXTRA_EVENT_TYPE = "eventType"

        data class SanitizedNotificationContent(
            val title: String,
            val body: String
        )

        fun buildNotificationContent(
            remoteTitle: String?,
            remoteBody: String?,
            eventType: String?
        ): SanitizedNotificationContent {
            val safeTitle = remoteTitle?.trim().takeIf { !it.isNullOrBlank() }?.take(80)
                ?: when (eventType) {
                    "BOOKING_CONFIRMED", "NEW_BOOKING" -> "Consultation Confirmed"
                    "BOOKING_RESCHEDULED" -> "Consultation Rescheduled"
                    "BOOKING_CANCELLED" -> "Consultation Cancelled"
                    "PAYOUT_COMPLETED", "PAYOUT_FAILED" -> "Payout Update"
                    "REFUND_COMPLETED", "DISPUTE_RESOLVED" -> "Refund & Dispute Update"
                    else -> "HRConnect Update"
                }
            val safeBody = remoteBody?.trim().takeIf { !it.isNullOrBlank() }?.take(180)
                ?: "Open HRConnect to view your latest consultation or payout update."
            return SanitizedNotificationContent(title = safeTitle, body = safeBody)
        }

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(NotificationManager::class.java) ?: return
                if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Booking confirmations, Google Meet updates, refunds, and payouts."
                    }
                    manager.createNotificationChannel(channel)
                }
            }
        }

        fun hasPostNotificationsPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }
    }
}
