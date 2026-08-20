package com.tracking.familyorbit

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.tracking.familyorbit.core.FamilyOrbitApi
import com.tracking.familyorbit.core.SecureTokenStore

class ParentMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val accessToken = SecureTokenStore(this, "guardian_access_token").get() ?: return
        Thread {
            runCatching { FamilyOrbitApi(BuildConfig.API_BASE_URL, this).registerGuardianPush(accessToken, token, android.os.Build.MODEL) }
        }.start()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val content = message.notification ?: return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "家族の通知", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(
            message.messageId?.hashCode() ?: System.currentTimeMillis().toInt(),
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(content.title ?: "Family Orbit")
                .setContentText(content.body ?: "家族の状態が更新されました")
                .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
                .setAutoCancel(true).setContentIntent(pending).build(),
        )
    }

    companion object { private const val CHANNEL_ID = "family_updates" }
}
