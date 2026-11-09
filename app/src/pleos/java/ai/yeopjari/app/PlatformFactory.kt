package ai.yeopjari.app

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import ai.yeopjari.app.ports.CabinControl
import ai.yeopjari.app.ports.CopilotConfig
import ai.yeopjari.app.ports.DiagResult
import ai.yeopjari.app.ports.DiagSignal
import ai.yeopjari.app.ports.Diagnostics
import ai.yeopjari.app.ports.LocationSource
import ai.yeopjari.app.ports.Navigator
import ai.yeopjari.app.ports.Platform
import ai.yeopjari.app.ports.VehicleSource
import ai.yeopjari.app.voice.AndroidMediaSource
import ai.yeopjari.core.GeoPoint
import ai.yeopjari.core.Gear
import ai.yeopjari.core.GpsSpeed
import ai.yeopjari.core.RestStop
import ai.yeopjari.core.VehicleSnapshot
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "YeopjariPleos"

/**
 * Pleos Connect 빌드: Vehicle SDK 로 차의 신호를 읽고 실내를 제어하며, FusedLocation 으로 위치·속도를,
 * NaviHelper 로 경유지와 남은 거리를 다룬다.
 *
 * 공개 Vehicle SDK(2.3.2)에는 차속과 비상등 API 가 없다. 차속은 FusedLocation(없으면 기기 GPS) 위치 변화로 계산하고,
 * 비상등은 켤 수 없으므로 [PleosCabinControl.hazardLights] 가 false 를 돌려 음성 안내만 하게 한다.
 */
fun createPlatform(context: Context): Platform {
    val app = context.applicationContext
    val vehicle = Vehicle(app)
    // Pleos 서비스가 없는 기기(일반 휴대전화)에서도 앱이 죽지 않게 한다. 신호는 점검 화면에 '소식 없음'으로 보인다.
    runCatching { vehicle.initialize() }.onFailure { Log.w(TAG, "Vehicle init: ${it.message}") }
    val location = PleosLocation(app)
    val state = PleosVehicleState(app, vehicle, location)
    val navigator = PleosNavigator(app)
    val cabin = PleosCabinControl(vehicle)
    return Platform(
        vehicle = state,
        location = location,
        navigator = navigator,
        cabin = cabin,
        diagnostics = PleosDiagnostics(state, location, cabin, navigator),
        media = AndroidMediaSource(app),
        config = CopilotConfig(
            normalIntervalMs = 15 * 60_000L,
            cautionIntervalMs = 5 * 60_000L,
            napMs = 15 * 60_000L,
            quietModeMs = 20 * 60_000L,
            quietModeRiskyMs = 10 * 60_000L,
            // 1초마다 표본 → 처음 15분으로 이번 주행의 평소 조향을 정하고, 최근 1분을 본다
            steeringBaselineSamples = 15 * 60,
            steeringWindowSamples = 60,
        ),
        onRelease = {
            state.stop()
            navigator.release()
            location.release()
            vehicle.release()
        },
    )
}

private val DRIVER = SeatArea(SeatValue.ROW_1_LEFT)
private val PASSENGER = SeatArea(SeatValue.ROW_1_RIGHT)
private val DRIVER_WINDOW = WindowArea(WindowValue.ROW_1_LEFT)
private val WINDSHIELD = WindowArea(WindowValue.FRONT_WINDSHIELD)

/** 마지막 SDK 실패 문구. 점검 화면이 "왜 안 됐는지"를 보여 주는 데 쓴다. */
@Volatile private var lastSdkError: String? = null

private fun sdkFailed(what: String, message: String?) {
    Log.w(TAG, "$what failed: $message")
    lastSdkError = "$what: ${message ?: "오류"}"
}

/** 한 번의 비동기 SDK 호출을 기다린다. 실패·시간 초과는 null. 차량 통신은 IPC 라 결과가 콜백으로 온다. */
private suspend fun <T> sdkCall(what: String, block: (done: (T) -> Unit, fail: (Exception) -> Unit) -> Unit): T? {
    val answered = java.util.concurrent.atomic.AtomicBoolean(false)
    val result = withTimeoutOrNull(3_000) {
        suspendCancellableCoroutine<T?> { cont ->
            // 성공·실패 콜백이 둘 다 오거나 서로 다른 스레드에서 겹쳐 와도 한 번만 이어 간다(두 번 resume 하면 앱이 죽는다).
            fun finish(value: T?) {
                if (answered.compareAndSet(false, true) && cont.isActive) runCatching { cont.resume(value) }
            }
            runCatching {
                block({ finish(it) }, { e -> sdkFailed(what, e.message); finish(null) })
            }.onFailure { e ->
                sdkFailed(what, e.message)
                finish(null)
            }
        }
    }
    if (!answered.get()) sdkFailed(what, "3초 동안 응답 없음")
    return result
}

/**
 * FusedLocation 으로 위치·진행 방향을 받고, 연속된 두 위치로 차속을 계산한다(공개 Vehicle SDK 에 차속이 없다).
 * FusedLocation 을 쓸 수 없거나 3초 넘게 소식이 없으면 기기의 GPS(Android LocationManager)로 대신한다.
 * 터널에서 위치가 끊기면 D단인 동안 마지막 속도를 유지한다([GpsSpeed]).
 */
