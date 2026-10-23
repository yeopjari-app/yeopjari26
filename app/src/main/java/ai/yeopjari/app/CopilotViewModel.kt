package ai.yeopjari.app

import ai.yeopjari.app.data.RestStopRepository
import ai.yeopjari.app.ports.ListenResult
import ai.yeopjari.app.ports.text
import ai.yeopjari.app.voice.AndroidVoiceIO
import ai.yeopjari.core.AlertStage
import ai.yeopjari.core.AlertnessEngine
import ai.yeopjari.core.CheckResult
import ai.yeopjari.core.DriveEvent
import ai.yeopjari.core.DriveSessionTracker
import ai.yeopjari.core.DriverIntent
import ai.yeopjari.core.Gear
import ai.yeopjari.core.QuestionBank
import ai.yeopjari.core.SteeringMonitor
import ai.yeopjari.core.VehicleSnapshot
import ai.yeopjari.core.Workload
import ai.yeopjari.core.WorkloadGate
import ai.yeopjari.core.detectIntent
import ai.yeopjari.core.isMeaningfulAnswer
import ai.yeopjari.core.isYes
import ai.yeopjari.core.nearestRestStop
import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar
import kotlin.math.roundToInt

data class UiState(
    val accompanying: Boolean = false,
    val parked: Boolean = false,
    val stage: AlertStage = AlertStage.NORMAL,
    val speedKph: Int = 0,
    val drivingMinutes: Long = 0,
    val baselineSec: Double = 1.5,
    val quietReason: String? = null,
    val quietMode: Boolean = false,
    val passengerPresent: Boolean = false,
    val weaving: Boolean = false,
    val napping: Boolean = false,
    val lastExchange: String = "",
    val log: List<String> = emptyList(),
    val canListen: Boolean = true,
    val demoAvailable: Boolean = false,
)

/**
 * 차량 상태 → 동행 시작 → (운전이 바쁘지 않고 조수석이 비었을 때) 가벼운 대화 → 단계 판정 →
 * 개입(환기 · 휴식 장소 안내) → 정차 후 파워냅 → 재출발 확인으로 이어지는 흐름.
 * 운전자가 "피곤해"라고 하거나 차량의 운전자 주의 경고가 울리면 바로 휴식 장소를 안내한다.
 * 조향이 평소보다 흔들리면 경고하지 않고 다음 확인을 앞당겨, 대답으로 확인한다.
 * 대답 기준선은 주행이 끝나도 저장해 다음 주행에 이어 쓴다.
 */
class CopilotViewModel(app: Application) : AndroidViewModel(app) {
    private val platform = createPlatform(app)
    private val voice = AndroidVoiceIO(app)
    private val restStops = RestStopRepository.load(app)
    private val prefs = app.getSharedPreferences("yeopjari", Context.MODE_PRIVATE)
    private val savedBaseline = loadBaseline()
    private val engine = AlertnessEngine(initialBaseline = savedBaseline)
    private val steering = SteeringMonitor(
        baselineSamples = platform.config.steeringBaselineSamples,
        windowSamples = platform.config.steeringWindowSamples,
    )
    private val questions = QuestionBank()
    private val tracker = DriveSessionTracker(hourOf = ::localHour)
    private val gate = WorkloadGate()

    /** 한 번에 한 대화만 오가게 한다(정기 체크·파워냅·차량 경고 안내가 겹치지 않도록). */
    private val voiceLock = Mutex()

    private val _ui = MutableStateFlow(
        UiState(canListen = voice.canListen, demoAvailable = platform.demo != null)
    )
    val ui: StateFlow<UiState> = _ui

    private var loop: Job? = null
    private var nap: Job? = null
    private var lastTimeMs = 0L
    private var parked = false
    private var passengerPresent = false
    private var vehicleWarning = false
    private var quietModeUntil = 0L
    private var restSuggested = false
    private var conditionCheckPending = false
    private var freshAirOffered = false
    private var micWarned = false
    private var weaving = false
    private var earlyCheckRequested = false
    private var checksThisTrip = 0
    private var wakeLevel = 0
    private var hazardOn = false

    init {
        viewModelScope.launch {
            platform.vehicle.snapshots.collect(::onSnapshot)
        }
    }

    fun laneChange() {
        platform.demo?.laneChange()
    }

    fun hardBrake() {
        platform.demo?.hardBrake()
    }

    fun togglePassenger() {
        platform.demo?.togglePassenger()
    }

    fun vehicleDrowsinessWarning() {
        platform.demo?.vehicleDrowsinessWarning()
    }

    fun weave() {
        platform.demo?.weave()
    }

