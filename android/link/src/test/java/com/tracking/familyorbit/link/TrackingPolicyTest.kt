package com.tracking.familyorbit.link

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingPolicyTest {
    @Test
    fun movingUses45SecondInterval() {
        assertEquals(45_000L, TrackingPolicy.intervalFor(1.5f, true))
    }

    @Test
    fun stationaryAndUnknownSpeedUseFiveMinuteInterval() {
        assertEquals(300_000L, TrackingPolicy.intervalFor(0.2f, true))
        assertEquals(300_000L, TrackingPolicy.intervalFor(null, false))
    }
}
