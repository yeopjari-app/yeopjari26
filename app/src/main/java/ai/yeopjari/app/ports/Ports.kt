package ai.yeopjari.app.ports

import ai.yeopjari.core.GeoPoint
import ai.yeopjari.core.RestStop
import ai.yeopjari.core.VehicleSnapshot
import kotlinx.coroutines.flow.Flow

/** 차량 상태(차속·기어) 공급원. Pleos 는 Vehicle SDK, 시연은 가상 주행. */
interface VehicleSource {
    val snapshots: Flow<VehicleSnapshot>
}

interface LocationSource {
    suspend fun current(): GeoPoint?
}

/** 휴식 장소를 경로에 경유지로 추가한다. 성공하면 true. */
interface Navigator {
    suspend fun addWaypoint(stop: RestStop): Boolean
}

/**
 * 실내 제어. 지원하지 않는 기능은 false 를 돌려주고, 그때는 음성 안내로 대신한다.
 * 좌석 제어는 P단 정차 중 파워냅 모드에서만 쓴다.
 */
interface CabinControl {
    suspend fun freshAir(): Boolean
    suspend fun reclineSeat(): Boolean
    suspend fun restoreSeat(): Boolean
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
    suspend fun speak(text: String)
    suspend fun listen(timeoutMs: Long): ListenResult
    fun release()
}

/** 시연용 조작(가상 주행에서만 제공). */
interface DemoControls {
    fun laneChange()
    fun hardBrake()
    fun togglePassenger()
    fun vehicleDrowsinessWarning()
    fun parkForRest()
    fun resumeDriving()
}

/** 음성 체크 주기 등 플랫폼별 설정. 시연 빌드는 시간을 압축한다. */
data class CopilotConfig(
    val normalIntervalMs: Long,
    val cautionIntervalMs: Long,
    val napMs: Long,
    val quietModeMs: Long,
    val listenTimeoutMs: Long = 8_000,
    val restStopRadiusKm: Double = 30.0,
)

class Platform(
    val vehicle: VehicleSource,
    val location: LocationSource,
    val navigator: Navigator,
    val cabin: CabinControl,
    val config: CopilotConfig,
    val demo: DemoControls? = null,
    private val onRelease: () -> Unit = {},
) {
    fun release() = onRelease()
}