    fun parkForRest() {
        platform.demo?.parkForRest()
    }

    fun resumeDriving() {
        platform.demo?.resumeDriving()
    }

    private fun onSnapshot(snapshot: VehicleSnapshot) {
        lastTimeMs = snapshot.timeMs
        parked = snapshot.gear == Gear.PARK
        val busy = gate.update(snapshot)
        tracker.update(snapshot).forEach { event ->
            when (event) {
                DriveEvent.Activated -> {
                    checksThisTrip = 0
                    log("동행 시작 (고속 주행 ${tracker.highSpeedMs / 60_000}분)")
                    startLoop()
                }
                DriveEvent.Parked -> {
                    log("정차 감지")
                    clearWake()
                    if (restSuggested || engine.stage != AlertStage.NORMAL) offerNap()
                }
                DriveEvent.RestCredited -> {
                    engine.resetAfterRest()
                    steering.clearWindow()
                    saveBaseline()
                    freshAirOffered = false
                    restSuggested = false
                    log("휴식 인정")
                }
                DriveEvent.ResumedAfterRest -> {
                    nap?.cancel()
                    conditionCheckPending = true
                    log("재출발 — 컨디션 확인 예정")
                }
            }
        }
        onPassengerChange(snapshot.passengerPresent)
        onVehicleWarning(snapshot.drowsinessWarning)
        onSteering(snapshot)
        _ui.update {
            it.copy(
                accompanying = tracker.active,
                parked = parked,
                speedKph = snapshot.speedKph.roundToInt(),
                drivingMinutes = tracker.drivingMs / 60_000,
                stage = engine.stage,
                baselineSec = engine.baselineMs / 1000.0,
                quietReason = busy.firstOrNull()?.label,
                quietMode = inQuietMode(),
                passengerPresent = passengerPresent,
                weaving = weaving,
            )
        }
    }

    /** 조향 흔들림은 경고가 아니라 "지금 한 번 물어볼 때"라는 단서로만 쓴다. */
    private fun onSteering(snapshot: VehicleSnapshot) {
        if (!tracker.active || parked) return
        val now = steering.update(snapshot.steeringAngleDeg)
        if (now && !weaving && engine.stage == AlertStage.NORMAL) {
            earlyCheckRequested = true
            log("조향 흔들림 감지 → 확인을 앞당김")
        }
        weaving = now
    }

    /** 조수석에 사람이 타면 옆자리는 물러나고, 내리면 다시 함께한다. */
    private fun onPassengerChange(present: Boolean) {
        if (present == passengerPresent) return
        passengerPresent = present
        if (!tracker.active) return
        log(if (present) "동승자 탑승 — 대화 멈춤" else "동승자 하차 — 대화 재개")
        say(if (present) "옆자리에 동승자가 계시네요. 저는 조용히 있을게요." else "다시 제가 옆자리에서 함께할게요.")
    }

    /** 차량 자체의 운전자 주의 경고가 울리면 경고음에서 끝나지 않도록 바로 휴식 장소를 안내한다. */
    private fun onVehicleWarning(active: Boolean) {
        val rising = active && !vehicleWarning
        vehicleWarning = active
        if (!rising || !tracker.active || parked) return
        engine.recordVehicleWarning()
        log("차량 졸음 경고 감지 → 위험")
        viewModelScope.launch {
            voiceLock.withLock {
                voice.speak("차량에서도 졸음 신호가 감지됐어요.")
                offerRestStop()
            }
        }
    }

    private fun say(text: String) {
        viewModelScope.launch { voiceLock.withLock { voice.speak(text) } }
    }

    private fun startLoop() {
        if (loop?.isActive == true) return
        loop = viewModelScope.launch {
            voiceLock.withLock { voice.speak("장거리 운전이네요. 옆자리가 함께할게요.") }
            while (isActive) {
                waitForNextCheck(intervalFor(engine.stage))
                if (parked || passengerPresent) continue
                waitUntilNotBusy()
                voiceLock.withLock {
                    if (conditionCheckPending) {
                        conditionCheckPending = false
                        voice.speak("푹 쉬셨어요? 다시 출발해 볼게요.")
                    }
                    runCheck()
                }
            }
        }
    }

    /** 다음 확인까지 기다리되, 조향 흔들림이 감지되면 바로 확인한다. */
    private suspend fun waitForNextCheck(intervalMs: Long) {
        var waited = 0L
        while (waited < intervalMs && !earlyCheckRequested) {
            delay(500)
            waited += 500
        }
        earlyCheckRequested = false
    }

