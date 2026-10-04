package com.rideflux.domain.safety

enum class DangerTier(val wireName: String) {
    LISTEN("T-LISTEN"), BENIGN("T-BENIGN"), CRITICAL("T-CRITICAL"), FORBIDDEN("T-FORBIDDEN")
}

enum class MotionState { UNKNOWN, MOVING, STATIONARY_CONFIRMED }

/** Per-connection filter. Call only with decoded, model-scaled speed samples. */
class MotionInterlock(private val maxSampleAgeMillis: Long = 1_500L) {
    init { require(maxSampleAgeMillis > 0L) }

    private var zeroFrames = 0
    private var lastSampleAt: Long? = null
    private var moving = false

    @Synchronized fun reset() {
        zeroFrames = 0
        lastSampleAt = null
        moving = false
    }

    @Synchronized fun observe(speedKmh: Float?, nowMillis: Long) {
        if (speedKmh == null || !speedKmh.isFinite() || speedKmh < 0f ||
            lastSampleAt?.let { nowMillis < it || nowMillis - it > maxSampleAgeMillis } == true
        ) {
            reset()
            if (speedKmh == null || !speedKmh.isFinite() || speedKmh < 0f) return
        }
        lastSampleAt = nowMillis
        moving = speedKmh > 0f
        zeroFrames = if (moving) 0 else (zeroFrames + 1).coerceAtMost(3)
    }

    @Synchronized fun state(nowMillis: Long): MotionState {
        val last = lastSampleAt ?: return MotionState.UNKNOWN
        if (nowMillis < last || nowMillis - last > maxSampleAgeMillis) return MotionState.UNKNOWN
        return if (moving) MotionState.MOVING else if (zeroFrames >= 3) MotionState.STATIONARY_CONFIRMED else MotionState.UNKNOWN
    }

    @Synchronized fun requireAllowed(tier: DangerTier, nowMillis: Long) {
        if (tier == DangerTier.FORBIDDEN) throw SecurityException("Forbidden vehicle command")
        if (tier == DangerTier.CRITICAL && state(nowMillis) != MotionState.STATIONARY_CONFIRMED) {
            throw SecurityException("Three fresh stationary speed frames are required")
        }
    }
}
