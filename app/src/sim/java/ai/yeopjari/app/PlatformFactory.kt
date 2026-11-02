package ai.yeopjari.app

import ai.yeopjari.app.ports.CabinControl
import ai.yeopjari.app.ports.CopilotConfig
import ai.yeopjari.app.ports.DemoControls
import ai.yeopjari.app.ports.LocationSource
import ai.yeopjari.app.ports.MediaSource
import ai.yeopjari.app.ports.Navigator
import ai.yeopjari.app.ports.Platform
import ai.yeopjari.app.ports.VehicleSource
import ai.yeopjari.app.voice.AndroidMediaSource
import ai.yeopjari.core.GeoPoint
import ai.yeopjari.core.Gear
import ai.yeopjari.core.MediaState
import ai.yeopjari.core.RestStop
import ai.yeopjari.core.VehicleSnapshot
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 시연 빌드: 실제 1초 = 가상 1분으로 압축한 고속도로 주행. 약 5초 뒤(가상 5분) 동행이 시작된다.
 * 차선 변경·급제동 버튼으로 "바쁠 때 조용히" 동작을, 동승자 버튼으로 "옆자리가 차면 물러나기"를,
 * 차량 졸음 경고 버튼으로 차량 경고 연동을, 차로 흔들림 버튼으로 "확인 앞당기기"를,
 * 휴식 정차로 파워냅(15초)을 보여 준다. 목적지는 380km 앞 부산이라 출발 5초(가상 5분) 뒤 함께한다.
 * 음악·곡 넘기기·전화 버튼은 가상 음악 상태를 바꾼다. 에뮬레이터에서 실제 음악 앱을 틀어 두면 그 음악도
 * 옆자리가 말할 때 줄어들고 들을 때 멈춘다.
 */
fun createPlatform(context: Context): Platform {
    val drive = SimulatedDrive(AndroidMediaSource(context))
    return Platform(
        vehicle = drive,
        location = drive,
        navigator = object : Navigator {
            override suspend fun addWaypoint(stop: RestStop) = true
            // 달린 만큼 줄어든다. 도착하면 출발점으로 돌아가 다시 달린다(시연 중 휴게소가 계속 나오게).
            override suspend fun remainingKm(): Double = drive.remainingKm()
        },
        cabin = object : CabinControl {
            // 외기 유입: 가상 공조의 내기 순환을 끈다
            override suspend fun freshAir() = true.also { drive.recirculation = false }
            override suspend fun reclineSeat() = true
            override suspend fun restoreSeat() = true
            override suspend fun wakeBurst() = true
            override suspend fun crackWindow(open: Boolean) = true
            // 실제 Pleos SDK 처럼 비상등은 앱이 켤 수 없다: 시연에서도 운전자에게 켜 달라고 말한다
            override suspend fun hazardLights(on: Boolean) = false
        },
        media = drive,
        config = CopilotConfig(
            normalIntervalMs = 20_000,
            cautionIntervalMs = 10_000,
            napMs = 15_000,
            quietModeMs = 60_000,
            quietModeRiskyMs = 30_000,
            // 0.5초마다 표본 → 30개 = 실제 15초(가상 15분), 최근 12개 = 실제 6초
            steeringBaselineSamples = 30,
            steeringWindowSamples = 12,
            songGapWaitMs = 15_000,
            interactionGraceMs = 10_000,
            maxDeferMs = 30_000,
            // 커피 냅: 실제 5분 대신 20초 뒤 다시 묻는다
            coffeeWaitMs = 20_000,
        ),
        demo = drive,
    )
}

internal class SimulatedDrive(private val realMedia: MediaSource) : VehicleSource, LocationSource, MediaSource, DemoControls {
    private val timeScale = 60L
    private val tickMs = 500L