private class PleosLocation(private val context: Context) : LocationSource {
    private val fused: FusedLocation? = runCatching { FusedLocation(context) }.getOrNull()
    private val speed = GpsSpeed()
    @Volatile private var lastPoint: GeoPoint? = null
    @Volatile private var lastHeading: Double? = null
    @Volatile private var lastFusedMs = 0L
    /** 지금 위치를 어디서 받는지(점검 화면용). */
    @Volatile var source: String = "없음"
        private set

    private val listener = object : FusedLocationListener {
        override fun onChanged(data: LocationData?) {
            if (data == null) return
            lastFusedMs = SystemClock.elapsedRealtime()
            source = "FusedLocation"
            // 방향을 모르면 NaN·음수가 올 수 있다: 0~360 사이의 값만 쓴다.
            accept(data.latitude, data.longitude, data.heading.toDouble().takeIf { it.isFinite() && it >= 0.0 && it <= 360.0 }, null)
        }

        override fun onError(exception: Exception) {
            Log.w(TAG, "FusedLocation: ${exception.message}")
        }
    }

    private val deviceGps = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            // FusedLocation 이 살아 있으면 그쪽을 쓴다
            if (SystemClock.elapsedRealtime() - lastFusedMs < 3_000) return
            source = "기기 GPS"
            accept(loc.latitude, loc.longitude, loc.bearing.toDouble().takeIf { loc.hasBearing() }, loc.speed.takeIf { loc.hasSpeed() }?.let { it * 3.6f })
        }
        @Deprecated("API 29 미만 호환")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private fun accept(lat: Double, lon: Double, heading: Double?, gpsKph: Float?) {
        speed.update(lat, lon, SystemClock.elapsedRealtime(), gpsKph)
        lastPoint = GeoPoint(lat, lon)
        lastHeading = heading
    }

    init {
        runCatching {
            val f = fused ?: error("FusedLocation unavailable")
            f.initialize()
            if (f.isSupported()) f.registerFusedLocationCallback(listener)
            else Log.w(TAG, "FusedLocation not supported on this device")
        }.onFailure { Log.w(TAG, "FusedLocation init: ${it.message}") }
        startAndroidGps()
    }

    /** 위치 권한은 화면이 처음 뜰 때 묻는다. 권한이 늦게 오면 [retryAndroidGps] 로 다시 시도한다. */
    private var androidStarted = false
    private fun startAndroidGps() {
        if (androidStarted) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val lm = context.getSystemService(LocationManager::class.java)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, deviceGps, Looper.getMainLooper())
            androidStarted = true
        }.onFailure { Log.w(TAG, "Android GPS: ${it.message}") }
    }

    fun retryAndroidGps() = startAndroidGps()

    fun speedKph(gear: Gear): Float = speed.speedKph(SystemClock.elapsedRealtime(), gear)

    /** 터널 등에서 위치 없이 마지막 속도를 유지하는 중인지. */
    fun holding(gear: Gear): Boolean = speed.holding(SystemClock.elapsedRealtime(), gear)

    override suspend fun current(): GeoPoint? = lastPoint

    /** 시속 10km 이상으로 달릴 때의 진행 방향만 믿는다. */
    override suspend fun headingDeg(): Double? = lastHeading?.takeIf { speedKph(Gear.DRIVE) >= 10f }

    fun release() {
        runCatching { fused?.unregisterFusedLocationCallback(listener) }
        runCatching { fused?.release() }
        runCatching { context.getSystemService(LocationManager::class.java).removeUpdates(deviceGps) }
    }
}

/** SDK 기어 → 앱 기어. 수동 단(1~9)은 주행으로 본다. */
internal fun mapGear(value: VehicleGear?): Gear = when (value) {
    null -> Gear.UNKNOWN
    VehicleGear.GEAR_PARK -> Gear.PARK
    VehicleGear.GEAR_REVERSE -> Gear.REVERSE
    VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
    VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
    else -> Gear.DRIVE // GEAR_DRIVE, GEAR_1..9
}

/**
 * Android Automotive 표준 기어 값(android.car.VehicleGear: UNKNOWN 0, NEUTRAL 1, REVERSE 2, PARK 4, DRIVE 8, 1단 이상 16~)을 앱 기어로.
 * 수동 단(1~9)은 주행으로 본다.
 */
internal fun mapCarGear(value: Int): Gear = when (value) {
    4 -> Gear.PARK
    2 -> Gear.REVERSE
    1 -> Gear.NEUTRAL
    0 -> Gear.UNKNOWN
    else -> if (value >= 8) Gear.DRIVE else Gear.UNKNOWN
}

/**
 * Android Automotive CarPropertyManager 로 GEAR_SELECTION 을 읽는다. 차량 서비스가 없는 기기(일반 휴대전화)나
 * 권한이 없을 때는 null 을 돌려 Pleos 기어 API 로 대신하게 한다. android.car 클래스는 이 클래스 안에서만 쓴다.
 */
private class CarGearProperty private constructor(private val car: android.car.Car, private val props: android.car.hardware.property.CarPropertyManager) {
    /** 0(UNKNOWN)은 '값 없음'으로 보아 Pleos 기어 API 로 대신하게 한다. */
    /** 권한이 없으면(일반 앱에는 CAR_POWERTRAIN 이 주어지지 않을 수 있다) 매초 다시 묻지 않고 Pleos 기어 API 로만 읽는다. */
    @Volatile private var denied = false

