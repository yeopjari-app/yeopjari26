package ai.yeopjari.app

import ai.yeopjari.app.data.RestStopRepository
import ai.yeopjari.app.ports.Heard
import ai.yeopjari.app.voice.AndroidVoiceIO
import ai.yeopjari.core.AlertStage
import ai.yeopjari.core.AlertnessEngine
import ai.yeopjari.core.CheckResult
import ai.yeopjari.core.DriveEvent
import ai.yeopjari.core.DriveSessionTracker
import ai.yeopjari.core.Gear
import ai.yeopjari.core.QuestionBank
import ai.yeopjari.core.VehicleSnapshot
import ai.yeopjari.core.isYes
import ai.yeopjari.core.nearestRestStop
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.roundToInt

data class UiState(
    val accompanying: Boolean = false,
    val parked: Boolean = false,
    val stage: AlertStage = AlertStage.NORMAL,
    val speedKph: Int = 0,
    val drivingMinutes: Long = 0,
    val baselineSec: Double = 1.5,
    val lastExchange: String = "",
    val log: List<String> = emptyList(),
    val canListen: Boolean = true,
    val demoAvailable: Boolean = false,
)

/**
 * 차량 상태 → 동행 시작 → 주기적 음성 체크 → 단계 판정 → 개입(환기·휴식 장소 안내)을 잇는 흐름.
 */
class CopilotViewModel(app: Application) : AndroidViewModel(app) {
    private val platform = createPlatform(app)
    private val voice = AndroidVoiceIO(app)
    private val restStops = RestStopRepository.load(app)
    private val engine = AlertnessEngine()
    private val questions = QuestionBank()
    private val tracker = DriveSessionTracker(hourOf = ::localHour)

    private val _ui = MutableStateFlow(
        UiState(canListen = voice.canListen, demoAvailable = platform.demo != null)
    )
    val ui: StateFlow<UiState> = _ui

    private var loop: Job? = null
    private var parked = false
    private var conditionCheckPending = false
    private var freshAirOffered = false

    init {
        viewModelScope.launch {
            platform.vehicle.snapshots.collect(::onSnapshot)
        }
    }

    fun parkForRest() {
        platform.demo?.parkForRest()
    }

    fun resumeDriving() {
        platform.demo?.resumeDriving()
    }

    private fun onSnapshot(snapshot: VehicleSnapshot) {
        parked = snapshot.gear == Gear.PARK
        tracker.update(snapshot).forEach { event ->
            when (event) {
                DriveEvent.Activated -> {
                    log("동행 시작 (고속 주행 ${tracker.highSpeedMs / 60_000}분)")
                    startLoop()
                }
                DriveEvent.Parked -> log("정차 감지")
                DriveEvent.RestCredited -> {
                    engine.resetAfterRest()
                    freshAirOffered = false
                    log("휴식 인정")
                }
                DriveEvent.ResumedAfterRest -> {
                    conditionCheckPending = true
                    log("재출발 — 컨디션 확인 예정")
                }
            }
        }
        _ui.update {
            it.copy(
                accompanying = tracker.active,
                parked = parked,
                speedKph = snapshot.speedKph.roundToInt(),
                drivingMinutes = tracker.drivingMs / 60_000,
                stage = engine.stage,
                baselineSec = engine.baselineMs / 1000.0,
            )
        }
    }

    private fun startLoop() {
        if (loop?.isActive == true) return
        loop = viewModelScope.launch {
            voice.speak("장거리 운전이네요. 옆자리가 함께할게요.")
            while (isActive) {
                delay(intervalFor(engine.stage))
                if (parked) continue
                if (conditionCheckPending) {
                    conditionCheckPending = false
                    voice.speak("푹 쉬셨어요? 다시 출발해 볼게요.")
                }
                runCheck()
            }
        }
    }

    private suspend fun runCheck() {
        val question = questions.next()
        voice.speak(question.prompt)
        val heard = voice.listen(platform.config.listenTimeoutMs)
        val stage = engine.record(CheckResult(heard?.latencyMs, heard != null && question.isCorrect(heard.text)))
        _ui.update {
            it.copy(
                stage = stage,
                baselineSec = engine.baselineMs / 1000.0,
                lastExchange = describe(question.prompt, heard),
            )
        }
        log("체크: ${heard?.let { "%.1f초".format(it.latencyMs / 1000.0) } ?: "무응답"} → ${stage.label}")
        when (stage) {
            AlertStage.NORMAL -> Unit
            AlertStage.CAUTION -> offerFreshAir()
            AlertStage.DANGER -> offerRestStop()
        }
    }

    private suspend fun offerFreshAir() {
        if (freshAirOffered) return
        freshAirOffered = true
        voice.speak("조금 피곤하신 것 같아요. 환기를 켜 볼까요?")
        if (!isYes(voice.listen(platform.config.listenTimeoutMs)?.text)) return
        if (platform.cabin.freshAir()) {
            voice.speak("바깥 공기를 들여올게요.")
            log("환기 켜짐")
        } else {
            voice.speak("창문을 살짝 열어 주세요.")
            log("환기 제어 불가 — 창문 권유")
        }
    }

    private suspend fun offerRestStop() {
        val here = platform.location.current()
        val nearby = here?.let { nearestRestStop(it, restStops, platform.config.restStopRadiusKm) }
        if (nearby == null) {
            voice.speak("많이 피곤하신 것 같아요. 안전한 곳에서 꼭 쉬어 가세요.")
            log("근처 휴식 장소 없음")
            return
        }
        val km = nearby.distanceKm.roundToInt().coerceAtLeast(1)
        voice.speak("많이 피곤하신 것 같아요. ${km}킬로미터 앞 ${nearby.stop.name}에 들를까요?")
        val answer = voice.listen(platform.config.listenTimeoutMs)
        if (isYes(answer?.text) && platform.navigator.addWaypoint(nearby.stop)) {
            voice.speak("${nearby.stop.name}을 경유지로 추가했어요.")
            log("경유지 추가: ${nearby.stop.name}")
        } else {
            voice.speak("알겠어요. 조금 뒤에 다시 여쭤볼게요.")
            log("휴식 제안 보류")
        }
    }

    private fun intervalFor(stage: AlertStage): Long = when (stage) {
        AlertStage.NORMAL -> platform.config.normalIntervalMs
        AlertStage.CAUTION, AlertStage.DANGER -> platform.config.cautionIntervalMs
    }

    private fun describe(prompt: String, heard: Heard?): String =
        "Q. $prompt\nA. " + (heard?.let { "${it.text} (%.1f초)".format(it.latencyMs / 1000.0) } ?: "(무응답)")

    private fun log(message: String) {
        _ui.update { it.copy(log = (listOf(message) + it.log).take(8)) }
    }

    override fun onCleared() {
        voice.release()
        platform.release()
    }
}

private fun localHour(timeMs: Long): Int =
    Calendar.getInstance().apply { timeInMillis = timeMs }.get(Calendar.HOUR_OF_DAY)

val AlertStage.label: String
    get() = when (this) {
        AlertStage.NORMAL -> "정상"
        AlertStage.CAUTION -> "주의"
        AlertStage.DANGER -> "위험"
    }
