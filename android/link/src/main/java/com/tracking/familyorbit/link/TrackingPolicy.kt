package com.tracking.familyorbit.link

object TrackingPolicy {
    const val MOVING_INTERVAL_MS = 45_000L
    const val STATIONARY_INTERVAL_MS = 5 * 60_000L

    fun intervalFor(speedMetersPerSecond: Float?, hasSpeed: Boolean): Long =
        if (hasSpeed && speedMetersPerSecond != null && speedMetersPerSecond >= 1f) MOVING_INTERVAL_MS else STATIONARY_INTERVAL_MS
}
