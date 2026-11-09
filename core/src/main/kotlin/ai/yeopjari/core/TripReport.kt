package ai.yeopjari.core

/** 이번 주행에서 보인 졸음 신호의 종류. */
enum class DrowsySignal { SLOW_ANSWER, NO_ANSWER, WEAVING, VEHICLE_WARNING, LANE_INTERVENTION, HANDS_OFF, CAMERA_DROWSINESS }

/** 옆자리가 깨우려고 한 일. */
enum class WakeAction { FRESH_AIR, WINDOW, COLD_AIR_SEAT, ALARM, HAZARD, FAMILY_ALERT }

/**
 * 한 번의 주행을 기록해 "오늘의 운행" 요약을 만든다. 화면과 가족 알림에 같은 숫자를 쓴다.
 * 시간은 차량 스냅샷과 같은 기준(ms)이면 된다. 의료적 판단이 아니라 옆자리가 한 일과 본 신호의 기록이다.
 */
class TripRecorder {
    private var startMs: Long? = null
    private var lastMs: Long = 0
    var conversations = 0
        private set
    private val latencies = mutableListOf<Long>()
    private val signals = linkedSetOf<DrowsySignal>()
    private var signalCount = 0
    private val wakes = linkedSetOf<WakeAction>()
    private var restStop: String? = null
    private var napMinutes = 0
    var maxStage = AlertStage.NORMAL
        private set

    fun start(nowMs: Long) {
        if (startMs == null) startMs = nowMs
        lastMs = maxOf(lastMs, nowMs)
    }

    fun tick(nowMs: Long) {
        if (startMs != null) lastMs = maxOf(lastMs, nowMs)
    }

    /** 옆자리가 먼저 건넨 대화 한 번. 대답 속도를 판정에 쓴 경우만 [latencyMs] 를 넘긴다(무응답이면 null). */
    fun conversation(latencyMs: Long? = null, measured: Boolean = false) {
        conversations++
        if (measured && latencyMs != null) latencies += latencyMs
    }

    fun stage(stage: AlertStage) {
        if (stage > maxStage) maxStage = stage
    }

    /** 같은 종류가 이어져도 횟수는 세되, 요약에는 종류를 한 번만 적는다. */
    fun signal(signal: DrowsySignal) {
        signals += signal
        signalCount++
    }

    fun wake(action: WakeAction) {
        wakes += action
    }

    fun rest(stopName: String?, napMinutes: Int) {
        if (stopName != null) restStop = stopName
        this.napMinutes += napMinutes
    }

    fun summary(baselineMs: Long, remembered: String?): TripSummary = TripSummary(
        minutes = ((lastMs - (startMs ?: lastMs)) / 60_000L).toInt(),
        conversations = conversations,
        baselineMs = baselineMs,
        slowestMs = latencies.maxOrNull(),
        signals = signals.toList(),
        signalCount = signalCount,
        wakes = wakes.toList(),
        restStop = restStop,
        napMinutes = napMinutes,
        remembered = remembered?.takeIf { it.isNotBlank() },
    )
}

data class TripSummary(
    val minutes: Int,
    val conversations: Int,
    val baselineMs: Long,
    val slowestMs: Long?,
    val signals: List<DrowsySignal>,
    val signalCount: Int,
    val wakes: List<WakeAction>,
    val restStop: String?,
    val napMinutes: Int,
    val remembered: String?,
) {
    /** 화면에 그대로 쓰는 (항목, 값) 줄. 일어나지 않은 일은 줄을 만들지 않는다. */
    fun rows(): List<Pair<String, String>> = buildList {
        add("함께한 시간" to duration(minutes))
        add("먼저 건넨 대화" to "${conversations}번")
        add("대답 속도" to listOfNotNull("평소 ${seconds(baselineMs)}", slowestMs?.let { "가장 늦을 때 ${seconds(it)}" }).joinToString(" · "))
        if (signals.isNotEmpty()) add("졸음 신호" to "${signalCount}번 · " + signals.joinToString(", ") { it.label })
        if (wakes.isNotEmpty()) add("깨우기" to wakes.joinToString(" · ") { it.label })
        if (restStop != null || napMinutes > 0) {
            add("휴식" to listOfNotNull(restStop, "파워냅 ${napMinutes}분".takeIf { napMinutes > 0 }).joinToString(" · "))
        }
        remembered?.let { add("기억" to it) }
    }

    /** 저장용 한 줄 텍스트(앱을 다시 열어도 지난 운행 요약을 보여 주기 위해). */
    fun serializeRows(): String = rows().joinToString("\n") { "${it.first}\t${it.second}" }

    companion object {
        fun parseRows(text: String?): List<Pair<String, String>> =
            text.orEmpty().lines().mapNotNull { line -> line.split("\t", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
    }
}

val DrowsySignal.label: String
    get() = when (this) {
        DrowsySignal.SLOW_ANSWER -> "대답 지연"
        DrowsySignal.NO_ANSWER -> "무응답"
        DrowsySignal.WEAVING -> "조향 흔들림"
        DrowsySignal.VEHICLE_WARNING -> "차량 졸음 경고"
        DrowsySignal.LANE_INTERVENTION -> "차로 이탈 보조 개입"
        DrowsySignal.HANDS_OFF -> "핸들 놓음 경고"
        DrowsySignal.CAMERA_DROWSINESS -> "카메라 졸음 경고"
    }

val WakeAction.label: String
    get() = when (this) {
        WakeAction.FRESH_AIR -> "환기"
        WakeAction.WINDOW -> "창문"
        WakeAction.COLD_AIR_SEAT -> "찬 바람·통풍 시트"
        WakeAction.ALARM -> "알람"
        WakeAction.HAZARD -> "비상등"
        WakeAction.FAMILY_ALERT -> "가족 연락"
    }

private fun seconds(ms: Long): String = String.format(java.util.Locale.KOREA, "%.1f초", ms / 1000.0)

internal fun duration(minutes: Int): String =
    when {
        minutes < 60 -> "${minutes}분"
        minutes % 60 == 0 -> "${minutes / 60}시간"
        else -> "${minutes / 60}시간 ${minutes % 60}분"
    }
