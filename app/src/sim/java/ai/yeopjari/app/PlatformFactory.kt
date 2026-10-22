package ai.yeopjari.app

import ai.yeopjari.app.ports.CabinControl
import ai.yeopjari.app.ports.CopilotConfig
import ai.yeopjari.app.ports.DemoControls
import ai.yeopjari.app.ports.LocationSource
import ai.yeopjari.app.ports.Navigator
import ai.yeopjari.app.ports.Platform
import ai.yeopjari.app.ports.VehicleSource
import ai.yeopjari.core.GeoPoint
import ai.yeopjari.core.Gear
import ai.yeopjari.core.RestStop
import ai.yeopjari.core.VehicleSnapshot
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 시연 빌드: 실제 1초 = 가상 1분으로 압축한 고속도로 주행. 30초 뒤 동행이 시작된다.
 * 차선 변경·급제동 버튼으로 "바쁠 때 조용히" 동작을, 동승자 버튼으로 "옆자리가 차면 물러나기"를,
 * 차량 졸음 경고 버튼으로 차량 경고 연동을, 휴식 정차로 파워냅(15초)을 보여 준다.
 */
fun createPlatform(context: Context): Platform {
    val drive = SimulatedDrive()
    return Platform(
        vehicle = drive,
        location = drive,
        navigator = object : Navigator {
            override suspend fun addWaypoint(stop: RestStop) = true
        },
        cabin = object : CabinControl {
            override suspend fun freshAir() = true
            override suspend fun reclineSeat() = true
            override suspend fun restoreSeat() = true
        },
        config = CopilotConfig(
            normalIntervalMs = 20_000,
            cautionIntervalMs = 10_000,
            napMs = 15_000,
            quietModeMs = 60_000,
        ),
        demo = drive,
    )
}

private class SimulatedDrive : VehicleSource, LocationSource, DemoControls {
    private val timeScale = 60L
    private val tickMs = 500L

    @Volatile private var gear = Gear.DRIVE
    @Volatile private var position = GeoPoint(37.40, 127.10)
    @Volatile private var turnSignalUntil = 0L
    @Volatile private var hardBrakeUntil = 0L
    @Volatile private var passenger = false
    @Volatile private var warningUntil = 0L
    private var virtualTimeMs = System.currentTimeMillis()
    private var elapsedVirtualMs = 0L

    override val snapshots: Flow<VehicleSnapshot> = flow {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val speed = currentSpeed(now)
            emit(
                VehicleSnapshot(
                    timeMs = virtualTimeMs,
                    speedKph = speed,
                    gear = gear,
                    turnSignalOn = now < turnSignalUntil,
                    hardBraking = now < hardBrakeUntil,
                    passengerPresent = passenger,
                    drowsinessWarning = now < warningUntil,
                )
            )
            delay(tickMs)
            val dtMs = tickMs * timeScale
            virtualTimeMs += dtMs
            elapsedVirtualMs += dtMs
            // 남쪽으로 이동: 위도 1도 ≈ 111km
            val km = speed * dtMs / 3_600_000.0
            position = position.copy(lat = position.lat - km / 111.0)
        }
    }

    /** 처음 2분은 시내(40km/h), 이후 고속도로(100km/h). 급제동 중에는 60km/h. */
    private fun currentSpeed(now: Long): Float = when {
        gear == Gear.PARK -> 0f
        now < hardBrakeUntil -> 60f
        elapsedVirtualMs < 2 * 60_000 -> 40f
        else -> 100f
    }

    override suspend fun current(): GeoPoint = position

    override fun laneChange() {
        turnSignalUntil = SystemClock.elapsedRealtime() + 5_000
    }

    override fun hardBrake() {
        hardBrakeUntil = SystemClock.elapsedRealtime() + 3_000
    }

    override fun togglePassenger() {
        passenger = !passenger
    }

    override fun vehicleDrowsinessWarning() {
        warningUntil = SystemClock.elapsedRealtime() + 2_000
    }

    override fun parkForRest() {
        gear = Gear.PARK
    }

    override fun resumeDriving() {
        gear = Gear.DRIVE
    }
}
