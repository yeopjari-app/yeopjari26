package ai.yeopjari.core

import kotlin.math.abs

/** 운전에 집중해야 해서 말을 걸지 않는 이유. */
enum class Workload { LANE_CHANGE, HARD_BRAKING, SHARP_STEERING, JUNCTION_AHEAD }

/**
 * 좋은 동승자처럼, 운전이 바쁜 순간에는 말을 걸지 않는다.
 *
 * 방향지시등(차선 변경), 급제동, 큰 조향, 다가오는 갈림길 중 하나라도 있으면 조용히 하고,
 * 상황이 끝난 뒤에도 [cooldownMs] 동안은 기다렸다가 말을 건다.
 * 비(와이퍼)는 넣지 않는다. 비가 오래 오면 각성 확인이 아예 멈추기 때문이다.
 */
class WorkloadGate(
    private val sharpSteeringDeg: Float = 30f,
    private val cooldownMs: Long = 10_000,
) {
    private var quietUntilMs = Long.MIN_VALUE

    var reasons: Set<Workload> = emptySet()
        private set

    fun update(snapshot: VehicleSnapshot, junctionAhead: Boolean = false): Set<Workload> {
        reasons = buildSet {
            if (snapshot.turnSignalOn) add(Workload.LANE_CHANGE)
            if (snapshot.hardBraking) add(Workload.HARD_BRAKING)
            if (abs(snapshot.steeringAngleDeg) >= sharpSteeringDeg) add(Workload.SHARP_STEERING)
            if (junctionAhead) add(Workload.JUNCTION_AHEAD)
        }
        if (reasons.isNotEmpty()) quietUntilMs = snapshot.timeMs + cooldownMs
        return reasons
    }

    fun canTalk(nowMs: Long): Boolean = reasons.isEmpty() && nowMs >= quietUntilMs
}
