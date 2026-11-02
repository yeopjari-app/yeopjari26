package ai.yeopjari.core

/** 조용히 모드가 끝난 이유. */
enum class QuietEnd { TIME_UP, WEAVING, VEHICLE_WARNING, LANE_INTERVENTION, HANDS_OFF }

/**
 * "조용히 해 줘"는 수다를 멈추라는 뜻이지, 졸음 감지를 멈추라는 뜻이 아니다.
 *
 * 조용히 하는 동안 옆자리는 먼저 말을 걸지 않는다. 대신 차량 신호(조향 흔들림, 차량 자체의 졸음 경고)는
 * 계속 보고, 신호가 보이면 남은 시간과 관계없이 바로 끝내고 짧게 확인한다([interrupt]).
 * 새벽(0~6시)이나 [longDriveMs] 이상 운전한 뒤에는 가장 졸리기 쉬운 때라 [riskyMs] 만큼만 조용히 한다.
 */
class QuietMode(
    private val normalMs: Long,
    private val riskyMs: Long,
    private val longDriveMs: Long = 2 * 60 * 60_000L,
) {
    private var untilMs = Long.MIN_VALUE
    private var running = false

    /** 조용히 모드를 시작하고 얼마나 조용히 할지 돌려준다. */
    fun start(nowMs: Long, hour: Int, drivingMs: Long): Long {
        val ms = if (hour < 6 || drivingMs >= longDriveMs) riskyMs else normalMs
        untilMs = nowMs + ms
        running = true
        return ms
    }

    fun active(nowMs: Long): Boolean = running && nowMs < untilMs

    /** 정해 둔 시간이 지나 끝났으면 한 번만 [QuietEnd.TIME_UP] 을 돌려준다. */
    fun poll(nowMs: Long): QuietEnd? {
        if (!running || nowMs < untilMs) return null
        running = false
        return QuietEnd.TIME_UP
    }

    /** 졸음 신호로 조용히 모드를 깬다. 조용히 하던 중이었으면 true. */
    fun interrupt(reason: QuietEnd): Boolean {
        require(reason != QuietEnd.TIME_UP)
        val was = running
        running = false
        return was
    }
}
