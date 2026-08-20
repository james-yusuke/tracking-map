package com.tracking.familyorbit.link

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.tracking.familyorbit.core.FamilyOrbitApi
import com.tracking.familyorbit.core.SecureTokenStore
import java.util.concurrent.Executors

class GeofenceRegistrar(
    private val context: Context,
    private val api: FamilyOrbitApi,
    private val tokenStore: SecureTokenStore,
) {
    fun register() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        Executors.newSingleThreadExecutor().execute {
            val token = tokenStore.get() ?: return@execute
            val zones = runCatching { api.deviceZones(token) }.getOrNull() ?: return@execute
            val geofences = buildList {
                for (index in 0 until zones.length()) {
                    val zone = zones.getJSONObject(index)
                    add(
                        Geofence.Builder()
                            .setRequestId(zone.getString("id"))
                            .setCircularRegion(zone.getDouble("latitude"), zone.getDouble("longitude"), zone.getDouble("radiusMeters").toFloat())
                            .setExpirationDuration(Geofence.NEVER_EXPIRE)
                            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                            .build(),
                    )
                }
            }
            if (geofences.isEmpty()) return@execute
            val request = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofences(geofences)
                .build()
            try {
                LocationServices.getGeofencingClient(context).addGeofences(request, pendingIntent())
            } catch (_: SecurityException) {
                // The UI reports missing permission; registration is retried on the next start.
            }
        }
    }

    private fun pendingIntent() = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, GeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
}
