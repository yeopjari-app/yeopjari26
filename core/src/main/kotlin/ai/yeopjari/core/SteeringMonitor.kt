package ai.yeopjari.core

import kotlin.math.abs

/**
 * 작은 조향 수정이 평소보다 잦아지는지 본다. 졸리면 차로 안에서 흔들리다 급히 바로잡는 일이 늘어난다.
 *
 * 동행이 시작된 뒤 처음 [baselineSamples] 개 표본으로 이번 주행의 평소 수정 빈도를 정하고,
 * 최근 [windowSamples] 개 표본의 빈도가 그 [ratio] 배 이상(최소 [minRate])이면 흔들림으로 본다.
 * 이 신호만으로는 경고하지 않는다. 옆자리는 이것을 "지금 한 번 물어볼 때"라는 단서로만 쓰고,
 * 판단은 대답으로 확인한다.
 */
class SteeringMonitor(
    private val baselineSamples: Int,
    private val windowSamples: Int,
    private val reversalDeg: Float = 2.5f,
    private val ratio: Double = 1.6,
    private val minRate: Double = 0.3,
) {
    private var lastAngle: Float? = null
    private var lastDirection = 0
    private var baselineCount = 0
    private var baselineReversals = 0
    private val window = ArrayDeque<Int>()

    val calibrated: Boolean
        get() = baselineCount >= baselineSamples

    /** 표본 하나를 넣고, 지금 흔들림 상태인지 돌려준다. */
    fun update(angleDeg: Float): Boolean {
        val reversal = isReversal(angleDeg)
        if (!calibrated) {
            baselineCount++
            if (reversal) baselineReversals++
            return false
        }
        window.addLast(if (reversal) 1 else 0)
        if (window.size > windowSamples) window.removeFirst()
        if (window.size < windowSamples) return false
        val rate = window.sum().toDouble() / windowSamples
        val usual = baselineReversals.toDouble() / baselineSamples
        return rate >= maxOf(usual * ratio, minRate)
    }

    /** 휴식 뒤에는 최근 기록만 비운다. 이번 주행의 평소 빈도는 유지한다. */
    fun clearWindow() {
        window.clear()
    }

    /** 방향을 바꾼 수정(왼쪽으로 꺾다 오른쪽으로 바로잡기)만 센다. 한 번 크게 꺾는 차선 변경은 세지 않는다. */
    private fun isReversal(angleDeg: Float): Boolean {
        val prev = lastAngle
        lastAngle = angleDeg
        if (prev == null) return false
        val delta = angleDeg - prev
        if (abs(delta) < reversalDeg) return false
        val direction = if (delta > 0) 1 else -1
        val reversed = lastDirection != 0 && direction != lastDirection
        lastDirection = direction
        return reversed
    }
}