    fun read(): Int? = if (denied) null else try {
        props.getIntProperty(android.car.VehiclePropertyIds.GEAR_SELECTION, 0).takeIf { it != 0 }
    } catch (e: SecurityException) {
        denied = true
        Log.i(TAG, "GEAR_SELECTION 권한 없음 — Pleos 기어 API 사용")
        null
    } catch (t: Throwable) {
        null
    }

    fun close() {
        runCatching { car.disconnect() }
    }

    companion object {
        fun create(context: Context): CarGearProperty? = try {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) null
            else {
                val car = android.car.Car.createCar(context)
                val props = car?.getCarManager(android.car.Car.PROPERTY_SERVICE) as? android.car.hardware.property.CarPropertyManager
                if (car != null && props != null) CarGearProperty(car, props) else null
            }
        } catch (t: Throwable) {
            null
        }
    }
}

internal fun isTurnSignalOn(value: TurnSignalValue?): Boolean = value != null && value != TurnSignalValue.OFF

/** 계기판 경고등 중 운전자 주의 경고가 켜졌거나 깜빡이면 차량 졸음 경고로 본다. */
internal fun isDriverAttentionWarning(value: Telltales?): Boolean = value?.telltales.orEmpty().any {
    it.type == TelltaleType.DRIVER_ATTENTION && (it.state == TelltaleState.ON || it.state == TelltaleState.BLINK)
}

private fun lit(state: TelltaleState?) = state == TelltaleState.ON || state == TelltaleState.BLINK

/** 차로 유지 보조(LKA) 또는 차로 유지 보조 주행(LFA) 표시등이 켜져 있으면 보조가 켜진 것으로 본다. */
internal fun isLaneAssistOn(value: Telltales?): Boolean = value?.telltales.orEmpty().any {
    (it.type == TelltaleType.LKA || it.type == TelltaleType.LFA) && lit(it.state)
}

/**
 * 차가 차로 이탈을 막으려고 개입하는 중인지: 비상 차로 유지(ELK) 표시등이 켜지거나, LKA 표시등이 깜빡일 때.
 * 공개 SDK 는 켜짐·깜빡임·꺼짐과 색만 알려 주므로, 실제 차가 어떤 상태를 어떻게 표시하는지는 에뮬레이터·실차에서 확인한다.
 */
internal fun isLaneIntervention(value: Telltales?): Boolean = value?.telltales.orEmpty().any {
    (it.type == TelltaleType.ELK && lit(it.state)) || (it.type == TelltaleType.LKA && it.state == TelltaleState.BLINK)
}

/** 급제동: 브레이크를 밟은 채 2초 사이(1초 간격 표본 3개) 시속 12km 이상 줄었다. 공개 SDK 는 페달 눌림만 알려 준다. */
internal fun isHardBraking(brakePressed: Boolean, lastSpeeds: List<Float>): Boolean =
    brakePressed && lastSpeeds.size == 3 && lastSpeeds.first() - lastSpeeds.last() >= 12f

/**
 * 차량 상태를 Vehicle SDK 리스너로 받아 1초마다 표본으로 내보낸다.
 * 기어·방향지시등·브레이크·조향각·조수석 탑승·계기판 경고등(운전자 주의 경고)·와이퍼.
 */
private class PleosVehicleState(context: Context, private val vehicle: Vehicle, private val location: PleosLocation) : VehicleSource {
    @Volatile private var gear = Gear.UNKNOWN
    fun gearNow(): Gear = gear
    @Volatile private var turnSignalOn = false
    @Volatile private var brakePressed = false
    @Volatile private var steeringAngleDeg = 0f
    @Volatile private var passengerPresent = false
    @Volatile private var drowsinessWarning = false
    @Volatile private var wipersOn = false
    @Volatile private var laneAssistOn = false
    @Volatile private var laneIntervention = false
    @Volatile private var recirculation = false
    private val speeds = ArrayDeque<Float>()

    private val appContext = context.applicationContext
    @Volatile private var carGear: CarGearProperty? = null
    @Volatile private var carGearTried = false

    /**
     * 기어는 1초마다 지금 값을 읽는다. Pleos SDK 2.3.2 는 기어 API 전체를 사용 중단 예정으로 두고 Android Automotive 의
     * 표준 차량 속성(GEAR_SELECTION)을 쓰라고 안내하므로 그것을 먼저 쓴다. 그 속성을 읽을 수 없는 기기에서만 Pleos 기어 API 로 대신한다.
     */
    private suspend fun pollGear() {
        // 차량 서비스 연결·읽기는 화면(메인 스레드)을 막지 않게 IO 에서 한다.
        if (!carGearTried) {
            carGearTried = true
            carGear = withContext(kotlinx.coroutines.Dispatchers.IO) { CarGearProperty.create(appContext) }
        }
        withContext(kotlinx.coroutines.Dispatchers.IO) { carGear?.read() }?.let { raw ->
            seen("기어")
            errors.remove("gear")
            onGearValue(mapCarGear(raw))
            return
        }
        @Suppress("DEPRECATION") // 표준 속성을 못 읽는 기기에서만 쓰는 대체 경로. 1초 표본 주기가 늘어나지 않게 0.8초까지만 기다린다.
        val value = withTimeoutOrNull(800) {
            sdkCall<VehicleGear?>("gear") { done, fail -> vehicle.getGear().getCurrentGearSelectionState({ done(it) }, fail) }
        } ?: run { errors["gear"] = "읽기 실패"; return }
        seen("기어")
        errors.remove("gear")
        onGear(value)
    }