    /** 차선 변경·급제동 등 운전이 바쁜 동안은 말을 걸지 않고 기다린다. */
    private suspend fun waitUntilNotBusy() {
        while (!gate.canTalk(lastTimeMs) || parked) delay(500)
    }

    private fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(getApplication<Application>(), Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun inQuietMode(): Boolean = SystemClock.elapsedRealtime() < quietModeUntil

    private suspend fun runCheck() {
        // 들을 수 없는 상태에서 확인을 하면 무응답이 쌓여 잘못된 위험 판정이 나온다.
        if (!voice.canListen || !hasMic()) {
            if (!micWarned) log("마이크를 쓸 수 없어 대화 확인을 쉬고 있어요")
            micWarned = true
            return
        }
        micWarned = false
        val here = platform.location.current()
        val upcoming = here?.let { nearestRestStop(it, restStops, platform.config.restStopRadiusKm) }
        val question = questions.next(upcoming?.stop?.name, checkInOnly = inQuietMode())
        voice.speak(question.prompt)
        var result = voice.listen(platform.config.listenTimeoutMs)
        var retried = false
        if (result is ListenResult.Silent) {
            // 소음이나 인식 실패일 수 있으니 바로 위험으로 올리지 않고 한 번 더 묻는다.
            voice.speak("제 말 들리세요?")
            result = voice.listen(platform.config.listenTimeoutMs)
            retried = true
        }
        val check = when (result) {
            is ListenResult.Unavailable -> {
                log("음성 인식 오류 — 이번 확인은 건너뜀")
                return
            }
            is ListenResult.Silent -> CheckResult(null, correct = false)
            // 다시 물어서야 대답했다면 그만큼 느린 대답으로 본다.
            is ListenResult.Heard -> {
                clearWake()
                CheckResult(
                    latencyMs = if (retried) platform.config.listenTimeoutMs + result.latencyMs else result.latencyMs,
                    correct = isMeaningfulAnswer(result.text),
                )
            }
        }
        val stage = engine.record(check)
        checksThisTrip++
        saveBaseline()
        _ui.update {
            it.copy(
                stage = stage,
                baselineSec = engine.baselineMs / 1000.0,
                lastExchange = describe(question.prompt, result),
            )
        }
        log("체크: ${check.latencyMs?.let { "%.1f초".format(it / 1000.0) } ?: "무응답"} → ${stage.label}")

        when (detectIntent(result.text)) {
            DriverIntent.TIRED -> {
                voice.speak("말씀해 주셔서 고마워요.")
                offerRestStop()
                return
            }
            DriverIntent.QUIET -> {
                quietModeUntil = SystemClock.elapsedRealtime() + platform.config.quietModeMs
                voice.speak("알겠어요. 한동안은 짧게 확인만 할게요.")
                log("조용히 모드")
            }
            DriverIntent.NONE -> Unit
        }
        if (stage == AlertStage.CAUTION && checksThisTrip <= 3 && savedBaseline.isNotEmpty()) {
            // 지난 주행의 평소 속도와 비교했기 때문에 알 수 있는 경우
            voice.speak("오늘은 출발부터 평소보다 대답이 느리세요.")
        }
        when (stage) {
            AlertStage.NORMAL -> Unit
            AlertStage.CAUTION -> offerFreshAir()
            AlertStage.DANGER -> offerRestStop()
        }
    }

    private fun loadBaseline(): List<Long> =
        prefs.getString(KEY_BASELINE, null)
            ?.split(",")
            ?.mapNotNull { it.toLongOrNull() }
            .orEmpty()

    private fun saveBaseline() {
        prefs.edit().putString(KEY_BASELINE, engine.baselineSamples.joinToString(",")).apply()
    }

    private suspend fun offerFreshAir() {
        if (freshAirOffered) return
        freshAirOffered = true
        voice.speak("조금 피곤하신 것 같아요. 환기를 켜 볼까요?")
        if (!isYes(voice.listen(platform.config.listenTimeoutMs).text)) return
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
        restSuggested = true
        if (nearby == null) {
            voice.speak("많이 피곤하신 것 같아요. 안전한 곳에서 꼭 쉬어 가세요.")
            log("근처 휴식 장소 없음")
            return
        }
        val km = nearby.distanceKm.roundToInt().coerceAtLeast(1)
        voice.speak("${km}킬로미터 앞 ${nearby.stop.name}에 들를까요?")
        val answer = voice.listen(platform.config.listenTimeoutMs)
        if (answer is ListenResult.Silent) {
            wakeEscalation()
            return
        }
        if (!isYes(answer.text)) {
            voice.speak("알겠어요. 조금 뒤에 다시 여쭤볼게요.")
            log("휴식 제안 보류")
        } else if (platform.navigator.addWaypoint(nearby.stop)) {
            voice.speak("${nearby.stop.name}을 경유지로 추가했어요.")
            log("경유지 추가: ${nearby.stop.name}")
        } else {
            voice.speak("${nearby.stop.name}은 ${km}킬로미터 앞이에요. 안내 표지판을 따라가 주세요.")
            log("경유지 추가 불가 — 음성 안내")
        }
    }

    /**
     * 위험 단계에서 쉼터 제안에도 대답이 없으면, 앱에 허용된 차량 기능만으로 깨운다.
     * 1단계: 알람음 + 찬 바람·통풍 시트·실내등. 2단계(그래도 무응답): 비상등 + 정차 안내.
     * 브레이크·조향·경적은 쓰지 않는다. 앱에 열려 있지 않고, 차량 자체 안전 기능의 몫이다.
     */
    private fun wakeEscalation() {
        wakeLevel++
        viewModelScope.launch {
            ring()
            val burst = platform.cabin.wakeBurst()
            log(if (burst) "깨우기 ${wakeLevel}단계: 알람·찬 바람·통풍 시트·실내등" else "깨우기 ${wakeLevel}단계: 알람")
            voiceLock.withLock {
                if (wakeLevel == 1) {
                    voice.speak("일어나세요! 지금 바로 쉬어야 해요.")
                } else {
                    if (!hazardOn && platform.cabin.hazardLights(true)) {
                        hazardOn = true
                        log("비상등 켜짐")
                    }
                    voice.speak("비상등을 켰어요. 가장 가까운 안전한 곳에 차를 세워 주세요.")
                }
            }
        }
    }

    /** 운전자가 대답하거나 차를 세우면 깨우기를 멈추고 비상등을 끈다. */
    private fun clearWake() {
        wakeLevel = 0
        if (!hazardOn) return
        hazardOn = false
        viewModelScope.launch { platform.cabin.hazardLights(false) }
        log("비상등 꺼짐")
    }

    /** 휴식 장소에 멈추면 15분 파워냅을 제안한다: 좌석 눕히기 → 환기 → 알람 → 좌석 원위치. */
    private fun offerNap() {
        if (nap?.isActive == true) return
        nap = viewModelScope.launch {
            voiceLock.withLock {
                voice.speak("잘 오셨어요. 15분만 눈 붙이고 가실래요?")
                if (!isYes(voice.listen(platform.config.listenTimeoutMs).text)) {
                    voice.speak("알겠어요. 스트레칭이라도 하고 가세요.")
                    return@launch
                }
                val reclined = platform.cabin.reclineSeat()
                platform.cabin.freshAir()
                voice.speak(if (reclined) "좌석을 눕혀 드릴게요. 15분 뒤에 깨워 드릴게요." else "좌석을 편하게 눕히세요. 15분 뒤에 깨워 드릴게요.")
            }
            log("파워냅 시작")
            _ui.update { it.copy(napping = true) }
            try {
                delay(platform.config.napMs)
                ring()
                voiceLock.withLock {
                    voice.speak("일어날 시간이에요. 좌석을 세울게요.")
                    platform.cabin.restoreSeat()
                }
                log("파워냅 끝")
            } finally {
                _ui.update { it.copy(napping = false) }
            }
        }
    }

    private suspend fun ring() {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 80) }.getOrNull() ?: return
        try {
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1_500)
            delay(1_600)
        } finally {
            tone.release()
        }
    }

    private fun intervalFor(stage: AlertStage): Long = when {
        stage != AlertStage.NORMAL -> platform.config.cautionIntervalMs
        inQuietMode() -> platform.config.normalIntervalMs * 2
        else -> platform.config.normalIntervalMs
    }

    private fun describe(prompt: String, result: ListenResult): String =
        "Q. $prompt\nA. " + when (result) {
            is ListenResult.Heard -> "${result.text} (%.1f초)".format(result.latencyMs / 1000.0)
            ListenResult.Silent -> "(무응답)"
            ListenResult.Unavailable -> "(인식 오류)"
        }

    private fun log(message: String) {
        _ui.update { it.copy(log = (listOf(message) + it.log).take(8)) }
    }

    override fun onCleared() {
        saveBaseline()
        voice.release()
        platform.release()
    }

    private companion object {
        const val KEY_BASELINE = "answer_baseline_ms"
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

val Workload.label: String
    get() = when (this) {
        Workload.LANE_CHANGE -> "차선 변경 중"
        Workload.HARD_BRAKING -> "급제동"
        Workload.SHARP_STEERING -> "큰 회전"
        Workload.JUNCTION_AHEAD -> "갈림길 접근"
    }
