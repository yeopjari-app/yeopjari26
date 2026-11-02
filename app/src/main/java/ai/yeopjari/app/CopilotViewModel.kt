package ai.yeopjari.app

import ai.yeopjari.app.data.RestStopRepository
import ai.yeopjari.app.notify.SmsFamilyNotifier
import ai.yeopjari.app.ports.DiagResult
import ai.yeopjari.app.ports.DiagSignal
import ai.yeopjari.app.ports.FamilyNotifier
import ai.yeopjari.app.ports.ListenResult
import ai.yeopjari.app.ports.text
import ai.yeopjari.app.ui.CopilotActions
import ai.yeopjari.app.voice.AndroidVoiceIO
import ai.yeopjari.core.ActivationReason
import ai.yeopjari.core.AlertStage
import ai.yeopjari.core.AlertnessEngine
import ai.yeopjari.core.AudioMode
import ai.yeopjari.core.CabinAirTracker
import ai.yeopjari.core.CheckResult
import ai.yeopjari.core.Conversation
import ai.yeopjari.core.DriveEvent
import ai.yeopjari.core.DriveSessionTracker
import ai.yeopjari.core.DriverIntent
import ai.yeopjari.core.DriverMemory
import ai.yeopjari.core.DrowsySignal
import ai.yeopjari.core.FamilyNotice
import ai.yeopjari.core.LaneAssistTracker
import ai.yeopjari.core.Gear
import ai.yeopjari.core.MediaState
import ai.yeopjari.core.Persona
import ai.yeopjari.core.QuietEnd
import ai.yeopjari.core.QuietMode
import ai.yeopjari.core.RestPlanner
import ai.yeopjari.core.SleepAnswer
import ai.yeopjari.core.FatigueForecast
import ai.yeopjari.core.SleepBand
import ai.yeopjari.core.SteeringMonitor
import ai.yeopjari.core.TalkDecision
import ai.yeopjari.core.TalkPolicy
import ai.yeopjari.core.TripRecorder
import ai.yeopjari.core.TripSummary
import ai.yeopjari.core.TurnContext
import ai.yeopjari.core.TurnKind
import ai.yeopjari.core.VehicleSnapshot
import ai.yeopjari.core.WakeAction
import ai.yeopjari.core.WaitReason
import ai.yeopjari.core.Workload
import ai.yeopjari.core.WorkloadGate
import ai.yeopjari.core.detectIntent
import ai.yeopjari.core.isMeaningfulAnswer
import ai.yeopjari.core.isYes
import ai.yeopjari.core.distanceKm
import ai.yeopjari.core.lastStopBeforeGap
import ai.yeopjari.core.nextRestStop
import ai.yeopjari.core.particle
import ai.yeopjari.core.restPlanLine
import ai.yeopjari.core.spokenAmenities
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
import ai.yeopjari.core.CoffeeNap
import ai.yeopjari.core.RestStop
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import kotlin.math.roundToInt

/** 화면 대화 목록의 한 줄(Pleos 대화 화면처럼 운전자는 오른쪽, 옆자리는 왼쪽). */
data class ChatLine(val fromDriver: Boolean, val text: String, val time: String, val latencySec: Double? = null)

/** 옆자리가 제안한 휴식 장소(번호 붙은 선택 카드로 보인다). */
data class RestOption(val name: String, val km: Int, val amenities: String?, val waypointAdded: Boolean = false)

/** 무응답 깨우기 때 화면 목록에 올라가는 동작 이름. 화면은 이 값과 같은지로 ✓ 를 켠다. */
const val WAKE_WINDOW = "운전석 창문 2~3cm 열기"
const val WAKE_COLD_AIR = "찬 바람 · 최대"
const val WAKE_SEAT = "통풍 시트 · 열선 끄기"
const val WAKE_VOICE = "목소리 전환 · 열혈 교관"
const val WAKE_FAMILY = "가족에게 연락"

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
    /** 차로 유지 보조(LKA·LFA)가 켜져 있어 조향 대신 대답으로만 판단하는 중인지. */
    val laneAssist: Boolean = false,
    val napping: Boolean = false,
    val musicPlaying: Boolean = false,
    val nowPlaying: String? = null,
    val inCall: Boolean = false,
    val waitingFor: String? = null,
    /** 대화 중 음악을 어떻게 했는지(줄임·멈춤). 대화가 끝나면 null. */
    val mediaHeld: AudioMode? = null,
    val remembered: String = "",
    val lastExchange: String = "",
    val log: List<String> = emptyList(),
    val canListen: Boolean = true,
    val demoAvailable: Boolean = false,
    /** 가족 알림을 받을 번호(정리된 숫자). 없으면 문자를 보내지 않는다. */
    val familyPhone: String? = null,
    /** 차량 연결 점검 화면을 쓸 수 있는지(Pleos 빌드). */
    val diagAvailable: Boolean = false,
    /** 점검 화면이 열려 있으면 그 내용. */
    val diag: DiagState? = null,
    /** "오늘의 운행" 요약 줄. 정차하거나 동행이 끝나면 화면에 보인다. 지난 주행 요약은 다시 열어도 남는다. */
    val tripSummary: List<Pair<String, String>> = emptyList(),
    /** 고른 목소리 캐릭터, 그리고 지금 말하는 목소리(깨우기 중에는 열혈 교관). */
    val persona: Persona = Persona.FRIEND,
    val voicePersona: Persona = Persona.FRIEND,
    val speaking: Boolean = false,
    /** 판정에 쓴 가장 최근 대답 시간(초). */
    val lastLatencySec: Double? = null,
    /** 파워냅이 끝나는 시각(SystemClock.elapsedRealtime)과 전체 길이. 화면의 남은 시간 링에 쓴다. */
    val napEndsAtMs: Long? = null,
    val napTotalMs: Long = 0,
    /** 최근 대화(오래된 것부터, 최대 [CHAT_LINES]줄). */
    val chat: List<ChatLine> = emptyList(),
    /** 운전자의 대답을 듣는 중. */
    val listening: Boolean = false,
    val restOption: RestOption? = null,
    /** 최근 대답 시간 7개(초). 무응답은 -1.0. 동행을 시작하면 비운다. */
    val latencyHistory: List<Double> = emptyList(),
    /** 이번 깨우기 단계에서 실행한 동작 이름([WAKE_WINDOW] 등). 정상으로 돌아오거나 쉬면 비운다. */
    val wakeActions: List<String> = emptyList(),
    /** 휴식 시작 문자를 가족에게 보냈다. */
    val familyNotified: Boolean = false,
    /** 최근 핸들 각도(도) 표본, 오래된 것부터. 스냅샷마다 쌓는다(SteeringMonitor 는 표본을 내보내지 않는다). */
    val steerTrace: List<Float> = emptyList(),
    /** 출발할 때 정한 다음 휴식 시점(연속 주행 분). 계획이 없으면 null. */
    val restPlanMin: Int? = null,
    /** 커피 냅: 커피를 사러 간 동안. 화면에 "파워냅 시작" 버튼이 보인다. */
    val coffeeWaiting: Boolean = false,
    /** 내기 순환이 오래 이어져 바깥 공기로 바꿨다(또는 바꿔 달라고 했다). */
    val staleAirFixed: Boolean = false,
    /** 목소리로 대화하는 데 문제가 있으면 무엇을 고치면 되는지(화면 위 안내). 비면 문제 없음. */
    val voiceIssues: List<String> = emptyList(),
    /** 졸음 예보: (출발 기준 분, KSS) 5분 간격. 출발 때 잠 질문에 답하면 채워진다. */
    val forecast: List<Pair<Int, Float>> = emptyList(),
    /** 예보를 그린 출발 시각(자정부터 분). */
    val forecastDepartMin: Int? = null,
)

/** 점검 화면: 신호는 1초마다 새로 읽고, 제어는 누를 때마다 결과를 남긴다. 시험 중인 제어는 [running]. */
data class DiagState(
    val signals: List<DiagSignal> = emptyList(),
    val controls: List<String> = emptyList(),
    val results: Map<String, DiagResult> = emptyMap(),
    val running: String? = null,
)

const val CHAT_LINES = 8
const val LATENCY_HISTORY = 7
const val STEER_TRACE = 40

/**
 * 차량 상태 → 동행 시작 → (운전이 바쁘지 않고 조수석이 비었을 때) 대화 → 단계 판정 →
 * 개입(환기 · 휴식 장소 안내) → 정차 후 파워냅 → 재출발 확인으로 이어지는 흐름.
 * 운전자가 "피곤해"라고 하거나 차량의 운전자 주의 경고가 울리면 바로 휴식 장소를 안내한다.
 * 조향이 평소보다 흔들리면 경고하지 않고 다음 확인을 앞당겨, 대답으로 확인한다.
 * "조용히 해 줘"라고 하면 먼저 말을 걸지 않지만 조향·차량 경고는 계속 보고, 신호가 보이면 바로 돌아온다([QuietMode]).
 * 대답 기준선과 운전자 이야기(응원하는 팀·좋아하는 간식)는 주행이 끝나도 저장해 다음 주행에 이어 쓴다.
 *
 * 음악이 나오면 말할 때 소리를 줄이고 들을 때 잠깐 멈추며, 정상 단계에서는 곡 사이까지 기다린다.
 * 통화 중에는 끼어들지 않는다([TalkPolicy]).
 */