    @Volatile private var gear = Gear.DRIVE
    @Volatile private var position = START
    @Volatile private var turnSignalUntil = 0L
    @Volatile private var hardBrakeUntil = 0L
    @Volatile private var passenger = false
    @Volatile private var warningUntil = 0L
    @Volatile private var weaveUntil = 0L
    @Volatile private var laneAssist = false
    @Volatile private var laneInterventionUntil = 0L
    @Volatile private var handsOffUntil = 0L
    @Volatile private var cameraUntil = 0L
    /** 공조 내기 순환. 가상 30분(실제 30초) 이어지면 옆자리가 바깥 공기로 바꾼다. */
    @Volatile var recirculation = false
    @Volatile private var musicOn = false
    @Volatile private var trackIndex = 0
    @Volatile private var trackStartedAt = 0L
    @Volatile private var lastSkipAt: Long? = null
    @Volatile private var inCall = false
    private var tick = 0
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
                    steeringAngleDeg = steeringAngle(now),
                    laneAssistOn = laneAssist,
                    laneIntervention = now < laneInterventionUntil,
                    handsOffWarning = now < handsOffUntil,
                    cameraDrowsiness = now < cameraUntil,
                    recirculationOn = recirculation,
                )
            )
            tick++
            delay(tickMs)
            val dtMs = tickMs * timeScale
            virtualTimeMs += dtMs
            elapsedVirtualMs += dtMs
            // 남쪽으로 이동: 위도 1도 ≈ 111km. 목적지까지 다 달리면 출발점으로 돌아가 다시 달린다.
            advance(speed * dtMs / 3_600_000.0)
        }
    }

    @Volatile private var travelledKm = 0.0

    /** 목적지까지 남은 거리. 한 바퀴([ROUTE_KM]) 돌면 다시 처음부터. */
    fun remainingKm(): Double = (ROUTE_KM - travelledKm).coerceAtLeast(0.0)

    /**
     * [km] 만큼 남쪽으로 간다. 목적지(380km)에 닿으면 남은 거리를 다시 380km 로 되돌린다.
     * 위치는 [LOOP_KM] 마다 출발점으로 돌아간다: 경부선 쪽(경도 127.1)을 곧장 남쪽으로 가면 대전을 지나서는
     * 고속도로에서 멀어지므로, 휴게소가 있는 구간을 되풀이해 시연 중 쉼터가 계속 나오게 한다.
     */
    internal fun advance(km: Double) {
        travelledKm += km
        if (travelledKm >= ROUTE_KM) travelledKm -= ROUTE_KM
        loopKm = (loopKm + km) % LOOP_KM
        position = position.copy(lat = START.lat - loopKm / 111.0)
    }

    @Volatile private var loopKm = 0.0

    internal val currentPoint: GeoPoint get() = position

    /** 처음 2분은 시내(40km/h), 이후 고속도로(100km/h). 급제동 중에는 60km/h. */
    private fun currentSpeed(now: Long): Float = when {
        gear == Gear.PARK -> 0f
        now < hardBrakeUntil -> 60f
        elapsedVirtualMs < 2 * 60_000 -> 40f
        else -> 100f
    }

    /** 평소엔 아주 작은 떨림, 흔들림 버튼을 누르면 10초 동안 좌우로 흔들렸다 바로잡기를 반복. */
    private fun steeringAngle(now: Long): Float = when {
        now < weaveUntil -> if (tick % 2 == 0) 6f else -6f
        else -> (kotlin.math.sin(tick * 0.3) * 1.0).toFloat()
    }

    override suspend fun current(): GeoPoint = position

    /** 가상 주행은 남쪽(부산 쪽)으로 달린다. */
    override suspend fun headingDeg(): Double = 180.0

    /** 가상 플레이리스트: 곡마다 실제 40초. 실제 음악 앱이 재생 중이거나 통화 중이면 그것도 함께 본다. */
    override fun state(nowMs: Long): MediaState {
        val real = realMedia.state(nowMs)
        if (!musicOn) return real.copy(inCall = real.inCall || inCall)
        val played = nowMs - trackStartedAt
        val index = trackIndex + (played / TRACK_MS).toInt()
        return MediaState(
            playing = true,
            title = PLAYLIST[index % PLAYLIST.size],
            inCall = real.inCall || inCall,
            trackRemainingMs = TRACK_MS - played % TRACK_MS,
            lastInteractionMs = lastSkipAt,
        )
    }

    override fun toggleMusic() {
        musicOn = !musicOn
        trackStartedAt = SystemClock.elapsedRealtime()
    }

    override fun skipTrack() {
        if (!musicOn) return
        val now = SystemClock.elapsedRealtime()
        trackIndex += ((now - trackStartedAt) / TRACK_MS).toInt() + 1
        trackStartedAt = now
        lastSkipAt = now
    }

    override fun togglePhoneCall() {
        inCall = !inCall
    }

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

    override fun weave() {
        weaveUntil = SystemClock.elapsedRealtime() + 10_000
    }

    override fun toggleLaneAssist() {
        laneAssist = !laneAssist
    }

    /** 차가 차로 이탈을 막으려고 개입한 순간(비상 차로 유지·차로 경고). 보조가 꺼져 있어도 비상 차로 유지는 작동한다. */
    override fun laneIntervention() {
        laneInterventionUntil = SystemClock.elapsedRealtime() + 2_000
    }

    /** 차로 유지 보조 중 운전자가 핸들에서 손을 뗀 순간(차의 핸들 파지 경고). */
    override fun handsOffWarning() {
        handsOffUntil = SystemClock.elapsedRealtime() + 3_000
    }

    /** 실내 카메라(DMS) 졸음 경고. */
    override fun cameraDrowsiness() {
        cameraUntil = SystemClock.elapsedRealtime() + 2_000
    }

    override fun toggleRecirculation() {
        recirculation = !recirculation
    }

    override fun parkForRest() {
        gear = Gear.PARK
    }

    override fun resumeDriving() {
        gear = Gear.DRIVE
    }

    companion object {
        /** 서울 쪽 출발점과 부산까지 거리(가상). */
        val START = GeoPoint(37.40, 127.10)
        const val ROUTE_KM = 380.0
        /** 위치를 되풀이하는 구간(출발점 ~ 대전 남쪽). */
        const val LOOP_KM = 160.0
        private const val TRACK_MS = 40_000L
        private val PLAYLIST = listOf("밤양갱", "여행", "고속도로 로망스", "사건의 지평선")
    }
}
