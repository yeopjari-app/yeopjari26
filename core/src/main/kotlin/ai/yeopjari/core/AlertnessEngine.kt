package ai.yeopjari.core

/** 각성 단계. */
enum class AlertStage { NORMAL, CAUTION, DANGER }

/** 음성 체크 한 번의 결과. [latencyMs] 가 null 이면 무응답. */
data class CheckResult(val latencyMs: Long?, val correct: Boolean)

/**
 * 응답 지연·정답 여부를 운전자 본인의 평소 응답 속도(기준선)와 비교해 각성 단계를 판정한다.
 *
 * 체크마다 점수를 매기고(무응답 5, 기준선의 2배 이상 2, 1.5배 이상 1, 오답 +1),
 * 최근 [windowSize] 회 합계가 [cautionScore] 이상이면 주의, [dangerScore] 이상이면 위험.
 * 처음 [calibrationSize] 회의 정답 응답은 속도를 판정하지 않고 기준선으로만 쓴다(보정).
 * 이후 기준선은 정상 범위의 정답 응답으로만 갱신해 졸린 상태가 기준선을 끌어올리지 않게 한다.
 * [initialBaseline] 으로 지난 주행들에서 저장한 기준선을 이어 받으면 보정 없이 바로 판정하므로,
 * "출발할 때부터 평소보다 느린 날"도 알아챈다.
 */
class AlertnessEngine(
    private val baselineSize: Int = 10,
    private val windowSize: Int = 3,
    private val calibrationSize: Int = 3,
    private val defaultBaselineMs: Long = 1_500,
    private val cautionScore: Int = 2,
    private val dangerScore: Int = 7,
    initialBaseline: List<Long> = emptyList(),
) {
    private val baseline = ArrayDeque(initialBaseline.takeLast(baselineSize))
    private val scores = ArrayDeque<Int>()

    /** 다음 주행에 이어 쓰도록 저장할 기준선 표본. */
    val baselineSamples: List<Long>
        get() = baseline.toList()

    var stage: AlertStage = AlertStage.NORMAL
        private set

    /** 평소 응답 속도(중앙값). 표본이 없으면 기본값. */
    val baselineMs: Long
        get() = if (baseline.isEmpty()) defaultBaselineMs else baseline.sorted()[baseline.size / 2]

    fun record(result: CheckResult): AlertStage {
        val calibrating = baseline.size < calibrationSize
        val score = score(result, judgeSpeed = !calibrating)
        if (score == 0 && result.latencyMs != null && result.latencyMs >= MIN_SAMPLE_MS) {
            baseline.addLast(result.latencyMs)
            if (baseline.size > baselineSize) baseline.removeFirst()
        }
        scores.addLast(score)
        if (scores.size > windowSize) scores.removeFirst()

        val total = scores.sum()
        stage = when {
            total >= dangerScore -> AlertStage.DANGER
            total >= cautionScore -> AlertStage.CAUTION
            else -> AlertStage.NORMAL
        }
        return stage
    }

    /** 차량의 운전자 주의 경고가 울리면 대화 결과와 상관없이 바로 위험 단계로 올린다. */
    fun recordVehicleWarning(): AlertStage {
        scores.addLast(dangerScore)
        if (scores.size > windowSize) scores.removeFirst()
        stage = AlertStage.DANGER
        return stage
    }

    /** 휴식이 확인되면 최근 기록을 지운다. 기준선은 유지한다. */
    fun resetAfterRest() {
        scores.clear()
        stage = AlertStage.NORMAL
    }

    private fun score(result: CheckResult, judgeSpeed: Boolean): Int {
        val latency = result.latencyMs ?: return 5
        val base = maxOf(baselineMs, MIN_SAMPLE_MS)
        val ratio = latency.toDouble() / base
        val slowness = when {
            !judgeSpeed -> 0
            latency - base < MIN_SLOWER_MS -> 0 // 0.4초 남짓 차이는 버릇·소음 수준이라 느림으로 보지 않는다
            ratio >= 2.0 -> 2
            ratio >= 1.5 -> 1
            else -> 0
        }
        return slowness + if (result.correct) 0 else 1
    }

    private companion object {
        /** 이보다 짧은 대답 시간(시계 오차·즉시 누른 화면 대답)은 기준선에 넣지 않고, 기준선도 이 값 아래로 보지 않는다. */
        const val MIN_SAMPLE_MS = 300L
        /** 평소보다 이만큼은 늦어야 느린 대답으로 본다(기준선이 아주 짧을 때 0.8초 대답이 '2배'가 되는 것을 막는다). */
        const val MIN_SLOWER_MS = 400L
    }
}
