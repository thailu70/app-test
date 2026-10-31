package com.example.core.notifications

import android.app.PendingIntent
import android.content.Intent
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
        val authorised = if (payload.scope == "DRIVER") MobilityPush.driverDeliveryAllowed(this)
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
        val notification = NotificationCompat.Builder(this, MobilityPush.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mobility_notification).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(pending)
            .setAutoCancel(true).setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        NotificationManagerCompat.from(this).notify(id, notification)
    }
}
