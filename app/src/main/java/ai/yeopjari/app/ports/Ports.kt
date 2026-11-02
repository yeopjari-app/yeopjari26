package ai.yeopjari.app.ports

import ai.yeopjari.core.AudioMode
import ai.yeopjari.core.GeoPoint
import ai.yeopjari.core.MediaState
import ai.yeopjari.core.RestStop
import ai.yeopjari.core.VehicleSnapshot
import kotlinx.coroutines.flow.Flow

/** 차량 상태(차속·기어) 공급원. Pleos 는 Vehicle SDK, 시연은 가상 주행. */
interface VehicleSource {
    val snapshots: Flow<VehicleSnapshot>
}

interface LocationSource {
    suspend fun current(): GeoPoint?

    /** 진행 방향(도, 북쪽 0). 멈춰 있거나 알 수 없으면 null. 앞쪽 휴식 장소를 고르는 데 쓴다. */
    suspend fun headingDeg(): Double? = null
}

/** 휴식 장소를 경로에 경유지로 추가한다. 성공하면 true. */
interface Navigator {
    suspend fun addWaypoint(stop: RestStop): Boolean

    /** 목적지까지 남은 거리(km). 안내 중이 아니거나 알 수 없으면 null. */
    suspend fun remainingKm(): Double?
}

/**
 * 차 안 소리 상태(음악 재생·통화·곡 남은 시간·곡 넘기기). [nowMs] 와 [MediaState.lastInteractionMs] 는
 * SystemClock.elapsedRealtime 기준.
 */
interface MediaSource {
    fun state(nowMs: Long): MediaState
}

/**
 * 실내 제어. 지원하지 않는 기능은 false 를 돌려주고, 그때는 음성 안내로 대신한다.
 * 좌석 눕히기는 P단 정차 중 파워냅 모드에서만 쓴다.
 * 브레이크·조향·경적 같은 주행 제어는 여기에 두지 않는다. 그것은 차량 자체 안전 기능의 몫이다.
 */
interface CabinControl {
    suspend fun freshAir(): Boolean
    suspend fun reclineSeat(): Boolean
    suspend fun restoreSeat(): Boolean
    /**
     * 깨우기: 찬 바람(온도 낮추기·풍량 올리기·외기 유입) · 통풍 시트 · 시트/핸들 열선 끄기 · 실내등 깜빡임 중
     * 가능한 것. 하나라도 했으면 true.
     */
    suspend fun wakeBurst(): Boolean
    /** 운전석 창문을 2~3cm만 열거나 원래대로 닫는다. 찬 바람과 바람 소리로 깨우는 신호. */
    suspend fun crackWindow(open: Boolean): Boolean
    suspend fun hazardLights(on: Boolean): Boolean
}

/**
 * 한 번 듣기의 결과. 운전자가 정말 말하지 않은 [Silent] 와, 마이크·인식기 문제로 들을 수 없었던
 * [Unavailable] 을 구분한다. 후자는 졸음 판정에 넣지 않는다.
 */
sealed interface ListenResult {
    /** 인식한 답과, 듣기 시작부터 말하기 시작까지 걸린 시간. */
    data class Heard(val text: String, val latencyMs: Long) : ListenResult
    data object Silent : ListenResult
    data object Unavailable : ListenResult
}

val ListenResult.text: String?
    get() = (this as? ListenResult.Heard)?.text

interface VoiceIO {
    val canListen: Boolean

    /**
     * 음악이 나오고 있을 때 [mode] 대로 줄이거나(DUCK) 멈춘다(PAUSE). 한 번의 대화 안에서는 더 강한 쪽으로만 바뀌고,
     * [releaseMedia] 를 부르면 음악이 원래대로 돌아온다.
     */
    fun holdMedia(mode: AudioMode)
    fun releaseMedia()
    suspend fun speak(text: String)

    /** 목소리 캐릭터의 말투(높낮이·빠르기, 1.0 이 기본). 지원하지 않는 엔진은 무시한다. */
    fun setStyle(pitch: Float, rate: Float) = Unit

    /** 한국어 TTS 를 쓸 수 있는지(엔진 준비가 끝날 때까지 기다린다). 모르는 엔진은 true. */
    suspend fun koreanTtsReady(): Boolean = true

