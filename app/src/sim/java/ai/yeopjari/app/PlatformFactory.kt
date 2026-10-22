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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 시연 빌드: 실제 1초 = 가상 1분으로 압축한 고속도로 주행. 30초 뒤 동행이 시작된다. */
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
        },
        config = CopilotConfig(normalIntervalMs = 20_000, cautionIntervalMs = 10_000),
        demo = drive,
    )
}

private class SimulatedDrive : VehicleSource, LocationSource, DemoControls {
    private val timeScale = 60L
    private val tickMs = 1_000L

    @Volatile private var gear = Gear.DRIVE
    @Volatile private var position = GeoPoint(37.40, 127.10)
    private var virtualTimeMs = System.currentTimeMillis()
    private var elapsedVirtualMs = 0L

    override val snapshots: Flow<VehicleSnapshot> = flow {
        while (true) {
            val speed = currentSpeed()
            emit(VehicleSnapshot(virtualTimeMs, speed, gear))
            delay(tickMs)
            val dtMs = tickMs * timeScale
            virtualTimeMs += dtMs
            elapsedVirtualMs += dtMs
            // 남쪽으로 이동: 위도 1도 ≈ 111km
            val km = speed * dtMs / 3_600_000.0
            position = position.copy(lat = position.lat - km / 111.0)
        }
    }

    /** 처음 2분은 시내(40km/h), 이후 고속도로(100km/h). */
    private fun currentSpeed(): Float = when {
        gear == Gear.PARK -> 0f
        elapsedVirtualMs < 2 * 60_000 -> 40f
        else -> 100f
    }

    override suspend fun current(): GeoPoint = position

    override fun parkForRest() {
        gear = Gear.PARK
    }

    override fun resumeDriving() {
        gear = Gear.DRIVE
    }
}
