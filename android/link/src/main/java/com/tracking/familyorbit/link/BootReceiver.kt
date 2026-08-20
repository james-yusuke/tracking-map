package com.tracking.familyorbit.link

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val state = LinkState(context)
        if (!state.trackingActive && !state.pauseRestricted) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            state.trackingActive = false
            state.lastUploadError = "端末の再起動後はアプリを開き、位置共有を再開してください。常時共有には「常に許可」が必要です。"
            return
        }
        runCatching {
            if (state.pauseRestricted) state.trackingActive = true
            ContextCompat.startForegroundService(
                context,
                Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_START),
            )
        }
    }
}