    /** 음악을 멈추지 못한 채 노래만 들렸다면 무응답이 아니라 [ListenResult.Unavailable] 로 돌려준다. */
    suspend fun listen(timeoutMs: Long): ListenResult
    fun release()
}

/**
 * 가족 알림. 운전자가 등록한 번호로만, 휴식을 시작할 때와 다시 출발할 때 문자를 보낸다.
 * 번호가 없거나 보낼 수 없으면 [send] 가 false 를 돌려준다.
 */
interface FamilyNotifier {
    val phone: String?

    /** 번호를 등록한다. 휴대전화 번호 형식이 아니면 지우고 null 을 돌려준다. */
    fun setPhone(input: String): String?
    suspend fun send(text: String): Boolean
}

/** 점검 화면의 신호 한 줄. [ok] 는 값을 실제로 받고 있는지. */
data class DiagSignal(val name: String, val value: String, val ok: Boolean)

/** 제어 한 번 시험한 결과. [detail] 은 실패 이유(SDK 오류 문구) 또는 성공 내용. */
data class DiagResult(val ok: Boolean, val detail: String)

/**
 * 차량 연결 점검(Pleos 빌드). 에뮬레이터·실차에서 신호를 받는지, 제어가 되는지를 한 화면에서 확인한다.
 * 시연 빌드에는 없다.
 */
interface Diagnostics {
    fun signals(): List<DiagSignal>
    val controls: List<String>
    suspend fun test(control: String): DiagResult
}

/** 시연용 조작(가상 주행에서만 제공). */
interface DemoControls {
    fun laneChange()
    fun hardBrake()
    fun togglePassenger()
    fun vehicleDrowsinessWarning()
    fun weave()
    fun toggleLaneAssist()
    fun laneIntervention()
    fun toggleRecirculation() = Unit
    fun handsOffWarning() = Unit
    fun cameraDrowsiness() = Unit
    fun parkForRest()
    fun resumeDriving()
    fun toggleMusic()
    fun skipTrack()
    fun togglePhoneCall()
}

/** 음성 체크 주기 등 플랫폼별 설정. 시연 빌드는 시간을 압축한다. */
data class CopilotConfig(
    val normalIntervalMs: Long,
    val cautionIntervalMs: Long,
    val napMs: Long,
    /** "조용히 해 줘" 뒤 먼저 말을 걸지 않는 시간. 새벽이나 오래 운전한 뒤에는 [quietModeRiskyMs]. */
    val quietModeMs: Long,
    val quietModeRiskyMs: Long,
    val steeringBaselineSamples: Int,
    val steeringWindowSamples: Int,
    val listenTimeoutMs: Long = 8_000,
    val restStopRadiusKm: Double = 30.0,
    /** 목적지가 이만큼 멀면 장거리로 보고 출발 직후 함께한다. */
    val longTripKm: Double = 100.0,
    /** 졸음 예보에 쓰는 평소 기상 시각(자정부터 분, 기본 오전 7시). 설정에서 바꾼다. */
    val usualWakeMinute: Int = 7 * 60,
    /** 곡이 이 안에 끝나면 곡 사이까지 기다린다. */
    val songGapWaitMs: Long = 60_000,
    /** 곡 넘기기·볼륨 조절 직후 이만큼은 미룬다. */
    val interactionGraceMs: Long = 120_000,
    /** 통화를 뺀 미루기는 합쳐서 이 시간을 넘기지 않는다. */
    val maxDeferMs: Long = 5 * 60_000,
    /** 출발할 때 어젯밤 잠을 묻고 첫 휴식을 미리 정한다. */
    val restPlanning: Boolean = true,
    /** 커피 냅: 커피를 사러 간 뒤 이만큼 지나면 다시 묻는다. */
    val coffeeWaitMs: Long = 5 * 60_000,
    /** 실제 공공데이터 대신 시연용 샘플 쉼터 4곳만 쓴다(정해진 결과가 필요한 자동 테스트용). */
    val sampleRestStops: Boolean = false,
)

class Platform(
    val vehicle: VehicleSource,
    val location: LocationSource,
    val navigator: Navigator,
    val cabin: CabinControl,
    val media: MediaSource,
    val config: CopilotConfig,
    val demo: DemoControls? = null,
    val diagnostics: Diagnostics? = null,
    private val onRelease: () -> Unit = {},
) {
    fun release() = onRelease()
}
