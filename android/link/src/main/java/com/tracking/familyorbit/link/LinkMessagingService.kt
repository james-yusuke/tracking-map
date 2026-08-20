package com.tracking.familyorbit.link

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.tracking.familyorbit.core.FamilyOrbitApi
import com.tracking.familyorbit.core.SecureTokenStore

class LinkMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val deviceToken = SecureTokenStore(this, "child_device_token").get() ?: return
        Thread { runCatching { FamilyOrbitApi(BuildConfig.API_BASE_URL, this).registerDevicePush(deviceToken, token) } }.start()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val removed = message.data["type"] == "family_removed"
        if (removed) {
            FamilyRemovalState.mark(this)
            stopService(Intent(this, TrackingService::class.java))
        }
        val content = message.notification ?: return
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "保護者からのメッセージ", NotificationManager.IMPORTANCE_HIGH))
        }
        val intent = Intent(this, MainActivity::class.java)
            .putExtra("messageId", message.data["messageId"] ?: message.data["itemId"])
            .putExtra("type", message.data["type"])
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(
            message.messageId?.hashCode() ?: System.currentTimeMillis().toInt(),
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_orbit_notification)
                .setContentTitle(content.title ?: if (removed) "Family Orbit" else "Family Orbitからメッセージ")
                .setContentText(content.body.orEmpty())
                .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true).setContentIntent(pending).build(),
        )
    }

    companion object { private const val CHANNEL_ID = "parent_messages" }
}