    /** 잠깐 '알 수 없음'이 오면(시동 상태 변화 등) 마지막 기어를 유지한다: P에서 낮잠 중 출발로 오인하지 않게. */
    internal fun onGear(value: VehicleGear?) = onGearValue(mapGear(value))

    private fun onGearValue(g: Gear) {
        if (g != Gear.UNKNOWN || gear == Gear.UNKNOWN) gear = g
    }
    private val turnL = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(value: TurnSignalValue?) { seen("방향지시등"); turnSignalOn = isTurnSignalOn(value) }
        override fun onFailed(exception: Exception) = warn("turn signal", exception)
    }
    private val brakeL = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(value: Boolean?) { seen("브레이크"); brakePressed = value == true }
        override fun onFailed(exception: Exception) = warn("brake", exception)
    }
    private val steerL = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(value: Float?) { seen("조향각"); if (value != null) steeringAngleDeg = value }
        override fun onFailed(exception: Exception) = warn("steering", exception)
    }
    private val seatL = object : SeatOccupancyListener {
        override fun onSeatOccupancyUpdated(value: Boolean?) { seen("조수석 탑승"); passengerPresent = value == true }
        override fun onFailed(exception: Exception) = warn("passenger seat", exception)
    }
    private val telltaleL = object : TelltaleInfoListener {
        override fun onTelltaleInfoUpdated(value: Telltales?) {
            seen("운전자 주의 경고")
            drowsinessWarning = isDriverAttentionWarning(value)
            laneAssistOn = isLaneAssistOn(value)
            laneIntervention = isLaneIntervention(value)
        }
        override fun onFailed(exception: Exception) = warn("telltales", exception)
    }
    // SDK 2.3.2 는 내기 순환 상태도 이 콜백 이름(onHvacAutoRecirculationStateUpdated)으로 알려 준다
    private val recircL = object : HvacRecirculationStateListener {
        override fun onHvacAutoRecirculationStateUpdated(value: Boolean?) { seen("내기 순환"); recirculation = value == true }
        override fun onFailed(exception: Exception) = warn("recirculation", exception)
    }
    private val wiperL = object : WiperStateListener {
        override fun onWiperStateUpdated(value: WiperState?) { seen("와이퍼"); wipersOn = value == WiperState.ON }
        override fun onFailed(exception: Exception) = warn("wiper", exception)
    }

    override val snapshots: Flow<VehicleSnapshot> = flow {
        start()
        while (true) {
            location.retryAndroidGps()
            pollGear()
            val speed = location.speedKph(gear)
            speeds.addLast(speed)
            while (speeds.size > 3) speeds.removeFirst()
            val hardBraking = isHardBraking(brakePressed, speeds)
            emit(
                VehicleSnapshot(
                    System.currentTimeMillis(), speed, gear,
                    turnSignalOn = turnSignalOn,
                    hardBraking = hardBraking,
                    steeringAngleDeg = steeringAngleDeg,
                    passengerPresent = passengerPresent,
                    drowsinessWarning = drowsinessWarning,
                    wipersOn = wipersOn,
                    laneAssistOn = laneAssistOn,
                    laneIntervention = laneIntervention,
                    recirculationOn = recirculation,
                )
            )
            delay(1_000)
        }
    }

    private fun start() {
        reg("turn signal") { vehicle.getTurnSignal().registerTurnSignalState(turnL) }
        reg("brake") { vehicle.getBrake().registerBrakePedalStatus(brakeL) }
        reg("steering") { vehicle.getSteeringWheel().registerSteeringWheelAngle(steerL) }
        reg("passenger seat") { vehicle.getSeat().registerSeatOccupancy(PASSENGER, seatL) }
        reg("telltales") { vehicle.getSafety().registerTelltales(telltaleL) }
        reg("wiper") { vehicle.getWiper().registerWiperState(WINDSHIELD, wiperL) }
        reg("recirculation") { vehicle.getHVAC().registerHvacRecirculationState(DRIVER, recircL) }
    }

    fun stop() {
        carGear?.close()
        // 등록할 때와 같은 리스너 인스턴스로 해제한다
        reg("turn signal off") { vehicle.getTurnSignal().unregisterTurnSignalState(turnL) }
        reg("brake off") { vehicle.getBrake().unregisterBrakePedalStatus(brakeL) }
        reg("steering off") { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steerL) }
        reg("passenger seat off") { vehicle.getSeat().unregisterSeatOccupancy(PASSENGER, seatL) }
        reg("telltales off") { vehicle.getSafety().unregisterTelltales(telltaleL) }
        reg("wiper off") { vehicle.getWiper().unregisterWiperState(WINDSHIELD, wiperL) }
        reg("recirculation off") { vehicle.getHVAC().unregisterHvacRecirculationState(DRIVER, recircL) }
    }

    /** 지원하지 않는 차량·권한 문제로 하나가 실패해도 나머지 신호는 계속 받는다. */
    private inline fun reg(what: String, block: () -> Unit) {
        runCatching(block).onFailure { Log.w(TAG, "register $what: ${it.message}"); errors[what] = "등록 실패: ${it.message}" }
    }

    private fun warn(what: String, e: Exception) {
        Log.w(TAG, "$what listener: ${e.message}")
        errors[what] = e.message ?: "오류"
    }

    /** 신호별 마지막으로 값을 받은 시각과 오류(점검 화면용). */
    private val seenAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val errors = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun seen(name: String) { seenAt[name] = SystemClock.elapsedRealtime() }

    /** 점검 화면의 신호 목록. 한 번이라도 값을 받았으면 ✓, 오류가 있으면 그 문구. */
    fun diagSignals(): List<DiagSignal> {
        fun row(name: String, value: String, key: String = name): DiagSignal {
            val err = errors.entries.firstOrNull { it.key.startsWith(englishKey[key] ?: key) }?.value
            val ok = seenAt.containsKey(key)
            return DiagSignal(name, if (!ok && err != null) err.take(40) else if (ok) value else "소식 없음", ok)
        }
        return listOf(
            row("기어", gear.name),
            row("방향지시등", if (turnSignalOn) "켜짐" else "꺼짐"),
            row("브레이크", if (brakePressed) "밟음" else "뗌"),
            row("조향각", "%.0f°".format(steeringAngleDeg)),
            row("조수석 탑승", if (passengerPresent) "있음" else "없음"),
            row("운전자 주의 경고", if (drowsinessWarning) "울림" else "없음"),
            row("와이퍼", if (wipersOn) "작동" else "멈춤"),
            row("내기 순환", if (recirculation) "켜짐" else "외기"),
            row("차로 유지 보조(LKA·LFA)", if (laneAssistOn) "켜짐" else "꺼짐", key = "운전자 주의 경고"),
            row("차로 이탈 개입(ELK·LKA 깜빡임)", if (laneIntervention) "개입 중" else "없음", key = "운전자 주의 경고"),
            // 차량 데이터에는 있지만 공개 SDK 로 읽을 수 없는 값. 공개되면 여기에 리스너를 단다.
            DiagSignal("핸들 파지 감지 · 카메라 졸음(DMS)", "공개 API 대기", false),
            DiagSignal(
                "차속(위치로 계산)",
                "%.0f km/h · %s%s".format(location.speedKph(gear), location.source, if (location.holding(gear)) " · 터널 유지" else ""),
                location.source != "없음",
            ),
        )
    }

    private val englishKey = mapOf(
        "기어" to "gear", "방향지시등" to "turn signal", "브레이크" to "brake", "조향각" to "steering",
        "조수석 탑승" to "passenger seat", "운전자 주의 경고" to "telltales", "와이퍼" to "wiper",
        "내기 순환" to "recirculation",
    )
}

