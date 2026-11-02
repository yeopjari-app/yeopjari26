package ai.yeopjari.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 위치 변화로 차속을 계산한다(공개 Pleos Vehicle SDK 에 차속 값이 없다).
 * 터널처럼 위치가 끊기면, D단으로 달리는 동안에는 마지막 속도를 [tunnelHoldMs] 까지 유지해
 * 정차로 잘못 보지 않는다. P단이면 바로 0, 기어를 모르면 [unknownHoldMs] 뒤 0.
 */
class GpsSpeed(
    private val tunnelHoldMs: Long = 10 * 60_000L,
    private val unknownHoldMs: Long = 10_000L,
) {
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastFixMs: Long? = null
    private var smoothed = 0f

    /** 위치 한 점. [gpsKph] 는 수신기가 직접 준 속도(있으면 그것을 쓴다). 여러 스레드에서 불려도 된다. */
    @Synchronized
    fun update(lat: Double, lon: Double, timeMs: Long, gpsKph: Float? = null) {
        val prev = lastFixMs
        val dtS = prev?.let { (timeMs - it) / 1000.0 }
        // 0.3초보다 촘촘한 점은 기준점을 옮기지 않는다: 옮기면 짧은 이동이 쌓이지 않고 매번 버려진다.
        if (gpsKph == null && dtS != null && dtS >= 0.0 && dtS < 0.3) return
        val kph = when {
            gpsKph != null && gpsKph >= 0f -> gpsKph
            dtS != null && dtS in 0.3..10.0 -> (haversineM(lastLat, lastLon, lat, lon) / dtS * 3.6).toFloat()
            else -> null
        }
        // P단 뒤 위치가 한참 끊겼다 다시 잡히면 예전 속도와 섞지 않는다(출발 직후 엉뚱한 속도가 남지 않게).
        val fresh = prev == null || resetOnNextFix
        // 위치 잡음으로 튀는 값을 누른다: 직전 값과 섞되 갑작스러운 변화도 따라가게
        if (kph != null && kph <= 250f) {
            smoothed = if (fresh) kph else smoothed * 0.4f + kph * 0.6f
            resetOnNextFix = false
        } else if (fresh) {
            smoothed = 0f
            resetOnNextFix = true                              // 첫 속도가 나올 때까지 새로 시작
        }
        lastLat = lat
        lastLon = lon
        lastFixMs = timeMs
    }

    private var resetOnNextFix = false

    /** 지금 속도. 마지막 위치가 3초보다 오래됐으면 기어로 판단한다. */
    @Synchronized
    fun speedKph(nowMs: Long, gear: Gear): Float {
        val last = lastFixMs ?: return 0f
        val age = nowMs - last
        if (gear == Gear.PARK) {
            if (age > 3_000) resetOnNextFix = true
            return 0f
        }
        if (age <= 3_000) return smoothed
        val hold = if (gear == Gear.DRIVE) tunnelHoldMs else unknownHoldMs
        return if (age <= hold) smoothed else 0f
    }

    /** 위치가 끊긴 채 마지막 속도를 유지하는 중인지(화면·점검용). */
    @Synchronized
    fun holding(nowMs: Long, gear: Gear): Boolean {
        val last = lastFixMs ?: return false
        return nowMs - last > 3_000 && speedKph(nowMs, gear) > 0f
    }

    companion object {
        fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
            return 2 * r * asin(sqrt(a))
        }
    }
}
