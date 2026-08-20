package com.tracking.familyorbit.link

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.tracking.familyorbit.core.FamilyOrbitApi
import com.tracking.familyorbit.core.OrbitUnauthorizedException
import com.tracking.familyorbit.core.SecureTokenStore
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.Executors

class TrackingService : Service() {
    private lateinit var fusedLocation: FusedLocationProviderClient
    private lateinit var queue: EncryptedLocationQueue
    private lateinit var linkState: LinkState
    private lateinit var tokenStore: SecureTokenStore
    private val api by lazy { FamilyOrbitApi(BuildConfig.API_BASE_URL, this) }
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var currentInterval = TrackingPolicy.MOVING_INTERVAL_MS

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            enqueue(location)

            val desiredInterval = TrackingPolicy.intervalFor(location.speed, location.hasSpeed())
            if (desiredInterval != currentInterval) configureUpdates(desiredInterval)
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!hasLocationPermission()) {
                sendState("permission_denied", "location_permission_revoked")
                linkState.trackingActive = false
                stopSelf()
                return
            }
            sendState("active", "heartbeat")
            flushQueue()
            handler.postDelayed(this, TrackingPolicy.STATIONARY_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        queue = EncryptedLocationQueue(this)
        linkState = LinkState(this)
        tokenStore = SecureTokenStore(this, "child_device_token")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val shouldRestart = when (intent?.action) {
            ACTION_PAUSE -> {
                pauseTracking("paused_by_child")
                false
            }
            ACTION_REFRESH -> startTracking()
            ACTION_START, ACTION_GEOFENCE, null -> startTracking()
            else -> false
        }
        return if (shouldRestart) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        fusedLocation.removeLocationUpdates(callback)
        handler.removeCallbacks(heartbeat)
        worker.shutdown()
        super.onDestroy()
    }

    private fun startTracking(): Boolean {
        if (tokenStore.get() == null) {
            stopSelf()
            return false
        }
        if (!hasLocationPermission()) {
            sendState("permission_denied", "location_permission_missing")
            stopSelf()
            return false
        }
        try {
            startForeground(NOTIFICATION_ID, notification("位置を共有中", "家族へ現在地を送信しています"))
        } catch (_: SecurityException) {
            linkState.trackingActive = false
            linkState.lastUploadError = "バックグラウンドで再開できませんでした。アプリを開いて位置共有を開始してください。"
            stopSelf()
            return false
        }
        linkState.trackingActive = true
        sendState("active", "started_by_child")
        configureUpdates(TrackingPolicy.MOVING_INTERVAL_MS)
        requestCurrentLocation()
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, TrackingPolicy.STATIONARY_INTERVAL_MS)
        GeofenceRegistrar(this, api, tokenStore).register()
        return true
    }

    private fun pauseTracking(reason: String) {
		if (linkState.pauseRestricted && reason == "paused_by_child") {
			linkState.trackingActive = true
			startTracking()
			return
		}
        fusedLocation.removeLocationUpdates(callback)
        handler.removeCallbacks(heartbeat)
        linkState.trackingActive = false
        sendState("paused", reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun configureUpdates(interval: Long) {
        currentInterval = interval
        fusedLocation.removeLocationUpdates(callback)
        if (!hasLocationPermission()) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(if (interval == TrackingPolicy.MOVING_INTERVAL_MS) 30_000 else interval)
            .setMinUpdateDistanceMeters(if (interval == TrackingPolicy.MOVING_INTERVAL_MS) 100f else 0f)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            fusedLocation.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            sendState("permission_denied", "location_permission_revoked")
        }
    }

    private fun flushQueue() {
        val token = tokenStore.get() ?: return
        worker.execute {
            val pending = queue.pending().take(100)
            if (pending.isEmpty()) return@execute
            runCatching {
                api.sendLocations(
                    token,
                    "queue:${pending.first().id}:${pending.last().id}:${pending.size}",
                    JSONArray().apply { pending.forEach { put(it.toApiJson()) } },
                    "active",
                )
            }.onSuccess {
                queue.remove(pending.map { it.id }.toSet())
                linkState.lastSentAt = System.currentTimeMillis()
                linkState.pendingLocationCount = queue.pending().size
                linkState.lastUploadError = null
            }.onFailure {
                if (!handleDeviceFailure(it)) {
                    linkState.pendingLocationCount = queue.pending().size
                    linkState.lastUploadError = it.message ?: "サーバーへ送信できません。自動的に再送します。"
                }
            }
        }
    }

    private fun requestCurrentLocation() {
        if (!hasLocationPermission()) return
        val cancellation = CancellationTokenSource()
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(0)
            .setDurationMillis(15_000)
            .build()
        fusedLocation.getCurrentLocation(request, cancellation.token)
            .addOnSuccessListener { location -> location?.let(::enqueue) }
            .addOnFailureListener {
                linkState.lastUploadError = "現在地を取得できませんでした。端末の位置情報設定を確認してください。"
            }
    }

    private fun enqueue(location: android.location.Location) {
        val battery = getSystemService(BATTERY_SERVICE) as BatteryManager
        val point = QueuedLocation(
            id = UUID.randomUUID().toString(),
            recordedAt = location.time.coerceAtLeast(System.currentTimeMillis() - 60_000),
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            speed = location.speed,
            batteryLevel = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100) / 100f,
            isCharging = battery.isCharging,
        )
        queue.append(point)
        linkState.lastAccuracy = location.accuracy
        linkState.pendingLocationCount = queue.pending().size
        flushQueue()
    }

    private fun sendState(state: String, reason: String) {
        val token = tokenStore.get() ?: return
        worker.execute {
            runCatching { api.sendTrackingState(token, state, reason) }
                .onFailure { handleDeviceFailure(it) }
        }
    }

    private fun handleDeviceFailure(error: Throwable): Boolean {
        if (error !is OrbitUnauthorizedException) return false
        queue.clear()
        FamilyRemovalState.mark(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return true
    }

    private fun notification(title: String, text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TrackingService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_orbit_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (!linkState.pauseRestricted) builder.addAction(0, "共有を停止", pauseIntent)
        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "位置共有の状態", NotificationManager.IMPORTANCE_LOW).apply {
            description = "位置共有中であることを常時表示します"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_START = "com.tracking.familyorbit.link.START"
        const val ACTION_PAUSE = "com.tracking.familyorbit.link.PAUSE"
        const val ACTION_REFRESH = "com.tracking.familyorbit.link.REFRESH"
        const val ACTION_GEOFENCE = "com.tracking.familyorbit.link.GEOFENCE"
        private const val CHANNEL_ID = "family_orbit_tracking"
        private const val NOTIFICATION_ID = 4201
    }
}