/**
 * 실내 제어. 온도·풍량·통풍·창문은 capability 범위를 먼저 확인하고, 모든 제어는 성공 콜백을 받았을 때만 true
 * (실패하면 앱이 음성으로 대신 부탁한다). 창문·등받이·온도·풍량·통풍은 바꾸기 전 값을 저장했다가 되돌린다.
 * 브레이크·조향·경적은 쓰지 않는다.
 */
private class PleosCabinControl(private val vehicle: Vehicle) : CabinControl {
    @Volatile private var savedWindow: Int? = null
    @Volatile private var savedBackrest: Int? = null
    /** 첫 깨우기 전의 온도·풍량·통풍. 깨우기를 되풀이해도 온도가 계속 내려가지 않게 이 값에서 계산하고, 끝나면 되돌린다. */
    @Volatile private var savedTemp: Float? = null
    @Volatile private var savedFan: Int? = null
    @Volatile private var savedVent: Int? = null
    @Volatile private var savedSeatHeat: Int? = null
    @Volatile private var savedWheelHeat: Int? = null

    override suspend fun freshAir(): Boolean =
        sdkCall<Any>("recirculation off") { done, fail ->
            vehicle.getHVAC().setHvacRecirculation(DRIVER, false, { _, v -> done(v ?: false) }, fail)
        } != null

    override suspend fun wakeBurst(): Boolean =
        // 차량 서비스가 응답하지 않아도 깨우기가 수십 초 동안 목소리를 붙잡지 않게 전체를 12초로 묶는다.
        withTimeoutOrNull(12_000) { wakeBurstSteps() } ?: false

