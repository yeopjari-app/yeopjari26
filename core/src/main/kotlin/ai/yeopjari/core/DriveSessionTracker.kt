package ai.yeopjari.core

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/**
 * 차량 상태 표본. [timeMs] 는 단조 증가하는 시각(시뮬레이션에서는 가상 시각).
 * 방향지시등·급제동·조향각은 말을 걸어도 되는 순간인지 판단하는 데 쓴다([WorkloadGate]).
 */
data class VehicleSnapshot(
    val timeMs: Long,
    val speedKph: Float,
    val gear: Gear,
    val turnSignalOn: Boolean = false,
    val hardBraking: Boolean = false,
    val steeringAngleDeg: Float = 0f,
)

sealed interface DriveEvent {
    /** 동행 시작 조건 충족 */
    data object Activated : DriveEvent
    /** 동행 중 P단 정차 시작 */
    data object Parked : DriveEvent
    /** 정차가 휴식으로 인정됨 */
    data object RestCredited : DriveEvent
    /** 휴식 인정 후 다시 출발 */
    data object ResumedAfterRest : DriveEvent
}

/**
 * 연속 주행 시간과 고속 주행 누적 시간을 계산해 동행 시작·휴식 인정을 판단한다.
 *
 * 고속([highSpeedKph] 이상) 주행이 [activationMs] 누적되면 시작한다. 졸음 취약 시간대
 * (00~06시, 14~16시)에는 [vulnerableActivationMs] 로 앞당긴다. P단 정차가 [restCreditMs]
 * 이상 이어지면 휴식으로 인정하고 누적 시간을 초기화한다.
 */
class DriveSessionTracker(
    private val highSpeedKph: Float = 80f,
    private val activationMs: Long = 30 * MINUTE,
    private val vulnerableActivationMs: Long = 15 * MINUTE,
    private val restCreditMs: Long = 10 * MINUTE,
    private val hourOf: (timeMs: Long) -> Int,
) {
    var active = false
        private set
    var drivingMs = 0L
        private set
    var highSpeedMs = 0L
        private set

    private var last: VehicleSnapshot? = null
    private var parkedSinceMs: Long? = null
    private var restCredited = false

    fun update(snapshot: VehicleSnapshot): List<DriveEvent> {
        val events = mutableListOf<DriveEvent>()
        val prev = last
        last = snapshot
        if (prev != null && prev.gear != Gear.PARK) {
            val dt = (snapshot.timeMs - prev.timeMs).coerceAtLeast(0)
            drivingMs += dt
            if (prev.speedKph >= highSpeedKph) highSpeedMs += dt
        }

        if (snapshot.gear == Gear.PARK) {
            val since = parkedSinceMs ?: snapshot.timeMs.also {
                parkedSinceMs = it
                if (active) events += DriveEvent.Parked
            }
            if (!restCredited && snapshot.timeMs - since >= restCreditMs) {
                restCredited = true
                drivingMs = 0
                highSpeedMs = 0
                if (active) events += DriveEvent.RestCredited
            }
        } else {
            if (parkedSinceMs != null && restCredited && active) events += DriveEvent.ResumedAfterRest
            parkedSinceMs = null
            restCredited = false
        }

        if (!active && highSpeedMs >= thresholdAt(snapshot.timeMs)) {
            active = true
            events += DriveEvent.Activated
        }
        return events
    }

    private fun thresholdAt(timeMs: Long): Long {
        val hour = hourOf(timeMs)
        val vulnerable = hour in 0..5 || hour in 14..15
        return if (vulnerable) vulnerableActivationMs else activationMs
    }

    companion object {
        const val MINUTE = 60_000L
    }
}
