package com.tracking.familyorbit.link

import android.content.Context
import com.tracking.familyorbit.core.SecureTokenStore

class LinkState(context: Context) {
    private val preferences = context.getSharedPreferences("family_orbit_link", Context.MODE_PRIVATE)

    var trackingActive: Boolean
        get() = preferences.getBoolean("tracking_active", false)
        set(value) { preferences.edit().putBoolean("tracking_active", value).apply() }

    var childId: String?
        get() = preferences.getString("child_id", null)
        set(value) { preferences.edit().putString("child_id", value).apply() }

    var lastSentAt: Long
        get() = preferences.getLong("last_sent_at", 0)
        set(value) { preferences.edit().putLong("last_sent_at", value).apply() }

    var lastAccuracy: Float
        get() = preferences.getFloat("last_accuracy", 0f)
        set(value) { preferences.edit().putFloat("last_accuracy", value).apply() }

    var pendingLocationCount: Int
        get() = preferences.getInt("pending_location_count", 0)
        set(value) { preferences.edit().putInt("pending_location_count", value).apply() }

    var lastUploadError: String?
        get() = preferences.getString("last_upload_error", null)
        set(value) { preferences.edit().putString("last_upload_error", value).apply() }

    var removedFromFamily: Boolean
        get() = preferences.getBoolean("removed_from_family", false)
        set(value) { preferences.edit().putBoolean("removed_from_family", value).apply() }

    var pauseRestricted: Boolean
        get() = preferences.getBoolean("pause_restricted", false)
        set(value) { preferences.edit().putBoolean("pause_restricted", value).apply() }
}

object FamilyRemovalState {
    fun mark(context: Context) {
        val application = context.applicationContext
        SecureTokenStore(application, "child_device_token").clear()
        EncryptedLocationQueue(application).clear()
        LinkState(application).apply {
            trackingActive = false
            childId = null
            pendingLocationCount = 0
            lastUploadError = null
            removedFromFamily = true
            pauseRestricted = false
        }
    }
}