    private suspend fun wakeBurstSteps(): Boolean {
        var any = false
        val hvac = vehicle.getHVAC()
        // 찬 바람: 설정 온도를 3도 낮추고(범위 안에서) 풍량을 최대로, 외기 유입
        val bounds = sdkCall<Map<SeatArea, ai.pleos.playground.vehicle.api.model.capability.AreaBounds<Float>>>("temp bounds") { done, fail ->
            hvac.checkHvacTemperatureCapabilities({ _, b, _ -> done(b) }, fail)
        }?.get(DRIVER)
        val temp = savedTemp ?: sdkCall<Float?>("temp") { done, fail -> hvac.getHvacTemperature(DRIVER, TemperatureUnit.CELSIUS, { done(it) }, fail) }
            ?.also { savedTemp = it }
        if (temp != null) {
            val target = max(bounds?.minValue ?: (temp - 3f), temp - 3f)
            any = sdkCall<Any>("temp down") { done, fail ->
                hvac.setHvacTemperature(DRIVER, target, TemperatureUnit.CELSIUS, { _, v -> done(v ?: target) }, fail)
            } != null || any
        }
        val fanMax = sdkCall<Map<SeatArea, ai.pleos.playground.vehicle.api.model.capability.AreaBounds<Int>>>("fan bounds") { done, fail ->
            hvac.checkHvacFanSpeedCapabilities({ _, b -> done(b) }, fail)
        }?.get(DRIVER)?.maxValue
        if (fanMax != null) {
            if (savedFan == null) savedFan = sdkCall<Int?>("fan") { done, fail -> hvac.getHvacFanSpeed(DRIVER, { done(it) }, fail) }
            // 원래 풍량을 읽지 못했으면 되돌릴 수 없으니 바꾸지 않는다.
            if (savedFan != null) any = sdkCall<Any>("fan max") { done, fail -> hvac.setHvacFanSpeed(DRIVER, fanMax, { _, v -> done(v ?: fanMax) }, fail) } != null || any
        }
        any = freshAir() || any
        // 통풍 시트 켜기(범위 최대), 시트·핸들 열선 끄기
        val seat = vehicle.getSeat()
        val ventMax = sdkCall<Map<SeatArea, ai.pleos.playground.vehicle.api.model.capability.AreaBounds<Int>>>("vent bounds") { done, fail ->
            seat.checkSeatVentilationCapabilities({ _, b -> done(b) }, fail)
        }?.get(DRIVER)?.maxValue
        if (ventMax != null) {
            if (savedVent == null) savedVent = sdkCall<Int?>("vent") { done, fail -> seat.getHvacSeatVentilationState(DRIVER, { done(it) }, fail) }
            if (savedVent != null) any = sdkCall<Any>("seat vent") { done, fail -> seat.setHvacSeatVentilation(DRIVER, ventMax, { _, v -> done(v ?: ventMax) }, fail) } != null || any
        }
        // 열선은 켜져 있을 때만 끄고, 끝나면 원래 단계로 되돌린다(읽지 못하면 건드리지 않는다).
        if (savedSeatHeat == null) savedSeatHeat = sdkCall<Int?>("seat heat") { done, fail -> seat.getHvacSeatTemperature(DRIVER, { done(it) }, fail) }?.takeIf { it > 0 }
        if (savedSeatHeat != null) {
            any = sdkCall<Any>("seat heat off") { done, fail -> seat.setHvacSeatTemperature(DRIVER, 0, { _, v -> done(v ?: 0) }, fail) } != null || any
        }
        val wheel = vehicle.getSteeringWheel()
        if (savedWheelHeat == null) savedWheelHeat = sdkCall<Int?>("wheel heat") { done, fail -> wheel.getHvacSteeringWheelHeatState({ done(it) }, fail) }?.takeIf { it > 0 }
        if (savedWheelHeat != null) {
            any = sdkCall<Any>("wheel heat off") { done, fail -> wheel.setHvacSteeringWheelHeat(0, { v -> done(v ?: 0) }, fail) } != null || any
        }
        // 실내등 세 번 깜빡임
        val light = vehicle.getLight()
        // 깜빡인 뒤에는 원래 스위치 위치(꺼짐·켜짐·자동)로 돌려놓는다. 읽지 못하면 꺼 둔다.
        val lightBefore = sdkCall<LightSwitch?>("cabin light") { done, fail -> light.getCabinLightSwitch({ done(it) }, fail) } ?: LightSwitch.OFF
        repeat(3) {
            var on = false
            try {
                on = sdkCall<Any>("cabin light on") { done, fail -> light.setCabinLightSwitch(LightSwitch.ON, { _ -> done(Unit) }, fail) } != null
                delay(350)
            } finally {
                // 중간에 취소돼도 실내등이 켜진 채로 남지 않게 끈다
                withContext(NonCancellable) { sdkCall<Any>("cabin light back") { done, fail -> light.setCabinLightSwitch(lightBefore, { _ -> done(Unit) }, fail) } }
            }
            delay(350)
            any = on || any
        }
        return any
    }

    override suspend fun restoreClimate(): Boolean {
        val hvac = vehicle.getHVAC()
        var ok = true
        savedTemp?.let { t ->
            ok = sdkCall<Any>("temp restore") { done, fail -> hvac.setHvacTemperature(DRIVER, t, TemperatureUnit.CELSIUS, { _, v -> done(v ?: t) }, fail) } != null && ok
            savedTemp = null
        }
        savedFan?.let { f ->
            ok = sdkCall<Any>("fan restore") { done, fail -> hvac.setHvacFanSpeed(DRIVER, f, { _, v -> done(v ?: f) }, fail) } != null && ok
            savedFan = null
        }
        savedVent?.let { v0 ->
            ok = sdkCall<Any>("vent restore") { done, fail -> vehicle.getSeat().setHvacSeatVentilation(DRIVER, v0, { _, v -> done(v ?: v0) }, fail) } != null && ok
            savedVent = null
        }
        savedSeatHeat?.let { h ->
            ok = sdkCall<Any>("seat heat restore") { done, fail -> vehicle.getSeat().setHvacSeatTemperature(DRIVER, h, { _, v -> done(v ?: h) }, fail) } != null && ok
            savedSeatHeat = null
        }
        savedWheelHeat?.let { h ->
            ok = sdkCall<Any>("wheel heat restore") { done, fail -> vehicle.getSteeringWheel().setHvacSteeringWheelHeat(h, { v -> done(v ?: h) }, fail) } != null && ok
            savedWheelHeat = null
        }
        return ok
    }

