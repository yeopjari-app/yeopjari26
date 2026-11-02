package ai.yeopjari.core

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/**
 * 차량 상태 표본. [timeMs] 는 단조 증가하는 시각(시뮬레이션에서는 가상 시각).
 * 방향지시등·급제동·조향각은 말을 걸어도 되는 순간인지 판단하는 데 쓴다([WorkloadGate]).
 * [passengerPresent] 는 조수석 탑승 여부, [drowsinessWarning] 은 차량 자체의 운전자 주의 경고 작동 여부,
 * [wipersOn] 은 와이퍼 작동 여부(비가 오면 창문을 열지 않는다).
 * [laneAssistOn] 은 차로 유지 보조(LKA·LFA)가 켜져 있는지, [laneIntervention] 은 차가 차로 이탈을 막으려고
 * 개입하는 중인지(비상 차로 유지 작동 등). 둘은 [LaneAssistTracker] 가 해석한다.
 * [recirculationOn] 은 공조가 내기 순환(외기 차단) 중인지. [CabinAirTracker] 가 해석한다.
 */
data class VehicleSnapshot(
    val timeMs: Long,
    val speedKph: Float,
    val gear: Gear,
    val turnSignalOn: Boolean = false,
    val hardBraking: Boolean = false,
    val steeringAngleDeg: Float = 0f,
    val passengerPresent: Boolean = false,
    val drowsinessWarning: Boolean = false,
    val wipersOn: Boolean = false,
    val laneAssistOn: Boolean = false,
    val laneIntervention: Boolean = false,
    val recirculationOn: Boolean = false,
    /**
     * 차로 유지 보조 중 "핸들을 잡으세요" 경고(핸들 파지 감지). 차량에는 있지만 Pleos 공개 SDK 로는 아직 읽을 수 없어
     * Pleos 빌드는 항상 false, 시연 빌드는 버튼으로 만든다. 공개되면 이 값만 채우면 된다.
     */
    val handsOffWarning: Boolean = false,
    /** 실내 카메라(DMS)의 졸음 경고. 위와 같이 공개 API 대기 중. */
    val cameraDrowsiness: Boolean = false,
)

enum class ActivationReason { HIGHWAY, LONG_TRIP, REQUESTED, VEHICLE_WARNING }

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
 * (00~06시, 14~16시)에는 [vulnerableActivationMs] 로 앞당긴다. 내비게이션 목적지가 멀면(장거리)
 * 출발 후 [longTripStartMs] 만에 시작하고, 운전자가 직접 부르거나 차량 졸음 경고가 울리면 바로 시작한다
 * ([requestActivation]). P단 정차가 [restCreditMs] 이상 이어지면 휴식으로 인정하고 누적 시간을 초기화한다.
 */
class DriveSessionTracker(
    private val highSpeedKph: Float = 80f,
    private val activationMs: Long = 30 * MINUTE,
    private val vulnerableActivationMs: Long = 15 * MINUTE,
    private val restCreditMs: Long = 10 * MINUTE,
    private val longTripStartMs: Long = 5 * MINUTE,
    private val hourOf: (timeMs: Long) -> Int,
) {
    var active = false
        private set
    /** 동행이 왜 시작됐는지(첫인사를 고르는 데 쓴다). */
    var activationReason: ActivationReason? = null
        private set
    var drivingMs = 0L
        private set
    var highSpeedMs = 0L
        private set

    private var last: VehicleSnapshot? = null
    private var parkedSinceMs: Long? = null
    private var restCredited = false

    /** [longTripAhead] 는 내비게이션 목적지까지 남은 거리가 장거리 기준 이상인지. */
    fun update(snapshot: VehicleSnapshot, longTripAhead: Boolean = false): List<DriveEvent> {
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

        if (!active) {
            val reason = when {
                highSpeedMs >= thresholdAt(snapshot.timeMs) -> ActivationReason.HIGHWAY
                longTripAhead && drivingMs >= longTripStartMs -> ActivationReason.LONG_TRIP
                else -> null
            }
            if (reason != null) {
                activate(reason)
                events += DriveEvent.Activated
            }
        }
        return events
    }

    /** 운전자가 직접 부르거나 차량 졸음 경고가 울렸을 때 바로 시작한다. 이미 함께하고 있었으면 false. */
    fun requestActivation(reason: ActivationReason): Boolean {
        if (active) return false
        activate(reason)
        return true
    }

    private fun activate(reason: ActivationReason) {
        active = true
        activationReason = reason
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