class CopilotViewModel(app: Application) : AndroidViewModel(app), CopilotActions {
    private val platform = TestHooks.platform?.invoke(app) ?: createPlatform(app)
    private val voice = TestHooks.voice?.invoke(app) ?: AndroidVoiceIO(app)
    /**
     * 휴식 장소. 처음에는 작은 샘플을 바로 쓰고, 공공데이터 CSV(수천 줄)는 메인 스레드 밖에서 읽어 바꿔 끼운다.
     * 읽지 못하면 샘플을 그대로 쓴다.
     */
    @Volatile private var restStops: List<RestStop> = RestStopRepository.loadSamples(app)
    private val prefs = app.getSharedPreferences("yeopjari", Context.MODE_PRIVATE)
    private val savedBaseline = loadBaseline()
    private val engine = AlertnessEngine(initialBaseline = savedBaseline)
    private val steering = SteeringMonitor(
        baselineSamples = platform.config.steeringBaselineSamples,
        windowSamples = platform.config.steeringWindowSamples,
    )
    private val conversation = Conversation(memory = DriverMemory.parse(prefs.getString(KEY_MEMORY, null)))
    private val talkPolicy = TalkPolicy(
        songGapWaitMs = platform.config.songGapWaitMs,
        interactionGraceMs = platform.config.interactionGraceMs,
        maxDeferMs = platform.config.maxDeferMs,
    )
    private val tracker = DriveSessionTracker(hourOf = ::localHour)
    private val gate = WorkloadGate()
    private val lane = LaneAssistTracker()
    private val planner = RestPlanner()
    private val cabinAir = CabinAirTracker()
    /** 어젯밤 잠(출발할 때 한 번 묻는다). */
    private var sleep = SleepBand.UNKNOWN
    /** 졸음 예보에서 위험이 처음 오는 시각(자정부터 분). 깨운 뒤 "예보대로"를 말할지 정하는 데 쓴다. */
    private var forecastRiskMinuteOfDay: Int? = null
    /** 다음 휴식 약속(연속 주행 분)과, 그 시간이 다가와 이미 알렸는지. */
    private var restPlanMin: Int? = null
    private var planReminded = false
    private var coffeeDone: CompletableDeferred<Unit>? = null
    private val quiet = QuietMode(platform.config.quietModeMs, platform.config.quietModeRiskyMs)
    private val family: FamilyNotifier = TestHooks.family?.invoke(app) ?: SmsFamilyNotifier(app, prefs)
    private var persona = Persona.parse(prefs.getString(KEY_PERSONA, null))
    /** 동행이 시작될 때마다 새로 만든다(앞 주행과 섞이지 않도록). */
    private var trip = TripRecorder()

    /** 한 번에 한 대화만 오가게 한다(정기 체크·파워냅·차량 경고 안내가 겹치지 않도록). */
    private val voiceLock = Mutex()

    private val _ui = MutableStateFlow(
        UiState(
            canListen = voice.canListen,
            demoAvailable = platform.demo != null,
            diagAvailable = platform.diagnostics != null,
            remembered = rememberedText(),
            familyPhone = family.phone,
            tripSummary = TripSummary.parseRows(prefs.getString(KEY_TRIP, null)),
            persona = persona,
            voicePersona = persona,
        )
    )
    val ui: StateFlow<UiState> = _ui

    private var loop: Job? = null
    private var nap: Job? = null
    private var lastTimeMs = 0L
    private var parked = false
    private var passengerPresent = false
    private var vehicleWarning = false
    /** 조용히 모드가 왜 끝났는지. 다음 대화의 첫마디를 고르는 데 쓴다. */
    private var quietEnded: QuietEnd? = null
    private var restSuggested = false
    private var conditionCheckPending = false
    private var freshAirOffered = false
    private var micWarned = false
    /** 파워냅 중 출발해 좌석을 못 세웠으면 다음 정차 때 세운다(달리는 중에는 좌석을 움직이지 않는다). */
    private var seatRestorePending = false
    /** 깨우기 2단계를 마지막으로 울린 때(elapsedRealtime). 계속 무응답이어도 [WAKE_REPEAT_MS]에 한 번만 다시 울린다. */
    private var lastLevel2AtMs: Long? = null
    /** 화면의 빠른 대답·입력칸으로 들어온 대답. 듣는 동안만 열려 있다. */
    private var typed: CompletableDeferred<String>? = null
    /** 이번 실행에서 마이크로 대답을 한 번이라도 알아들었는지. 그 전의 무응답은 마이크 문제일 수 있어 판정하지 않는다. */
    private var voiceVerified = false
    /**
     * 시연 빌드에서 화면 대답(빠른 대답·입력칸)으로 한 번이라도 대답했는지. 그 뒤로는 화면 대답이 대답하는 길이므로,
     * 마이크 없이 시연해도 대답하지 않으면 무응답으로 본다(주의 → 위험 → 깨우기 흐름을 그대로 볼 수 있게).
     * 실제 차(Pleos 빌드)에서는 운전 중 화면을 누르라고 하지 않으므로 쓰지 않는다.
     */
    private var screenAnswered = false

    /** 쉬자는 제안을 "아직"이라고 미루면, 이 운전 시간(ms)까지는 확인을 더 자주 한다. 휴식이 인정되면 지운다. */
    private var moreOftenUntilDrivingMs: Long? = null

    private fun screenReplyProven(): Boolean = platform.demo != null && screenAnswered
    /** 경유지로 안내한 휴식 장소(가게 정보 등). */
    private var lastStop: RestStop? = null
    private var weaving = false
    private var earlyCheckRequested = false
    private var storyRequested = false
    private var checksThisTrip = 0
    private var wakeLevel = 0
    private var hazardOn = false
    private var windowCracked = false
    private var raining = false
    private var remainingKm: Double? = null
    /** 마지막으로 안내한 휴식 장소. 운행 요약과 가족 문자에 쓴다. */
    private var lastStopName: String? = null
    private var familyNotified = false
    /** 깨워도 대답이 없어 가족에게 위급 문자를 보냈는지. 다시 대답하면 안심 문자를 보내고 내린다. */
    private var familyAlerted = false
    /** 마지막 위급 문자 시각(elapsedRealtime). 한 운행에서 30분에 한 번까지만 보낸다. */
    private var familyAlertAtMs: Long? = null
    private val steerBuf = ArrayDeque<Float>()

    init {
        if (!platform.config.sampleRestStops) {
            val fallback = restStops
            viewModelScope.launch(Dispatchers.IO) {
                restStops = RestStopRepository.loadOrFallback(app, samplesOnly = false, fallback = fallback)
            }
        }
        voice.setStyle(persona.pitch, persona.rate)
        refreshVoiceStatus()
        viewModelScope.launch {
            platform.vehicle.snapshots.collect { onSnapshot(it) }
        }
        viewModelScope.launch {
            while (isActive) {
                remainingKm = platform.navigator.remainingKm()
                delay(5_000)
            }
        }
    }

    /** 커피 냅: 커피를 마셨으니 바로 재워 달라. */
    override fun startNapNow() {
        coffeeDone?.complete(Unit)
    }

