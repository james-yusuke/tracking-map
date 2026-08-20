package com.tracking.familyorbit.link

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.android.gms.location.GeofencingEvent

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError() || !LinkState(context).trackingActive) return
        ContextCompat.startForegroundService(
            context,
            Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_GEOFENCE),
        )
    }
}
