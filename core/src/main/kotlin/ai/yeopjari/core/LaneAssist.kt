package ai.yeopjari.core

/**
 * 차로 유지 보조(LKA·LFA)와 비상 차로 유지(ELK)를 고려한다.
 *
 * - 보조가 켜져 있으면 차가 스스로 조향을 바로잡으므로 조향각은 운전자 상태를 보여 주지 않는다.
 *   그동안은 조향 흔들림 단서를 쓰지 않고 대답 속도로만 판단한다([steeringTrusted]).
 * - 차가 차로 이탈을 막으려고 개입하면(ELK 작동·차로 경고 깜빡임) 그것을 흔들림과 같은 단서로 보고 바로 확인한다
 *   ([update] 가 true 를 돌려주는 순간).
 * - 밤(0~6시)에 LFA 를 오래 켜고 달리면 손과 눈이 느슨해지기 쉬워 확인 간격을 [nightLongIntervalFactor] 배로 줄인다.
 */
class LaneAssistTracker(
    private val longAssistMs: Long = 30 * 60_000L,
    private val nightLongIntervalFactor: Double = 0.75,
) {
    private var assistSinceMs: Long? = null
    private var lastIntervention = false

    var assistOn = false
        private set

    /** 표본 하나를 넣는다. 차로 이탈 개입이 새로 시작된 순간이면 true. */
    fun update(snapshot: VehicleSnapshot): Boolean {
        assistOn = snapshot.laneAssistOn
        assistSinceMs = if (assistOn) assistSinceMs ?: snapshot.timeMs else null
        val rising = snapshot.laneIntervention && !lastIntervention
        lastIntervention = snapshot.laneIntervention
        return rising
    }

    /** 조향 흔들림을 졸음 단서로 써도 되는지. 보조가 켜져 있으면 차의 수정이 섞여 쓰지 않는다. */
    val steeringTrusted: Boolean
        get() = !assistOn

    /** 다음 확인 간격에 곱할 값. 밤에 보조를 오래 켜고 달렸으면 1보다 작다. */
    fun intervalFactor(nowMs: Long, hour: Int): Double {
        val since = assistSinceMs ?: return 1.0
        val night = hour in 0..5
        return if (night && nowMs - since >= longAssistMs) nightLongIntervalFactor else 1.0
    }
}