    /** 운전석 창문을 capability 범위의 약 5%(보통 창문 이동 거리 50cm 기준 2~3cm)만 열고, 닫을 때는 연 전의 위치로 되돌린다. */
    override suspend fun crackWindow(open: Boolean): Boolean {
        val window = vehicle.getWindow()
        if (open) {
            val b = sdkCall<Map<WindowArea, ai.pleos.playground.vehicle.api.model.capability.AreaBounds<Int>>>("window bounds") { done, fail ->
                window.checkWindowPositionCapabilities({ _, m -> done(m) }, fail)
            }?.get(DRIVER_WINDOW) ?: return false
            val now = sdkCall<Int?>("window pos") { done, fail -> window.getCurrentWindowPosition(DRIVER_WINDOW, { done(it) }, fail) } ?: b.minValue
            val target = min(b.maxValue, b.minValue + max(1, ((b.maxValue - b.minValue) * 0.05).roundToInt()))
            if (now >= target) return true // 이미 그만큼 열려 있다
            // 응답이 늦어도 창문은 이미 움직였을 수 있으니, 보내기 전에 원래 위치를 저장해 두고 닫을 때 그리로 되돌린다.
            if (savedWindow == null) savedWindow = now
            return sdkCall<Any>("window crack") { done, fail -> window.setWindowPosition(DRIVER_WINDOW, target, { _, v -> done(v ?: target) }, fail) } != null
        }
        val back = savedWindow ?: return true
        val ok = sdkCall<Any>("window restore") { done, fail -> window.setWindowPosition(DRIVER_WINDOW, back, { _, v -> done(v ?: back) }, fail) } != null
        if (ok) savedWindow = null
        return ok
    }

    /** 운전석 등받이를 4초 동안 뒤로 눕힌다. P단 정차 중 파워냅에서만 불린다. */
    override suspend fun reclineSeat(): Boolean {
        val seat = vehicle.getSeat()
        // 이미 눕힌 상태에서 다시 불려도 처음 위치를 지킨다.
        if (savedBackrest == null) savedBackrest = sdkCall<Int?>("backrest pos") { done, fail -> seat.getCurrentSeatBackrestAnglePosition(DRIVER, { done(it) }, fail) }
        try {
            val started = sdkCall<Any>("recline") { done, fail -> seat.moveSeatBackrestAngle(DRIVER, MoveControl.BACKWARD, { _, _ -> done(Unit) }, fail) } != null
            if (!started) return false
            delay(4_000)
            return true
        } finally {
            // 시작 응답이 늦었거나 도중에 출발해 취소돼도 등받이가 끝까지 넘어가지 않게 반드시 멈춘다.
            withContext(NonCancellable) {
                sdkCall<Any>("recline stop") { done, fail -> seat.moveSeatBackrestAngle(DRIVER, MoveControl.STOP, { _, _ -> done(Unit) }, fail) }
            }
        }
    }

    /** 눕히기 전 위치까지 등받이를 세운다(최대 8초). 위치를 모르면 4초만 세운다. */
    override suspend fun restoreSeat(): Boolean {
        val seat = vehicle.getSeat()
        val target = savedBackrest
        try {
            val started = sdkCall<Any>("raise") { done, fail -> seat.moveSeatBackrestAngle(DRIVER, MoveControl.FORWARD, { _, _ -> done(Unit) }, fail) } != null
            if (!started) return false
            val until = System.currentTimeMillis() + if (target == null) 4_000 else 8_000
            var first: Int? = null
            while (System.currentTimeMillis() < until) {
                delay(300)
                if (target == null) continue
                val now = sdkCall<Int?>("backrest pos") { done, fail -> seat.getCurrentSeatBackrestAnglePosition(DRIVER, { done(it) }, fail) } ?: continue
                if (first == null) first = now
                // 위치 값이 커지는지 작아지는지는 차마다 다를 수 있어, 저장한 값을 지나치면 멈춘다
                val reached = if (first <= target) now >= target else now <= target
                if (reached) break
            }
            savedBackrest = null
            return true
        } finally {
            // 응답이 늦었거나 도중에 취소돼도 등받이가 끝까지 움직이지 않게 반드시 멈춘다.
            withContext(NonCancellable) {
                sdkCall<Any>("raise stop") { done, fail -> seat.moveSeatBackrestAngle(DRIVER, MoveControl.STOP, { _, _ -> done(Unit) }, fail) }
            }
        }
    }

    /** 공개 Vehicle SDK(2.3.2)에는 비상등 제어가 없다. 켜지 못했다고 알려 음성으로 운전자에게 부탁하게 한다. */
    override suspend fun hazardLights(on: Boolean): Boolean = false
}

/** NaviHelper 로 휴게소를 경유지로 넣고, 목적지까지 남은 거리를 읽는다. */
private class PleosNavigator(context: Context) : Navigator {
    private val navi = NaviHelper(context)
    @Volatile var ready = false
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile private var waypointResult: CompletableDeferred<Boolean>? = null
    @Volatile private var destination: CompletableDeferred<DestinationInfo?>? = null

