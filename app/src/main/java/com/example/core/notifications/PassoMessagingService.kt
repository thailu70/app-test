package com.example.core.notifications

import android.app.PendingIntent
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Data-only pushes are validated and authorised locally before a generic notification is displayed. */
class PassoMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { MobilityPush.receivedToken(applicationContext, token) }

    @Suppress("MissingPermission")
    override fun onMessageReceived(message: RemoteMessage) {
        if (!MobilityPush.allowed(this)) return
        val payload = MobilityPushPayload.parse(message.data) ?: return
        val authorised = if (payload.kind in setOf("TAXI", "SHARED")) RidePush.deliveryAllowed(this, payload.scope)
            else if (payload.scope == "DRIVER") MobilityPush.driverDeliveryAllowed(this)
            else MobilityPush.guestDeliveryAllowed(this, payload.requestId)
        if (!authorised) return
        MobilityPush.ensureChannel(this)
        val id = (payload.requestId + ":" + payload.eventType).hashCode()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MobilityPushPayload.EXTRA_ID, payload.requestId)
            putExtra(MobilityPushPayload.EXTRA_EVENT, payload.eventType)
            putExtra(MobilityPushPayload.EXTRA_SCOPE, payload.scope)
            putExtra(MobilityPushPayload.EXTRA_KIND, payload.kind)
        }
        val pending = PendingIntent.getActivity(this, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val (title, body) = payload.notificationCopy()
        val alert = (payload.scope == "DRIVER" && payload.eventType == "NEW_REQUEST") ||
            (payload.scope == "PASSENGER" && (
                (payload.kind == "SHARED" && payload.eventType in setOf("NEW_REQUEST", "STATUS_OPEN")) ||
                    (payload.kind in setOf("TAXI", "SHARED") && payload.eventType == "STATUS_ACCEPTED") ||
                    (payload.kind in setOf("CARGO", "GROUP") && payload.eventType == "QUOTES")
                ))
        val driverAlert = payload.scope == "DRIVER"
        val channel = if (alert) MobilityPush.selectedAlertChannel(this, driverAlert) else MobilityPush.CHANNEL_ID
        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_mobility_notification).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(pending)
            .setAutoCancel(true).setOnlyAlertOnce(!alert)
            .setPriority(if (alert) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .apply {
                if (alert) {
                    setCategory(NotificationCompat.CATEGORY_CALL)
                    setVibrate(longArrayOf(0, 850, 350, 850, 350, 1200))
                    if (Build.VERSION.SDK_INT < 26) setSound(
                        RingtoneManager.getDefaultUri(MobilityAlertTone.selected(this@PassoMessagingService, driverAlert).ringtoneType))
                } else setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            }
            .build()
        NotificationManagerCompat.from(this).notify(id, notification)
    }
}
