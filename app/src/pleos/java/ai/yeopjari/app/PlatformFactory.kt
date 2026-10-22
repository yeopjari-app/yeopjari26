package ai.yeopjari.app

import ai.pleos.playground.vehicle.Vehicle
import ai.yeopjari.app.ports.CabinControl
import ai.yeopjari.app.ports.CopilotConfig
import ai.yeopjari.app.ports.LocationSource
import ai.yeopjari.app.ports.Navigator
import ai.yeopjari.app.ports.Platform
import ai.yeopjari.app.ports.VehicleSource
import ai.yeopjari.core.GeoPoint
import ai.yeopjari.core.Gear
import ai.yeopjari.core.RestStop
import ai.yeopjari.core.VehicleSnapshot
import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Pleos Connect 빌드: Vehicle SDK 로 실제 차량 상태를 읽는다. */
fun createPlatform(context: Context): Platform {
    val vehicle = Vehicle(context.applicationContext)
    vehicle.initialize()
    val state = PleosVehicleState(vehicle)
    return Platform(
        vehicle = state,
        location = AndroidLocationSource(context),
        navigator = PleosNavigator(),
        cabin = PleosCabinControl(vehicle),
        config = CopilotConfig(
            normalIntervalMs = 15 * 60_000L,
            cautionIntervalMs = 5 * 60_000L,
            napMs = 15 * 60_000L,
            quietModeMs = 30 * 60_000L,
        ),
        onRelease = {
            state.stop()
            vehicle.release()
        },
    )
}

/**
 * 차량 상태를 Vehicle SDK 리스너로 받아 1초마다 표본으로 내보낸다.
 *
 * TODO(pleos): Pleos Playground API 참조에서 아래 값의 타입 API 이름을 확인해 [start] 에서
 *  capability 확인 → register 리스너를 연결한다. 패턴은 pleos-sample-vehicle-app 의 Door Position 예제
 *  (check…Capabilities → register… → unregister…)와 같다.
 *  - 차속(PERF_VEHICLE_SPEED, m/s → × 3.6) / 기어 선택(GEAR_SELECTION → P/R/N/D)  ← 필수
 *  - 방향지시등(TurnSignal 도메인) / 브레이크(Brake 도메인, 급제동 판단) / 조향각(SteeringWheel 도메인)
 *  - 조수석 탑승 여부(Seat 또는 Safety 도메인의 탑승·안전벨트 정보)
 *  - 운전자 주의 경고(Safety 도메인 / ADAS 상태, READ_ADAS_STATES 권한)
 *  연결 전까지는 값이 갱신되지 않아 동행이 시작되지 않는다.
 */
private class PleosVehicleState(private val vehicle: Vehicle) : VehicleSource {
    @Volatile private var speedKph = 0f
    @Volatile private var gear = Gear.UNKNOWN
    @Volatile private var turnSignalOn = false
    @Volatile private var hardBraking = false
    @Volatile private var steeringAngleDeg = 0f
    @Volatile private var passengerPresent = false
    @Volatile private var drowsinessWarning = false

    override val snapshots: Flow<VehicleSnapshot> = flow {
        start()
        while (true) {
            emit(
                VehicleSnapshot(
                    System.currentTimeMillis(), speedKph, gear,
                    turnSignalOn = turnSignalOn,
                    hardBraking = hardBraking,
                    steeringAngleDeg = steeringAngleDeg,
                    passengerPresent = passengerPresent,
                    drowsinessWarning = drowsinessWarning,
                )
            )
            delay(1_000)
        }
    }

    private fun start() {
        // TODO(pleos): 차속·기어·방향지시등·브레이크·조향각·조수석 탑승·운전자 주의 경고 리스너 등록
    }

    fun stop() {
        // TODO(pleos): start() 에서 등록한 리스너를 같은 인스턴스로 해제
    }
}

/**
 * TODO(pleos): capability 의 accessType 이 제어를 허용할 때만 호출하고, 성공 콜백에서 true 를 돌려준다.
 *  - [freshAir]: vehicle.getHVAC() 의 외기/내기 순환 API 로 외기 유입
 *  - [reclineSeat] / [restoreSeat]: vehicle.getSeat() 의 운전석 등받이 각도. 눕히기 전 각도를 저장했다가 되돌린다.
 *    P단 정차 중에만 호출된다.
 */
private class PleosCabinControl(private val vehicle: Vehicle) : CabinControl {
    override suspend fun freshAir(): Boolean = false
    override suspend fun reclineSeat(): Boolean = false
    override suspend fun restoreSeat(): Boolean = false
}

/**
 * TODO(pleos): NaviHelper SDK 로 경유지를 추가한다. 연결 전에는 false 를 돌려줘
 *  운전자에게 음성으로만 안내한다.
 */
private class PleosNavigator : Navigator {
    override suspend fun addWaypoint(stop: RestStop): Boolean = false
}

/** TODO(pleos): FusedLocation SDK 로 교체할 수 있다. 지금은 Android 기본 위치를 쓴다. */
private class AndroidLocationSource(context: Context) : LocationSource {
    private val manager = context.getSystemService(LocationManager::class.java)

    @SuppressLint("MissingPermission") // MainActivity 에서 권한을 요청한다.
    override suspend fun current(): GeoPoint? = runCatching {
        val last = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        last?.let { GeoPoint(it.latitude, it.longitude) }
    }.getOrNull()
}