    /** 화면의 빠른 대답·입력칸. 듣는 중일 때만 대답으로 쓰인다(목소리 대신이 아니라 함께). */
    override fun quickReply(text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) typed?.complete(t)
    }

    /**
     * 목소리 대화가 되는지 점검해 화면에 고칠 방법을 띄운다: 마이크 권한, 음성 인식 서비스, 한국어 TTS.
     * 권한을 새로 받은 뒤(MainActivity)에도 다시 부른다.
     */
    override fun refreshVoiceStatus() {
        viewModelScope.launch {
            val issues = mutableListOf<String>()
            if (!hasMic()) issues += "마이크 권한이 꺼져 있어요 · 설정 → 앱 → 옆자리 → 권한 → 마이크 허용"
            if (!voice.canListen) issues += "음성 인식 서비스가 없어요 · Google 앱(음성 인식)을 설치하거나 업데이트해 주세요"
            if (!voice.koreanTtsReady()) issues += "한국어 음성(TTS)이 없어요 · 설정 → 시스템 → 언어 → 텍스트 음성 변환에서 한국어 설치"
            _ui.update { s -> s.copy(voiceIssues = issues + s.voiceIssues.filter { it.startsWith(SILENT_HINT) }) }
        }
    }

    /** 화면의 "같이 가기": 언제든 바로 함께한다. */
    override fun startNow() {
        if (!tracker.requestActivation(ActivationReason.REQUESTED)) return
        onActivated()
    }

    /** 화면의 "재밌는 얘기" (음성으로 "재밌는 얘기 해 줘"라고 해도 된다). */
    override fun requestStory() {
        storyRequested = true
        earlyCheckRequested = true
        startNow()
    }

    override fun laneChange() {
        platform.demo?.laneChange()
    }

    override fun hardBrake() {
        platform.demo?.hardBrake()
    }

    override fun togglePassenger() {
        platform.demo?.togglePassenger()
    }

    override fun vehicleDrowsinessWarning() {
        platform.demo?.vehicleDrowsinessWarning()
    }

    override fun weave() {
        platform.demo?.weave()
    }

    override fun toggleLaneAssist() {
        platform.demo?.toggleLaneAssist()
    }

    override fun toggleRecirculation() {
        platform.demo?.toggleRecirculation()
    }

    override fun laneIntervention() {
        platform.demo?.laneIntervention()
    }

    override fun handsOffWarning() {
        platform.demo?.handsOffWarning()
    }

    override fun cameraDrowsiness() {
        platform.demo?.cameraDrowsiness()
    }

    /** 시연의 "휴식 정차": 쉬려고 세운 것이므로, 휴식을 제안한 적이 없어도 정차하면 파워냅을 권한다. */
    override fun parkForRest() {
        if (platform.demo == null) return
        if (tracker.active) restSuggested = true
        platform.demo?.parkForRest()
    }

    override fun resumeDriving() {
        platform.demo?.resumeDriving()
    }

    override fun toggleMusic() {
        platform.demo?.toggleMusic()
    }

    override fun skipTrack() {
        platform.demo?.skipTrack()
    }

    override fun togglePhoneCall() {
        platform.demo?.togglePhoneCall()
    }

    /** 화면의 "목소리": 캐릭터를 바꾸고, 정차 중이거나 동행 전이면 한마디 들려준다. */
    override fun setPersona(persona: Persona) {
        this.persona = persona
        prefs.edit().putString(KEY_PERSONA, persona.name).apply()
        voice.setStyle(persona.pitch, persona.rate)
        _ui.update { it.copy(persona = persona, voicePersona = persona) }
        log("목소리: ${persona.label}")
        if (!tracker.active || parked) viewModelScope.launch { exchange { speak(persona.intro) } }
    }

    private var diagJob: Job? = null

    override fun openDiagnostics() {
        val diag = platform.diagnostics ?: return
        _ui.update { it.copy(diag = DiagState(signals = diag.signals(), controls = diag.controls)) }
        diagJob?.cancel()
        diagJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                _ui.update { s -> s.diag?.let { s.copy(diag = it.copy(signals = diag.signals())) } ?: s }
            }
        }
    }

    override fun closeDiagnostics() {
        diagJob?.cancel()
        _ui.update { it.copy(diag = null) }
    }

    override fun testControl(control: String) {
        val diag = platform.diagnostics ?: return
        if (_ui.value.diag?.running != null) return
        _ui.update { s -> s.copy(diag = s.diag?.copy(running = control)) }
        viewModelScope.launch {
            val result = runCatching { diag.test(control) }.getOrElse { DiagResult(false, it.message ?: it.javaClass.simpleName) }
            log("점검 · $control: ${if (result.ok) "성공" else "실패"} ${result.detail}")
            _ui.update { s -> s.copy(diag = s.diag?.let { it.copy(results = it.results + (control to result), running = null) }) }
        }
    }

    /** 화면의 "가족 번호": 휴식 시작·재출발 때 문자를 받을 번호. 빈 값이나 잘못된 번호면 알림을 끈다. */
    override fun setFamilyPhone(input: String) {
        if (input.isNotBlank() && FamilyNotice.normalizePhone(input) == null) {
            log("번호 형식 오류 — 휴대전화 번호를 입력해 주세요")
            return
        }
        val phone = family.setPhone(input)
        _ui.update { it.copy(familyPhone = phone) }
        log(if (phone != null) "가족 알림 번호 등록" else "가족 알림 꺼짐")
    }

    private fun onSnapshot(snapshot: VehicleSnapshot) {
        lastTimeMs = snapshot.timeMs
        if (tracker.active) trip.tick(snapshot.timeMs)
        val wasParked = parked
        parked = snapshot.gear == Gear.PARK
        if (wasParked && !parked) {
            // 다시 출발하면 낮잠·커피 기다림을 바로 멈춘다: 달리는 중에 알람이 울리거나 좌석이 움직이지 않게.
            if (nap?.isActive == true) log("출발 — 파워냅 취소")
            nap?.cancel()
            coffeeDone?.cancel()
            lastStop = null
            // 휴식 문자를 보낸 뒤 다시 출발하면(휴식 인정 시간 전이라도) 한 번만 알린다.
            if (familyNotified) {
                familyNotified = false
                _ui.update { it.copy(familyNotified = false) }
                viewModelScope.launch { if (family.send(FamilyNotice.resumed(lastStopName))) log("가족에게 재출발 문자 보냄") }
            }
        }
        if (!wasParked && parked && seatRestorePending) {
            seatRestorePending = false
            viewModelScope.launch { platform.cabin.restoreSeat(); log("정차 — 좌석 원위치") }
        }
        raining = snapshot.wipersOn
        val busy = gate.update(snapshot)
        val longTrip = (remainingKm ?: 0.0) >= platform.config.longTripKm
        tracker.update(snapshot, longTrip).forEach { event ->
            when (event) {
                DriveEvent.Activated -> onActivated()
                DriveEvent.Parked -> {
                    log("정차 감지")
                    _ui.update { it.copy(restOption = null) }
                    clearWake()
                    publishTrip()
                    if (restSuggested || engine.stage != AlertStage.NORMAL) offerNap()
                }
                DriveEvent.RestCredited -> {
                    _ui.update { it.copy(restOption = null) }
                    clearWake()
                    engine.resetAfterRest()
                    steering.clearWindow()
                    saveBaseline()
                    freshAirOffered = false
                    restSuggested = false
                    moreOftenUntilDrivingMs = null
                    if (restPlanMin != null) {
                        restPlanMin = planner.nextAfterRestMin(sleep, localHour(snapshot.timeMs))
                        planReminded = false
                        _ui.update { it.copy(restPlanMin = restPlanMin) }
                    }
                    log("휴식 인정")
                }
                DriveEvent.ResumedAfterRest -> {
                    nap?.cancel()
                    lastStop = null
                    conditionCheckPending = true
                    log("재출발 — 컨디션 확인 예정")

                }
            }
        }
        onPassengerChange(snapshot.passengerPresent)
        onVehicleWarning(snapshot.drowsinessWarning || snapshot.cameraDrowsiness, camera = snapshot.cameraDrowsiness)
        onLaneAssist(snapshot)
        onHandsOff(snapshot.handsOffWarning)
        onSteering(snapshot)
        if (tracker.active && cabinAir.update(snapshot, localHour(snapshot.timeMs))) onStaleAir(cabinAir.minutes(snapshot.timeMs))
        if (tracker.active && !parked) {
            steerBuf.addLast(snapshot.steeringAngleDeg)
            if (steerBuf.size > STEER_TRACE) steerBuf.removeFirst()
        } else if (steerBuf.isNotEmpty()) steerBuf.clear()
        val media = mediaNow()
        _ui.update {
            it.copy(
                steerTrace = steerBuf.toList(),
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
                laneAssist = lane.assistOn,
                musicPlaying = media.playing,
                nowPlaying = media.title,
                inCall = media.inCall,
            )
        }
    }

    private fun onActivated() {
        checksThisTrip = 0
        restPlanMin = null
        planReminded = false
        moreOftenUntilDrivingMs = null
        sleep = SleepBand.UNKNOWN
        forecastRiskMinuteOfDay = null
        gapWarnedFor = null
        pendingGap = null
        restSuggested = false
        trip = TripRecorder().apply { start(lastTimeMs) }
        steerBuf.clear()
        _ui.update { it.copy(chat = emptyList(), lastLatencySec = null, restOption = null, latencyHistory = emptyList(), wakeActions = emptyList(), steerTrace = emptyList(), restPlanMin = null, staleAirFixed = false, forecast = emptyList(), forecastDepartMin = null) }
        val reason = tracker.activationReason
        log("동행 시작 (${reason?.label ?: ""})")
        startLoop(greeting(reason))
    }

    /**
     * 졸리기 전에 쉴 때를 정한다. 어젯밤 몇 시간 잤는지 한 번 묻고, 잠·시간대·남은 거리로 첫 휴식 시점을 잡는다([RestPlanner]).
     * 이 대답은 대화이므로 대답 속도를 판정에 쓰지 않는다. 쉬기 전에 도착하는 짧은 길이면 계획하지 않는다.
     */
    private suspend fun planRest() {
        speak("어젯밤엔 몇 시간쯤 주무셨어요?")
        val answer = ask()
        val heard = (answer as? ListenResult.Heard)?.text
        sleep = heard?.let { SleepAnswer.parse(it) } ?: SleepBand.UNKNOWN
        // 졸음 예보: 잠·일어난 시각(평소 기상 시각)·지금 시각으로 이번 주행의 졸음을 미리 그린다.
        val depart = localMinuteOfDay(lastTimeMs)
        val tripMin = ((remainingKm ?: DEFAULT_FORECAST_KM) / AVG_KPH * 60).roundToInt()
        val fc = FatigueForecast.forecast(heard?.let { SleepAnswer.hours(it) }, platform.config.usualWakeMinute, depart, tripMin)
        forecastRiskMinuteOfDay = fc.riskAt?.let { Math.floorMod(depart + it, 1440) }
        _ui.update { it.copy(forecast = fc.points.map { p -> p.minute to p.kss.toFloat() }, forecastDepartMin = depart) }
        val plan = planner.plan(sleep, localHour(lastTimeMs), remainingKm, fc)   // 짧은 길이어도 도착 전 예보 위험이 있으면 계획한다
        log("어젯밤 잠: ${sleep.label}" + (fc.riskAt?.let { " · 예보: ${FatigueForecast.spokenClock(depart + it)} 위험" } ?: "") +
            (plan?.let { " → ${it.firstRestMin}분 뒤 휴식 약속" } ?: " → 휴식 전에 도착"))
        if (plan == null) {
            speak("목적지가 멀지 않네요. 피곤하면 언제든 말씀하세요.")
            return
        }
        // 약속은 "지금부터 N분 뒤"이고, 비교는 휴식 뒤부터 센 운전 시간(drivingMs)과 하므로 지금까지 달린 시간을 더한다.
        restPlanMin = (tracker.drivingMs / 60_000).toInt() + plan.firstRestMin
        planReminded = false
        _ui.update { it.copy(restPlanMin = restPlanMin) }
        speak(restPlanLine(sleep, plan, depart))
    }

    /** 이미 안내한 "마지막 쉼터" 이름(같은 곳을 두 번 말하지 않는다). */
    private var gapWarnedFor: String? = null
    private var pendingGap: ai.yeopjari.core.RestGap? = null

    /**
     * 한 시간 넘게 달렸고 다음 쉼터를 지나면 한참 쉴 곳이 없으면, 그 쉼터가 가까워졌을 때 미리 알린다.
     * 졸리기 전에 "여기가 마지막 기회"를 알려 주는 쪽이 지나친 뒤 되돌아가는 것보다 낫다.
     */
    private suspend fun gapWarningDue(): Boolean {
        pendingGap = null
        if (restSuggested || parked || tracker.drivingMs < GAP_WARN_AFTER_MIN * 60_000L || engine.stage != AlertStage.NORMAL) return false
        val here = platform.location.current() ?: return false
        val gap = lastStopBeforeGap(here, platform.location.headingDeg(), restStops, platform.config.restStopRadiusKm) ?: return false
        // 다음 확인 전에 지나쳐 버리지 않도록, 다음 확인까지 달릴 거리만큼 앞에서 알린다.
        val reach = (GAP_WARN_KM + _ui.value.speedKph / 60.0 * intervalFor(engine.stage) / 60_000).coerceAtMost(platform.config.restStopRadiusKm)
        if (gap.next.stop.name == gapWarnedFor || gap.next.distanceKm > reach) return false
        if (remainingKm?.let { it < gap.next.distanceKm + gap.gapKm } == true) return false
        pendingGap = gap
        return true
    }

    private suspend fun offerLastStopBeforeGap() {
        val gap = pendingGap ?: return
        gapWarnedFor = gap.next.stop.name
        val km = gap.next.distanceKm.roundToInt().coerceAtLeast(1)
        speak("${km}킬로미터 앞 ${particle(gap.next.stop.name, "을", "를")} 지나면 ${gap.gapKm.roundToInt()}킬로미터 넘게 쉴 곳이 없어요.")
        log("마지막 쉼터 안내: ${gap.next.stop.name} 뒤로 ${gap.gapKm.roundToInt()}km 이상 없음")
        offerRestStop()
    }

    /** 약속한 휴식 [REST_PLAN_LEAD_MIN]분 전부터, 아직 쉼터를 권하지 않았으면 알린다. */
    private fun restPlanDue(): Boolean {
        val due = restPlanMin ?: return false
        if (planReminded || restSuggested || parked || engine.stage != AlertStage.NORMAL) return false
        return tracker.drivingMs >= (due - REST_PLAN_LEAD_MIN) * 60_000L
    }

    /**
     * 내기 순환이 오래 이어지면 바깥 공기로 바꾼다([CabinAirTracker]). 졸음 판정과는 별개인 환기 습관이라
     * 조용히 모드에서는 말없이 바꾸기만 하고, 제어가 안 되면 바꿔 달라고 부탁한다.
     */
    private fun onStaleAir(minutes: Long) {
        viewModelScope.launch {
            val ok = platform.cabin.freshAir()
            _ui.update { it.copy(staleAirFixed = ok) }
            log("내기 순환 ${minutes}분 → " + if (ok) "외기로 전환" else "제어 불가 — 음성 권유")
            if (inQuietMode() && ok) return@launch
            waitWhileInCall()
            waitUntilNotBusy()
            exchange {
                speak(
                    if (ok) "내기 순환이 ${minutes}분째라 바깥 공기로 바꿀게요. 실내 공기가 탁해지면 머리가 무거워질 수 있어요."
                    else "내기 순환이 ${minutes}분째예요. 잠깐 바깥 공기로 바꿔 주실래요?",
                )
            }
        }
    }

    /** 왜 함께하게 됐는지에 맞춰 첫인사를 고른다. 차량 경고로 시작했으면 인사 없이 바로 안내한다. */
    private fun greeting(reason: ActivationReason?): String? = when (reason) {
        ActivationReason.LONG_TRIP -> "목적지까지 ${remainingKm?.roundToInt() ?: 0}킬로미터, 먼 길이네요. 옆자리가 함께할게요."
        ActivationReason.REQUESTED -> "좋아요, 옆자리가 함께할게요."
        ActivationReason.VEHICLE_WARNING -> null
        ActivationReason.HIGHWAY, null -> "장거리 운전이네요. 옆자리가 함께할게요."
    }

    /**
     * 차로 유지 보조가 켜져 있으면 조향각은 차의 수정이 섞여 운전자 상태를 보여 주지 않으므로 흔들림 단서를 쓰지 않는다.
     * 차가 차로 이탈을 막으려고 개입하면 흔들림과 같이 "지금 확인할 때"로 본다.
     */
    private fun onLaneAssist(snapshot: VehicleSnapshot) {
        val wasOn = lane.assistOn
        val intervened = lane.update(snapshot)
        if (lane.assistOn != wasOn && tracker.active) log(if (lane.assistOn) "차로 유지 보조 켜짐 — 조향 대신 대답으로 판단" else "차로 유지 보조 꺼짐 — 조향 단서 다시 사용")
        if (!intervened || !tracker.active || parked) return
        trip.signal(DrowsySignal.LANE_INTERVENTION)
        earlyCheckRequested = true
        if (quiet.interrupt(QuietEnd.LANE_INTERVENTION)) {
            quietEnded = QuietEnd.LANE_INTERVENTION
            log("조용히 모드 중 차로 이탈 보조 개입 → 바로 확인")
        } else {
            log("차로 이탈 보조 개입 → 확인을 앞당김")
        }
    }

    private var handsOff = false

    /**
     * 차로 유지 보조가 핸들을 잡고 있는 동안 운전자 손이 떨어졌다는 경고. 조향으로는 졸음을 알 수 없는 상황이라
     * 바로 확인한다(대답이 느리거나 없으면 평소 단계대로 올라간다). 차가 스스로 "핸들을 잡으세요"를 알리므로 따로 경고하지 않는다.
     */
    private fun onHandsOff(active: Boolean) {
        val rising = active && !handsOff
        handsOff = active
        if (!rising || !tracker.active || parked) return
        trip.signal(DrowsySignal.HANDS_OFF)
        earlyCheckRequested = true
        if (quiet.interrupt(QuietEnd.HANDS_OFF)) {
            quietEnded = QuietEnd.HANDS_OFF
            log("조용히 모드 중 핸들 놓음 경고 → 바로 확인")
        } else {
            log("핸들 놓음 경고 → 확인을 앞당김")
        }
    }

    /** 조향 흔들림은 경고가 아니라 "지금 한 번 물어볼 때"라는 단서로만 쓴다. */
    private fun onSteering(snapshot: VehicleSnapshot) {
        if (!tracker.active || parked) return
        if (!lane.steeringTrusted) {
            weaving = false
            return
        }
        val now = steering.update(snapshot.steeringAngleDeg)
        if (now && !weaving) trip.signal(DrowsySignal.WEAVING)
        if (now && !weaving && engine.stage == AlertStage.NORMAL) {
            earlyCheckRequested = true
            if (quiet.interrupt(QuietEnd.WEAVING)) {
                quietEnded = QuietEnd.WEAVING
                log("조용히 모드 중 조향 흔들림 → 바로 확인")
            } else {
                log("조향 흔들림 감지 → 확인을 앞당김")
            }
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

    /**
     * 차량 자체의 운전자 주의 경고가 울리면 경고음에서 끝나지 않도록 바로 휴식 장소를 안내한다.
     * 아직 함께하기 전이었다면 지금 시작한다. 통화 중이면 통화가 끝난 뒤 안내한다.
     */
    /** 차량 졸음 경고(운전자 주의 경고 또는 실내 카메라 DMS). 차가 먼저 알아챈 것이므로 바로 위험 단계. */
    private fun onVehicleWarning(active: Boolean, camera: Boolean = false) {
        val rising = active && !vehicleWarning
        vehicleWarning = active
        if (!rising || parked) return
        if (tracker.requestActivation(ActivationReason.VEHICLE_WARNING)) onActivated()
        if (quiet.interrupt(QuietEnd.VEHICLE_WARNING)) log("조용히 모드 중 차량 경고 → 바로 안내")
        engine.recordVehicleWarning()
        trip.signal(if (camera) DrowsySignal.CAMERA_DROWSINESS else DrowsySignal.VEHICLE_WARNING)
        trip.stage(AlertStage.DANGER)
        log(if (camera) "실내 카메라 졸음 경고 감지 → 위험" else "차량 졸음 경고 감지 → 위험")
        viewModelScope.launch {
            waitWhileInCall()
            exchange {
                speak("차량에서도 졸음 신호가 감지됐어요.")
                offerRestStop()
            }
        }
    }

    private fun say(text: String) {
        viewModelScope.launch {
            waitWhileInCall()
            exchange { speak(text) }
        }
    }

    private fun startLoop(greeting: String?) {
        if (loop?.isActive == true) return
        loop = viewModelScope.launch {
            if (greeting != null) {
                awaitTalkWindow()
                exchange {
                    speak(greeting)
                    if (platform.config.restPlanning && tracker.activationReason != ActivationReason.VEHICLE_WARNING) planRest()
                }
            }
            while (isActive) {
                waitForNextCheck(intervalFor(engine.stage))
                if (parked || passengerPresent || silentlyWatching()) continue
                waitUntilNotBusy()
                awaitTalkWindow()
                waitUntilNotBusy()
                exchange {
                    if (conditionCheckPending) {
                        conditionCheckPending = false
                        speak("푹 쉬셨어요? 다시 출발해 볼게요.")
                    }
                    comebackLine()?.let { speak(it) }
                    if (gapWarningDue()) {
                        offerLastStopBeforeGap()
                    } else if (restPlanDue()) {
                        planReminded = true
                        speak("약속한 휴식 시간이 다가와요.")
                        log("휴식 약속 시간 — 쉼터 안내")
                        offerRestStop()
                    } else {
                        runCheck()
                    }
                }
            }
        }
    }

    /** 다음 확인까지 기다리되, 조향 흔들림이 감지되거나 이야기를 청하거나 조용히 모드가 끝나면 바로 확인한다. */
    private suspend fun waitForNextCheck(intervalMs: Long) {
        var waited = 0L
        while (waited < intervalMs && !earlyCheckRequested) {
            if (quiet.poll(SystemClock.elapsedRealtime()) != null) {
                quietEnded = QuietEnd.TIME_UP
                log("조용히 모드 끝")
                break
            }
            delay(500)
            waited += 500
            if (restPlanDue()) break  // 약속한 휴식 안내가 확인 간격 때문에 늦지 않게
        }
        earlyCheckRequested = false
    }

    /** 차선 변경·급제동 등 운전이 바쁜 동안은 말을 걸지 않고 기다린다. */
    private suspend fun waitUntilNotBusy() {
        while (!gate.canTalk(lastTimeMs) || parked) delay(500)
    }

    /** 통화 중이면 끝날 때까지(위험·차량 경고면 약 20초까지만), 음악이 곧 끝나면 곡 사이까지, 방금 곡을 넘겼으면 조금 기다린다. */
    private suspend fun awaitTalkWindow() {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            val now = SystemClock.elapsedRealtime()
            when (val decision = talkPolicy.decide(engine.stage, mediaNow(), now - start, now, vehicleWarning)) {
                is TalkDecision.Talk -> {
                    _ui.update { it.copy(waitingFor = null) }
                    return
                }
                is TalkDecision.Wait -> {
                    _ui.update { it.copy(waitingFor = decision.reason.label) }
                    delay(decision.ms)
                }
            }
        }
    }

    /**
     * 통화가 끝날 때까지 기다린다. 위험 단계이거나 차량 경고가 켜져 있으면 [TalkPolicy] 와 같이 약 20초까지만 기다린다.
     */
    private suspend fun waitWhileInCall() {
        val start = SystemClock.elapsedRealtime()
        while (mediaNow().inCall) {
            val urgent = engine.stage == AlertStage.DANGER || vehicleWarning
            if (urgent && SystemClock.elapsedRealtime() - start >= URGENT_CALL_CAP_MS) return
            delay(1_000)
        }
    }

    private fun mediaNow(): MediaState = platform.media.state(SystemClock.elapsedRealtime())

    /** 대화 한 번. 끝나면 줄이거나 멈췄던 음악을 원래대로 돌린다. */
    private suspend fun <T> exchange(block: suspend () -> T): T = voiceLock.withLock {
        try {
            block()
        } finally {
            voice.releaseMedia()
            _ui.update { it.copy(mediaHeld = null) }
        }
    }

    private suspend fun speak(text: String) {
        holdMedia(talkPolicy.speakMode(mediaNow()))
        _ui.update { it.copy(speaking = true) }
        chat(ChatLine(fromDriver = false, text = text, time = clockNow()))
        try {
            voice.speak(text)
        } finally {
            _ui.update { it.copy(speaking = false) }
        }
    }

    private fun chat(line: ChatLine) {
        _ui.update { it.copy(chat = (it.chat + line).takeLast(CHAT_LINES)) }
    }

    private fun clockNow(): String =
        Calendar.getInstance().let { FamilyNotice.clock(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }

    /** 잠깐 다른 캐릭터 목소리로 말하고 원래 캐릭터로 돌아온다(무응답 깨우기의 열혈 교관). */
    private suspend fun speakAs(voicePersona: Persona, text: String) {
        voice.setStyle(voicePersona.pitch, voicePersona.rate)
        _ui.update { it.copy(voicePersona = voicePersona) }
        try {
            speak(text)
        } finally {
            voice.setStyle(persona.pitch, persona.rate)
            _ui.update { it.copy(voicePersona = persona) }
        }
    }

    private suspend fun ask(): ListenResult {
        holdMedia(talkPolicy.listenMode(mediaNow()))
        val tap = CompletableDeferred<String>().also { typed = it }
        _ui.update { it.copy(listening = true) }
        val startedAt = SystemClock.elapsedRealtime()
        var byVoice = true
        var result = try {
            // 목소리와 화면 대답 중 먼저 온 쪽을 쓴다. 화면 대답도 듣기 시작부터 잰 시간을 대답 속도로 쓴다.
            coroutineScope {
                val micOk = micUsable()
                val heard = async {
                    if (micOk) voice.listen(platform.config.listenTimeoutMs)
                    else { delay(platform.config.listenTimeoutMs); if (screenReplyProven()) ListenResult.Silent else ListenResult.Unavailable }
                }
                select {
                    heard.onAwait { it }
                    tap.onAwait { text ->
                        heard.cancel()
                        byVoice = false
                        screenAnswered = true
                        ListenResult.Heard(text, SystemClock.elapsedRealtime() - startedAt)
                    }
                }
            }
        } finally {
            typed = null
            _ui.update { it.copy(listening = false) }
        }
        if (result is ListenResult.Heard) {
            if (byVoice && !voiceVerified) {
                voiceVerified = true
                _ui.update { s -> s.copy(voiceIssues = s.voiceIssues.filterNot { it.startsWith(SILENT_HINT) }) }
            }
            chat(ChatLine(fromDriver = true, text = result.text, time = clockNow(), latencySec = result.latencyMs / 1000.0))
            if (conversation.memory.noteCoffee(result.text)) saveMemory()
        } else if (result is ListenResult.Silent && !voiceVerified && !screenReplyProven()) {
            // 아직 목소리를 한 번도 못 알아들었다: 졸음이 아니라 마이크·에뮬레이터 설정 문제일 수 있어 판정하지 않는다.
            // (차량 졸음 경고·핸들 신호는 따로 그대로 본다.)
            _ui.update { s -> if (s.voiceIssues.any { it.startsWith(SILENT_HINT) }) s else s.copy(voiceIssues = s.voiceIssues + SILENT_HINT_TEXT) }
            log("목소리를 아직 못 들었어요 — 무응답으로 판정하지 않음")
            result = ListenResult.Unavailable
        }
        return result
    }

    private fun holdMedia(mode: AudioMode) {
        voice.holdMedia(mode)
        if (mode != AudioMode.NONE) _ui.update { it.copy(mediaHeld = maxOf(mode, it.mediaHeld ?: AudioMode.NONE)) }
    }

    private fun micUsable(): Boolean = voice.canListen && hasMic()

    private fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(getApplication<Application>(), Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun inQuietMode(): Boolean = quiet.active(SystemClock.elapsedRealtime())

    /**
     * 조용히 모드에서 조향 기준이 잡혀 있으면 말없이 신호만 본다.
     * 기준이 아직 없거나(주행 초반·조향 신호를 못 받는 차) 이미 주의 단계면 신호만 믿을 수 없어 가끔 짧게 확인한다.
     */
    private fun silentlyWatching(): Boolean =
        inQuietMode() && steering.calibrated && engine.stage == AlertStage.NORMAL

    /** 조용히 모드가 끝난 뒤 첫마디. 왜 다시 말을 거는지 먼저 밝힌다. */
    private fun comebackLine(): String? {
        val line = when (quietEnded) {
            QuietEnd.TIME_UP -> "다시 왔어요. 짧게만 여쭤볼게요."
            QuietEnd.WEAVING -> "조용히 하라고 하셨는데, 핸들이 평소보다 많이 흔들려서 잠깐 여쭤볼게요."
            QuietEnd.HANDS_OFF -> "조용히 하라고 하셨는데, 핸들에서 손이 떨어졌다고 해서 잠깐 여쭤볼게요."
            QuietEnd.LANE_INTERVENTION -> "조용히 하라고 하셨는데, 차가 차로를 벗어나지 않게 붙잡아서 잠깐 여쭤볼게요."
            QuietEnd.VEHICLE_WARNING, null -> null
        }
        quietEnded = null
        return line
    }

    private suspend fun runCheck() {
        // 들을 수 없는 상태에서 확인을 하면 무응답이 쌓여 잘못된 위험 판정이 나온다.
        // 마이크를 쓸 수 없으면 한 번 소리 내어 알리고, 확인은 화면 대답(빠른 대답·입력칸)으로 이어 간다.
        // 이때 대답이 없는 것은 졸음이 아니라 들을 수 없었던 것이므로 판정하지 않는다([ask] 가 Unavailable 로 돌려준다).
        if (!micUsable()) {
            if (!micWarned) {
                log("마이크를 쓸 수 없어요 — 화면 대답으로 확인")
                speak("마이크를 쓸 수 없어서 목소리는 못 들어요. 화면의 빠른 대답이나 입력칸으로 대답해 주세요.")
            }
            micWarned = true
        } else micWarned = false
        val here = platform.location.current()
        val upcoming = here?.let { nextRestStop(it, platform.location.headingDeg(), restStops, platform.config.restStopRadiusKm) }
        val turn = conversation.next(
            TurnContext(
                stage = engine.stage,
                calibrating = engine.baselineSamples.size < CALIBRATION_ANSWERS,
                quiet = inQuietMode(),
                nextRestStop = upcoming?.stop?.name,
                nowPlaying = mediaNow().title,
                storyRequested = storyRequested,
                drivingMinutes = tracker.drivingMs / 60_000,
            )
        )
        if (turn.kind == TurnKind.STORY) storyRequested = false
        speak(turn.prompt)
        var result = ask()
        var retried = false
        if (result is ListenResult.Silent) {
            // 소음이나 인식 실패일 수 있으니 바로 위험으로 올리지 않고 한 번 더 묻는다.
            speak("제 말 들리세요?")
            result = ask()
            retried = true
        }
        if (result is ListenResult.Unavailable) {
            log("음성 인식 오류 — 이번 확인은 건너뜀")
            return
        }
        // 즐거운 대화·이야기는 생각할 시간이 필요할 수 있어 대답 속도를 판정에 쓰지 않는다.
        // 무응답(다시 물어도 대답이 없거나 다시 물어서야 대답한 경우)은 어떤 대화든 판정한다.
        val measured = turn.measured || retried
        val heardMs = (result as? ListenResult.Heard)?.let { it.latencyMs + if (retried) platform.config.listenTimeoutMs else 0L }
        trip.conversation(heardMs, measured = measured && heardMs != null)
        val stage = if (measured) record(result, retried) else engine.stage
        // 대답했으면 깨우기를 멈춘다. 아직 주의 단계면 환기(창문)는 그대로 둔다.
        if (result is ListenResult.Heard) clearWake(keepWindow = stage == AlertStage.CAUTION)
        _ui.update { it.copy(lastExchange = describe(turn.prompt, result, measured)) }

        val intent = detectIntent(result.text)
        if (intent == DriverIntent.NONE) conversation.reply(turn, result.text)?.let { speak(it) }
        // 좋아하는 간식 얘기가 나오면 "들어 볼래요?"라고 묻고 대답을 바로 듣는다(측정에는 쓰지 않는다).
        if (conversation.hasOffer) conversation.answerOffer((ask() as? ListenResult.Heard)?.text)?.let { speak(it) }
        // 좋다고 했으면 그 이야기를 바로 이어 간다(다음 확인을 기다리지 않음).
        if (conversation.hasFollowUp) earlyCheckRequested = true
        saveMemory()
        when (intent) {
            DriverIntent.TIRED -> {
                speak("말씀해 주셔서 고마워요.")
                offerRestStop()
                return
            }
            DriverIntent.QUIET -> {
                val ms = quiet.start(SystemClock.elapsedRealtime(), localHour(System.currentTimeMillis()), tracker.drivingMs)
                val span = if (ms >= 60_000) "${ms / 60_000}분" else "${ms / 1000}초"
                quietEnded = null
                speak("알겠어요. $span 동안 먼저 말 걸지 않을게요. 대신 핸들이랑 차량 신호는 계속 보고 있을게요.")
                log("조용히 모드 $span · 감지는 계속")
            }
            DriverIntent.STORY -> {
                storyRequested = true
                earlyCheckRequested = true
                speak("좋아요, 재밌는 얘기 하나 해 드릴게요.")
                log("이야기 요청")
            }
            DriverIntent.NONE -> Unit
        }
        if (!measured) return
        if (stage == AlertStage.CAUTION && checksThisTrip <= 3 && savedBaseline.isNotEmpty()) {
            // 지난 주행의 평소 속도와 비교했기 때문에 알 수 있는 경우
            speak("오늘은 출발부터 평소보다 대답이 느리세요.")
        }
        when (stage) {
            AlertStage.NORMAL -> Unit
            AlertStage.CAUTION -> offerFreshAir()
            // 다시 불러도 대답이 없어 위험이 됐다면 "들를까요?"로 묻지 않고 바로 깨운다(대답하면 쉼터로 바로 안내).
            AlertStage.DANGER -> if (result is ListenResult.Heard) offerRestStop() else wakeEscalation()
        }
    }

    /** 대답 속도를 판정에 넣는다. 다시 물어서야 대답했다면 그만큼 느린 대답으로 본다. */
    private fun record(result: ListenResult, retried: Boolean): AlertStage {
        val check = when (result) {
            is ListenResult.Heard -> CheckResult(
                latencyMs = if (retried) platform.config.listenTimeoutMs + result.latencyMs else result.latencyMs,
                correct = isMeaningfulAnswer(result.text),
            )
            else -> CheckResult(null, correct = false)
        }
        val before = engine.stage
        val stage = engine.record(check)
        trip.stage(stage)
        if (stage > before) trip.signal(if (check.latencyMs == null) DrowsySignal.NO_ANSWER else DrowsySignal.SLOW_ANSWER)
        checksThisTrip++
        saveBaseline()
        _ui.update { s ->
            s.copy(
                stage = stage,
                baselineSec = engine.baselineMs / 1000.0,
                lastLatencySec = check.latencyMs?.let { it / 1000.0 } ?: s.lastLatencySec,
                latencyHistory = (s.latencyHistory + (check.latencyMs?.let { it / 1000.0 } ?: -1.0)).takeLast(LATENCY_HISTORY),
            )
        }
        if (stage == AlertStage.NORMAL) {
            clearWake()
            freshAirOffered = false   // 정상으로 돌아왔으니, 다시 주의가 되면 환기를 또 권한다
        }
        log("체크: ${check.latencyMs?.let { "%.1f초".format(it / 1000.0) } ?: "무응답"} → ${stage.label}")
        return stage
    }

    private fun loadBaseline(): List<Long> =
        prefs.getString(KEY_BASELINE, null)
            ?.split(",")
            ?.mapNotNull { it.toLongOrNull() }
            .orEmpty()

    private fun saveBaseline() {
        prefs.edit().putString(KEY_BASELINE, engine.baselineSamples.joinToString(",")).apply()
    }

    private fun saveMemory() {
        prefs.edit().putString(KEY_MEMORY, conversation.memory.serialize()).apply()
        _ui.update { it.copy(remembered = rememberedText()) }
    }

    private fun rememberedText(): String =
        DriverMemory.parse(prefs.getString(KEY_MEMORY, null)).let { m -> listOfNotNull(m.team, m.food, "커피".takeIf { m.likesCoffee == true && m.food != "커피" }).joinToString(" · ") }

    /** 주의 단계: 묻지 않고 바로 환기한다. 외기 유입에 더해 운전석 창문을 2~3cm(비 올 때·정차 중 제외) 연다. */
    private suspend fun offerFreshAir() {
        if (freshAirOffered) return
        freshAirOffered = true
        val air = platform.cabin.freshAir()
        if (!windowCracked && !raining && !parked && platform.cabin.crackWindow(true)) windowCracked = true
        if (air) trip.wake(WakeAction.FRESH_AIR)
        if (windowCracked) {
            trip.wake(WakeAction.WINDOW)
            addWake(WAKE_WINDOW)
        }
        when {
            air && windowCracked -> speak("창문 조금 열고, 시원한 바람 틀어 드릴게요. 바람은 잠깐만 도와줘요. 계속 졸리면 쉬어 가요.")
            air -> speak("바깥 공기를 들여올게요.")
            windowCracked -> speak("창문을 조금 열게요.")
            else -> speak("조금 피곤해 보여요. 창문을 살짝 열어 주실래요?")
        }
        log("주의 단계 환기: ${listOfNotNull("외기 유입".takeIf { air }, "창문 2~3cm".takeIf { windowCracked }).ifEmpty { listOf("제어 불가 — 음성 권유") }.joinToString("·")}")
    }

    private suspend fun offerRestStop() {
        val nearby = nearestStop()
        restSuggested = true
        _ui.update { it.copy(restOption = null) }
        if (nearby == null) {
            speak("많이 피곤하신 것 같아요. 안전한 곳에서 꼭 쉬어 가세요.")
            log("근처 휴식 장소 없음")
            return
        }
        val km = nearby.distanceKm.roundToInt().coerceAtLeast(1)
        _ui.update { it.copy(restOption = RestOption(nearby.stop.name, km, nearby.stop.spokenAmenities())) }
        val amenities = nearby.stop.spokenAmenities()?.let { " $it 있어요." } ?: ""
        // 옆에 사람이 타 있으면 운전을 바꾸는 것도 방법이다.
        val swap = if (passengerPresent) " 옆에 계신 분과 운전을 바꾸셔도 좋고요." else ""
        speak("${km}킬로미터 앞에 ${particle(nearby.stop.name, "이", "가")} 있어요.$amenities$swap 들를까요?")
        var answer = ask()
        if (answer is ListenResult.Silent) {
            // 한 번의 침묵은 못 들었을 수도 있다: 한 번 더 묻는다.
            speak("${nearby.stop.name}에 들를까요? '응'이나 '아니'로 말씀해 주세요.")
            answer = ask()
        }
        if (answer is ListenResult.Silent) {
            _ui.update { it.copy(restOption = null) }
            // 깨우기는 이미 위험하다는 근거가 있을 때만(위험 단계이거나 차량 경고가 켜져 있을 때).
            if (engine.stage == AlertStage.DANGER || vehicleWarning) {
                wakeEscalation()
            } else {
                declineRest()
                speak("대답이 없으셔서 일단 그냥 갈게요. 쉬고 싶으면 언제든 '쉬자'라고 말씀해 주세요.")
                log("휴식 제안 — 대답 없음(정상 단계라 깨우지 않음)")
            }
            return
        }
        if (answer is ListenResult.Unavailable) {
            _ui.update { it.copy(restOption = null) }
            declineRest()
            speak("대답을 못 들었어요. 쉬고 싶으면 화면에서 '피곤해'를 누르거나 '쉬자'라고 말씀해 주세요.")
            log("휴식 제안 — 듣기 불가")
            return
        }
        if (!isYes(answer.text)) {
            _ui.update { it.copy(restOption = null) }
            declineRest()
            // 쉬지 않기로 했으니 한동안 확인을 더 자주 한다(평소 간격의 절반, 운전 30분 동안).
            moreOftenUntilDrivingMs = tracker.drivingMs + MORE_OFTEN_MIN * 60_000L
            speak("알겠어요. 그럼 조금 더 자주 여쭤볼게요.")
            log("휴식 제안 보류 — ${MORE_OFTEN_MIN}분 동안 확인 간격 절반")
            return
        }
        // 들르기로 했을 때만 "다음 정차할 곳"으로 기억한다(커피 가게 이름·가족 문자에 쓴다).
        lastStopName = nearby.stop.name
        lastStop = nearby.stop
        if (platform.navigator.addWaypoint(nearby.stop)) {
            _ui.update { it.copy(restOption = it.restOption?.copy(waypointAdded = true)) }
            // 휴게소 카드는 도착(정차)하거나 10분이 지나면 내린다. 거리 숫자가 낡은 채 남지 않게.
            val name = nearby.stop.name
            viewModelScope.launch { delay(10 * 60_000L); _ui.update { s -> if (s.restOption?.name == name) s.copy(restOption = null) else s } }
            speak("${particle(nearby.stop.name, "을", "를")} 경유지로 추가했어요.")
            log("경유지 추가: ${nearby.stop.name}")
        } else {
            speak("${particle(nearby.stop.name, "은", "는")} ${km}킬로미터 앞이에요. 안내 표지판을 따라가 주세요.")
            log("경유지 추가 불가 — 음성 안내")
        }
    }

    /** 휴식 제안을 거절(또는 대답 없음)하면 다음 제안·휴식 약속 알림이 다시 나올 수 있게 되돌린다. */
    private fun declineRest() {
        restSuggested = false
        planReminded = false
    }

    /**
     * 깨우기에 대답하면 묻지 않고 가장 가까운 쉼터를 경유지로 넣는다.
     * 이미 두 번 대답이 없었던 뒤라 "들를까요?"로 다시 미루지 않는다.
     */
    private suspend fun routeToRestStop() {
        val nearby = nearestStop()
        restSuggested = true
        if (nearby == null) {
            speak("많이 피곤하신 것 같아요. 안전한 곳에서 꼭 쉬어 가세요.")
            return
        }
        lastStopName = nearby.stop.name
        lastStop = nearby.stop
        val km = nearby.distanceKm.roundToInt().coerceAtLeast(1)
        _ui.update { it.copy(restOption = RestOption(nearby.stop.name, km, nearby.stop.spokenAmenities())) }
        val amenities = nearby.stop.spokenAmenities()?.let { " $it 있어요." } ?: ""
        // 예보한 위험 시각 앞뒤 30분 안이면 "예보대로"라고 알려 준다(예보가 맞았다는 걸 운전자도 알게).
        val asForecast = forecastRiskMinuteOfDay?.let { r -> Math.floorMod(localMinuteOfDay(lastTimeMs) - r + 720, 1440) - 720 in -30..30 } == true
        val fcNote = if (asForecast) " 예보한 그 시간이에요." else ""
        speak("놀라셨죠?$fcNote ${km}킬로미터 앞에 ${particle(nearby.stop.name, "이", "가")} 있어요.$amenities 들렀다 가요.")
        if (platform.navigator.addWaypoint(nearby.stop)) {
            _ui.update { it.copy(restOption = it.restOption?.copy(waypointAdded = true)) }
            speak("${particle(nearby.stop.name, "을", "를")} 경유지로 넣었어요.")
            log("깨운 뒤 경유지 추가: ${nearby.stop.name}")
        } else {
            speak("${particle(nearby.stop.name, "은", "는")} ${km}킬로미터 앞이에요. 안내 표지판을 따라가 주세요.")
            log("경유지 추가 불가 — 음성 안내")
        }
    }

    private suspend fun nearestStop() =
        platform.location.current()?.let { nextRestStop(it, platform.location.headingDeg(), restStops, platform.config.restStopRadiusKm) }

    /**
     * 위험 단계에서 쉼터 제안에도 대답이 없으면, 앱에 허용된 차량 기능만으로 깨운다.
     * 1단계: 알람음 + 찬 바람·통풍 시트·실내등 + 운전석 창문 2~3cm(비 올 때 제외). 2단계(그래도 무응답): 비상등 + 정차 안내.
     * 알람이 확실히 들리도록 음악은 멈춘다. 브레이크·조향·경적은 쓰지 않는다. 앱에 열려 있지 않고, 차량 자체 안전 기능의 몫이다.
     */
    private fun wakeEscalation() {
        val level = ++wakeLevel
        viewModelScope.launch {
            // 2단계 이상은 계속 무응답이어도 WAKE_REPEAT_MS 에 한 번만 다시 울린다(알람이 쉬지 않고 이어지지 않게).
            val now = SystemClock.elapsedRealtime()
            if (level >= 2 && lastLevel2AtMs?.let { now - it < WAKE_REPEAT_MS } == true) {
                log("깨우기 ${level}단계 — 방금 울려서 이번엔 건너뜀")
                return@launch
            }
            if (wakeLevel != level) return@launch
            val burst = platform.cabin.wakeBurst()
            if (!windowCracked && !raining && !parked && platform.cabin.crackWindow(true)) windowCracked = true
            val parts = listOfNotNull("알람", "찬 바람·통풍 시트·열선 끄기·실내등".takeIf { burst }, "창문 살짝".takeIf { windowCracked })
            trip.wake(WakeAction.ALARM)
            if (windowCracked) addWake(WAKE_WINDOW)
            if (burst) {
                addWake(WAKE_COLD_AIR)
                addWake(WAKE_SEAT)
            }
            if (burst) trip.wake(WakeAction.COLD_AIR_SEAT)
            if (windowCracked) trip.wake(WakeAction.WINDOW)
            log("깨우기 ${level}단계: ${parts.joinToString("·")}")
            exchange {
                // 차례를 기다리는 사이 운전자가 대답했거나(깨우기 해제) 다음 단계가 이미 울렸으면 울리지 않는다.
                if (wakeLevel != level) {
                    log("깨우기 ${level}단계 — 그 사이 상태가 바뀌어 건너뜀")
                    return@exchange
                }
                if (level >= 2) lastLevel2AtMs = SystemClock.elapsedRealtime()
                holdMedia(AudioMode.PAUSE)
                ring()
                if (level == 1) {
                    log("목소리 전환 → ${Persona.DRILL.label}")
                    addWake(WAKE_VOICE)
                    speakAs(Persona.DRILL, Persona.DRILL.wakeLine)
                    if (ask() is ListenResult.Heard) {
                        clearWake()
                        routeToRestStop()
                    }
                } else {
                    if (!hazardOn && platform.cabin.hazardLights(true)) {
                        hazardOn = true
                        trip.wake(WakeAction.HAZARD)
                        log("비상등 켜짐")
                    }
                    // 비상등을 직접 켤 수 없는 차(공개 Pleos SDK 등)에서는 켰다고 말하지 않고 운전자에게 부탁한다
                    if (hazardOn) speak("비상등을 켰어요. 가장 가까운 안전한 곳에 차를 세워 주세요.")
                    else speak("비상등을 켜고, 가장 가까운 안전한 곳에 차를 세워 주세요.")
                    alertFamily()
                }
            }
        }
    }

    /**
     * 깨우기 2단계에도 대답이 없으면 등록된 가족에게 한 번 알려 전화로 깨워 달라고 한다.
     * 위치는 가장 가까운 쉼터 이름과 거리까지만. 30분 안에는 다시 보내지 않는다.
     */
    private suspend fun alertFamily() {
        val now = SystemClock.elapsedRealtime()
        if (familyAlerted || family.phone == null) return
        if (familyAlertAtMs?.let { now - it < FAMILY_ALERT_GAP_MS } == true) return
        val nearby = nearestStop()
        val text = FamilyNotice.noResponse(nearby?.stop?.name, nearby?.distanceKm?.roundToInt()?.coerceAtLeast(1))
        if (family.send(text)) {
            familyAlerted = true
            familyAlertAtMs = now
            trip.wake(WakeAction.FAMILY_ALERT)
            addWake(WAKE_FAMILY)
            speak("가족에게 연락했어요. 전화가 오면 받아 주세요.")
            log("가족에게 위급 문자 보냄")
        } else {
            log("가족 위급 문자 전송 확인 안 됨 — 권한·통신 확인")
        }
    }

    /** 화면 깨우기 목록에 동작을 한 번만 올린다. */
    private fun addWake(label: String) {
        _ui.update { if (label in it.wakeActions) it else it.copy(wakeActions = it.wakeActions + label) }
    }

    /**
     * 운전자가 대답하거나 차를 세우면 깨우기를 멈추고 창문을 닫고 비상등을 끈다.
     * [keepWindow] 면(아직 주의 단계라 환기 중) 창문은 열어 둔다.
     */
    private fun clearWake(keepWindow: Boolean = false) {
        wakeLevel = 0
        lastLevel2AtMs = null
        _ui.update { s -> val keep = s.wakeActions.filter { keepWindow && it == WAKE_WINDOW }; if (s.wakeActions == keep) s else s.copy(wakeActions = keep) }
        if (windowCracked && !keepWindow) {
            windowCracked = false
            viewModelScope.launch { platform.cabin.crackWindow(false) }
            log("창문 닫힘")
        }
        if (hazardOn) {
            hazardOn = false
            viewModelScope.launch { platform.cabin.hazardLights(false) }
            log("비상등 꺼짐")
        }
        if (familyAlerted) {
            familyAlerted = false
            familyAlertAtMs = null   // 안심 문자를 보냈으니, 다시 무응답이면 새 위급 문자를 보낼 수 있다
            viewModelScope.launch { if (family.send(FamilyNotice.respondedAgain())) log("가족에게 안심 문자 보냄") }
        }
    }

    /**
     * 휴식 장소에 멈추면 15분 파워냅을 제안한다: 좌석 눕히기 → 환기 → 알람 → 좌석 원위치.
     * 다시 출발하면 낮잠 작업째로 취소되고([onSnapshot]), 달리는 중에는 알람을 울리지 않고 좌석도 움직이지 않는다.
     * 눕힌 좌석은 취소돼도 finally 에서 세우고, 이미 출발했으면 다음 정차 때 세운다.
     */
    private fun offerNap() {
        if (nap?.isActive == true) return
        nap = viewModelScope.launch {
            val accepted = exchange {
                speak("잘 오셨어요. 15분만 눈 붙이고 가실래요?")
                val r = ask()
                if (!isYes(r.text)) {
                    speak(if (r is ListenResult.Heard) "알겠어요. 스트레칭이라도 하고 가세요." else "필요하면 언제든 불러 주세요. 스트레칭이라도 하고 가세요.")
                    return@exchange false
                }
                true
            }
            if (!accepted || !parked) return@launch
            // 커피를 사러 간 동안은 목소리를 붙잡지 않는다(그 사이 다른 안내가 막히지 않게).
            if (exchange { askCoffee() }) waitForCoffee()
            if (!parked) return@launch
            var reclined = false
            try {
                exchange {
                    if (!parked) return@exchange
                    reclined = platform.cabin.reclineSeat()
                    platform.cabin.freshAir()
                    speak(if (reclined) "좌석을 눕혀 드릴게요. 15분 뒤에 깨워 드릴게요." else "좌석을 편하게 눕히세요. 15분 뒤에 깨워 드릴게요.")
                }
                if (!parked) return@launch
                log("파워냅 시작")
                trip.rest(lastStopName, NAP_MINUTES)
                notifyFamilyRest()
                _ui.update { it.copy(napping = true, napEndsAtMs = SystemClock.elapsedRealtime() + platform.config.napMs, napTotalMs = platform.config.napMs) }
                delay(platform.config.napMs)
                exchange {
                    if (!parked) return@exchange   // 그 사이 출발했으면 알람을 울리지 않는다
                    holdMedia(AudioMode.PAUSE)
                    ring()
                    speak("일어날 시간이에요. 좌석을 세울게요.")
                    // 나눈 이야기를 휴게소에서 다시 꺼낸다(예: "아, 호두과자 사 가는 거 잊지 마세요.")
                    conversation.memory.foodLabel?.let { speak("아, $it 사 가는 거 잊지 마세요.") }
                }
                log("파워냅 끝")
            } finally {
                withContext(NonCancellable) {
                    if (reclined) {
                        if (parked) platform.cabin.restoreSeat() else seatRestorePending = true
                    }
                    _ui.update { it.copy(napping = false, napEndsAtMs = null) }
                    publishTrip()
                }
            }
        }
    }

    /**
     * 커피 냅: 커피를 마시고 바로 15분 자면 깨어날 즈음 카페인이 돌아, 둘 중 하나만 할 때보다 덜 졸린다
     * (Reyner & Horne 1997, 주행 시뮬레이터: 카페인 200mg + 15분 미만 낮잠 → 졸음 사고 행동이 위약의 9%, 카페인만은 34%).
     * 원하는 사람만. 커피를 사러 간 동안은 화면의 "파워냅 시작"을 누르거나, [CopilotConfig.coffeeWaitMs] 뒤 다시 묻는다.
     */
    private suspend fun askCoffee(): Boolean {
        // 지금 선 곳이 안내한 쉼터일 때만 그 쉼터의 카페 이름을 쓴다(다른 곳에 섰으면 가게 이름을 말하지 않는다).
        val here = platform.location.current()
        val cafe = lastStop?.takeIf { s -> here == null || distanceKm(here, s.point) <= NEAR_STOP_KM }?.cafeBrand
        val likes = conversation.memory.likesCoffee
        if (likes == false) {
            speak(CoffeeNap.NAP_ONLY)
            return false
        }
        if (cafe == null) {
            // 커피 가게 자료가 없으면 "커피 한 잔 드시고"라고 권하지 않는다: 가진 커피가 있을 때만 커피 냅.
            speak(CoffeeNap.carryLine(likes))
            val r = ask()
            if (r !is ListenResult.Heard || !isYes(r.text)) return false
            speak(CoffeeNap.WITH_OWN)
        } else {
            val offer = CoffeeNap.offer(likes, cafe)
            if (offer != null) {
                speak(offer)
            } else {
                // 커피를 좋아하는지 모르면 먼저 묻는다(커피를 안 마시는 사람도 있다).
                speak(CoffeeNap.ASK)
                val r = ask()
                if (r !is ListenResult.Heard) return false
                if (!isYes(r.text) || conversation.memory.likesCoffee == false) {
                    speak(CoffeeNap.NAP_ONLY)
                    return false
                }
                speak(CoffeeNap.afterYes(cafe))
            }
        }
        if (!isYes(ask().text)) return false
        speak("좋아요. 커피 드시고 화면의 파워냅 시작을 눌러 주세요.")
        log("커피 냅 — 커피 기다리는 중")
        return true
    }

    /** "파워냅 시작"을 누르거나, 다시 물었을 때 "아직"이 아니면 끝난다. 다시 출발하면 낮잠 작업째로 취소된다. */
    private suspend fun waitForCoffee() {
        val done = CompletableDeferred<Unit>().also { coffeeDone = it }
        _ui.update { it.copy(coffeeWaiting = true) }
        try {
            while (true) {
                if (withTimeoutOrNull(platform.config.coffeeWaitMs) { done.await() } != null) return
                val stillOut = exchange {
                    speak("커피 드셨어요? 이제 15분 재워 드릴게요.")
                    val r = ask()
                    (r is ListenResult.Heard && r.text.contains("아직")).also { if (it) speak("천천히 다녀오세요.") }
                }
                if (!stillOut) return
            }
        } finally {
            coffeeDone = null
            _ui.update { it.copy(coffeeWaiting = false) }
        }
    }

    /** 휴식을 시작하면 등록한 가족 번호로 휴게소 이름과 다시 출발할 예정 시각을 보낸다. */
    private suspend fun notifyFamilyRest() {
        if (family.phone == null) {
            familyNotified = false
            _ui.update { it.copy(familyNotified = false) }
            log("가족 번호 없음 — 문자 생략")
            return
        }
        val resume = Calendar.getInstance().apply { add(Calendar.MINUTE, NAP_MINUTES) }
        val text = FamilyNotice.restStarted(lastStopName, NAP_MINUTES, resume.get(Calendar.HOUR_OF_DAY), resume.get(Calendar.MINUTE))
        familyNotified = family.send(text)
        _ui.update { it.copy(familyNotified = familyNotified) }
        log(if (familyNotified) "가족에게 휴식 문자 보냄" else "가족 문자 실패 — 권한·통신 확인")
    }

    /** 지금까지의 주행을 "오늘의 운행"으로 화면에 올리고 저장한다. */
    private fun publishTrip() {
        if (trip.conversations == 0) return
        val summary = trip.summary(engine.baselineMs, rememberedText())
        prefs.edit().putString(KEY_TRIP, summary.serializeRows()).apply()
        _ui.update { it.copy(tripSummary = summary.rows()) }
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
        // 쉬자는 제안을 미뤘으면 한동안 절반 간격으로
        moreOftenUntilDrivingMs?.let { tracker.drivingMs < it } == true -> platform.config.normalIntervalMs / 2
        // 밤에 차로 유지 보조를 오래 켜고 달리거나 어젯밤 잠이 짧았으면 확인을 조금 더 자주
        else -> (platform.config.normalIntervalMs * lane.intervalFactor(lastTimeMs, localHour(lastTimeMs)) * planner.checkIntervalFactor(sleep)).toLong()
    }

    private fun describe(prompt: String, result: ListenResult, measured: Boolean): String =
        "Q. $prompt\nA. " + when (result) {
            is ListenResult.Heard -> {
                val seconds = "%.1f초".format(result.latencyMs / 1000.0)
                "${result.text} ($seconds${if (measured) "" else " · 대화, 판정 제외"})"
            }
            ListenResult.Silent -> "(무응답)"
            ListenResult.Unavailable -> "(인식 오류)"
        }

    private fun log(message: String) {
        _ui.update { it.copy(log = (listOf(message) + it.log).take(8)) }
    }

    override fun onCleared() {
        publishTrip()
        saveBaseline()
        saveMemory()
        voice.release()
        platform.release()
    }

    private companion object {
        /** 위험·차량 경고 때 통화가 끝나기를 기다리는 최대 시간(TalkPolicy 의 urgentCallCapMs 와 같다). */
        const val URGENT_CALL_CAP_MS = 20_000L
        /** 깨우기 2단계를 다시 울리는 최소 간격. */
        const val WAKE_REPEAT_MS = 3 * 60_000L
        /** 이 거리 안에 서 있으면 안내한 쉼터에 선 것으로 본다. */
        const val NEAR_STOP_KM = 2.0
        const val KEY_BASELINE = "answer_baseline_ms"
        /** 약속한 휴식 몇 분 전에 쉼터를 권할지. */
        const val REST_PLAN_LEAD_MIN = 10
        /** 이만큼 달린 뒤부터 "다음 쉼터를 지나면 한참 없다"를 알린다. */
        const val GAP_WARN_AFTER_MIN = 60L
        /** 그 쉼터가 이만큼 가까워졌을 때 알린다. */
        const val GAP_WARN_KM = 12.0
        const val KEY_MEMORY = "driver_memory"
        const val KEY_TRIP = "last_trip_summary"
        const val KEY_PERSONA = "persona"
        /** 파워냅 길이. 음성 안내("15분 뒤에 깨워 드릴게요")와 같다. 시연 빌드는 시간을 압축하지만 요약·문자는 실제 분으로 적는다. */
        const val NAP_MINUTES = 15
        /** 가족 위급 문자 사이 최소 간격. */
        const val FAMILY_ALERT_GAP_MS = 30 * 60_000L
        /** 목적지를 모를 때 졸음 예보를 그릴 거리(km)와 예보용 평균 속도. */
        const val DEFAULT_FORECAST_KM = 400.0
        const val AVG_KPH = 90.0
        /** AlertnessEngine 이 평소 속도를 정하는 데 쓰는 첫 대답 수와 같다. */
        const val CALIBRATION_ANSWERS = 3
        /** 쉬자는 제안을 미룬 뒤 확인을 더 자주 하는 운전 시간(분). */
        const val MORE_OFTEN_MIN = 30L
    }
}

/** 목소리를 아직 한 번도 못 들었을 때 띄우는 안내. */
internal const val SILENT_HINT = "목소리가 아직 들리지 않아요"
internal const val SILENT_HINT_TEXT = "$SILENT_HINT · 에뮬레이터라면 Extended controls → Microphone → 'Virtual microphone uses host audio input'을 켜 주세요. 아래 빠른 대답을 눌러도 돼요."

private fun localHour(timeMs: Long): Int =
    Calendar.getInstance().apply { timeInMillis = timeMs }.get(Calendar.HOUR_OF_DAY)

private fun localMinuteOfDay(timeMs: Long): Int =
    Calendar.getInstance().apply { timeInMillis = timeMs }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

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

val WaitReason.label: String
    get() = when (this) {
        WaitReason.CALL -> "통화 중 · 끝나면 말할게요"
        WaitReason.SONG_GAP -> "곡이 끝나면 말할게요"
        WaitReason.DRIVER_ACTIVE -> "곡 넘기기 감지 · 조금 뒤에"
    }

val ActivationReason.label: String
    get() = when (this) {
        ActivationReason.HIGHWAY -> "고속 주행 누적"
        ActivationReason.LONG_TRIP -> "장거리 목적지"
        ActivationReason.REQUESTED -> "운전자 요청"
        ActivationReason.VEHICLE_WARNING -> "차량 졸음 경고"
    }