    private val listener = object : NaviHelperEventListener {
        override fun onWaypointChanged(info: WaypointChangedInfo) { waypointResult?.complete(true) }
        override fun onError(code: ai.pleos.playground.navi.constants.NaviErrorCode) {
            Log.w(TAG, "NaviHelper error: $code")
            lastError = "NaviHelper: $code"
            waypointResult?.complete(false)
            destination?.complete(null)
        }
        override fun onDestinationInfo(info: DestinationInfo) { destination?.complete(info) }
    }

    init {
        runCatching {
            navi.initialize({ ready = true }, { Log.w(TAG, "NaviHelper init: ${it.message}"); lastError = "초기화 실패: ${it.message}" })
            navi.addListener(listener)
        }.onFailure { Log.w(TAG, "NaviHelper: ${it.message}") }
    }

    override suspend fun addWaypoint(stop: RestStop): Boolean {
        if (!ready) return false
        val result = CompletableDeferred<Boolean>().also { waypointResult = it }
        runCatching {
            navi.addWaypoint(RequestWaypointInfo(stop.point.lat, stop.point.lon, stop.name, WaypointIndex.FIRST, "", "", "", ""))
        }.onFailure { Log.w(TAG, "addWaypoint: ${it.message}"); return false }
        return withTimeoutOrNull(5_000) { result.await() } ?: false
    }

    /** 경로 안내 중일 때 목적지까지 남은 거리(km). 안내 중이 아니면 null. */
    override suspend fun remainingKm(): Double? {
        if (!ready) return null
        val result = CompletableDeferred<DestinationInfo?>().also { destination = it }
        runCatching { navi.getDestinationInfo() }.onFailure { return null }
        val info = withTimeoutOrNull(3_000) { result.await() } ?: return null
        return info.remainDistance.takeIf { it > 0 }?.let { it / 1000.0 }
    }

    fun release() {
        runCatching { navi.removeListener(listener) }
        runCatching { navi.release() }
    }
}

/**
 * 차량 연결 점검. 신호는 [PleosVehicleState] 가 받은 값 그대로, 제어는 실제 앱이 쓰는 것과 같은 함수를 부른다.
 * 실패하면 SDK 가 돌려준 오류 문구를 그대로 보여 준다. 등받이는 P단에서만 시험한다.
 */
private class PleosDiagnostics(
    private val state: PleosVehicleState,
    private val location: PleosLocation,
    private val cabin: PleosCabinControl,
    private val navigator: PleosNavigator,
) : Diagnostics {
    override fun signals(): List<DiagSignal> = state.diagSignals() + DiagSignal(
        "NaviHelper",
        if (navigator.ready) "연결됨" else navigator.lastError?.take(40) ?: "연결 중",
        navigator.ready,
    )

    override val controls = listOf(
        C_AIR, C_WAKE, C_WIN_OPEN, C_WIN_CLOSE, C_RECLINE, C_RESTORE, C_WAYPOINT, C_REMAIN, C_HAZARD,
    )

    override suspend fun test(control: String): DiagResult {
        lastSdkError = null
        val ok: Boolean = when (control) {
            C_AIR -> cabin.freshAir()
            C_WAKE -> cabin.wakeBurst()
            C_WIN_OPEN -> cabin.crackWindow(true)
            C_WIN_CLOSE -> cabin.crackWindow(false)
            C_RECLINE -> if (state.gearNow() != Gear.PARK) return DiagResult(false, "안전을 위해 P단에서만 시험합니다") else cabin.reclineSeat()
            C_RESTORE -> cabin.restoreSeat()
            C_WAYPOINT -> {
                val here = location.current() ?: return DiagResult(false, "위치를 아직 받지 못했습니다")
                val stop = RestStop("diag", "옆자리 점검 경유지", GeoPoint(here.lat - 0.02, here.lon), ai.yeopjari.core.RestStopKind.SERVICE_AREA)
                navigator.addWaypoint(stop).also { if (!it && lastSdkError == null) lastSdkError = navigator.lastError ?: "경유지 변경 알림 없음(5초)" }
            }
            C_REMAIN -> {
                val km = navigator.remainingKm()
                return if (km != null) DiagResult(true, "%.1f km".format(km)) else DiagResult(false, navigator.lastError ?: "경로 안내 중이 아니거나 응답 없음")
            }
            C_HAZARD -> return DiagResult(false, "공개 Vehicle SDK 에 비상등 제어가 없어 음성으로 요청합니다")
            else -> return DiagResult(false, "알 수 없는 제어")
        }
        return DiagResult(ok, if (ok) "" else lastSdkError ?: "지원하지 않거나 범위 밖")
    }

    private companion object {
        const val C_AIR = "외기 유입(내기 순환 끄기)"
        const val C_WAKE = "깨우기: 찬 바람·통풍·열선 끄기·실내등"
        const val C_WIN_OPEN = "운전석 창문 2~3cm 열기"
        const val C_WIN_CLOSE = "운전석 창문 되돌리기"
        const val C_RECLINE = "등받이 눕히기(P단)"
        const val C_RESTORE = "등받이 되돌리기"
        const val C_WAYPOINT = "경유지 추가(2km 남쪽 점검용)"
        const val C_REMAIN = "목적지까지 남은 거리"
        const val C_HAZARD = "비상등"
    }
}
