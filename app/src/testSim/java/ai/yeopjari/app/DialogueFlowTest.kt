package ai.yeopjari.app

import ai.yeopjari.app.ports.CabinControl
import ai.yeopjari.app.ports.CopilotConfig
import ai.yeopjari.app.ports.DemoControls
import ai.yeopjari.app.ports.FamilyNotifier
import ai.yeopjari.app.ports.ListenResult
import ai.yeopjari.app.ports.LocationSource
import ai.yeopjari.app.ports.MediaSource
import ai.yeopjari.app.ports.Navigator
import ai.yeopjari.app.ports.Platform
import ai.yeopjari.app.ports.VehicleSource
import ai.yeopjari.app.ports.VoiceIO
import ai.yeopjari.core.AlertStage
import ai.yeopjari.core.CoffeeNap
import ai.yeopjari.core.AudioMode
import ai.yeopjari.core.GeoPoint
import ai.yeopjari.core.Gear
import ai.yeopjari.core.MediaState
import ai.yeopjari.core.Persona
import ai.yeopjari.core.RestStop
import ai.yeopjari.core.VehicleSnapshot
import android.Manifest
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * 가짜 목소리(대본대로 빠르게·느리게·대답 없이)와 가짜 차량으로 대화 흐름 전체를 돌린다.
 * 실제 TTS·음성 인식은 없지만, 대답에 따라 정상 → 주의 → 위험 → 깨우기 → 가족 연락 → 안심 문자까지
 * 앱이 정말로 단계를 넘어가는지, 제어가 거절되면 음성으로 대신하는지를 확인한다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DialogueFlowTest {
    /** 운전자 대본: 지금 대답하는 방식. */
    private enum class Reply { FAST, SLOW, SILENT }

    private class FakeVoice : VoiceIO {
        var reply = Reply.FAST
        /** 다음 대답들을 이 문장으로(빠르게) 한다. 비면 [reply] 대로. */
        val scripted = ArrayDeque<String>()
        val spoken = mutableListOf<String>()
        override var canListen = true
        override fun holdMedia(mode: AudioMode) = Unit
        override fun releaseMedia() = Unit
        override suspend fun speak(text: String) {
            spoken += text
            delay(300)
        }
        /** 방금 들은 말에 이 낱말이 있으면 그 대답을(빠르게) 한다. */
        val answers = mutableMapOf<String, String>()
        override suspend fun listen(timeoutMs: Long): ListenResult = (answers.entries.firstOrNull { (k, _) -> spoken.lastOrNull()?.contains(k) == true }?.value ?: scripted.removeFirstOrNull())?.let { delay(900); ListenResult.Heard(it, 900) } ?: when (reply) {
            Reply.FAST -> { delay(900); ListenResult.Heard("응 좋아", 900) }
            Reply.SLOW -> { delay(3_500); ListenResult.Heard("응 좋아", 3_500) }
            Reply.SILENT -> { delay(timeoutMs); ListenResult.Silent }
        }
        override fun release() = Unit
    }

    private class FakeFamily : FamilyNotifier {
        val sent = mutableListOf<String>()
        override var phone: String? = "01012345678"
        override fun setPhone(input: String): String? = input.also { phone = it }
        override suspend fun send(text: String): Boolean { sent += text; return true }
    }

    /** 실내 제어를 모두 받아 주거나([allow]) 모두 거절하는 차. 부른 제어를 기록한다. */
    private class FakeCabin(private val allow: Boolean, private val onFreshAir: () -> Unit) : CabinControl {
        val calls = mutableListOf<String>()
        override suspend fun freshAir() = allow.also { calls += "freshAir"; if (it) onFreshAir() }
        override suspend fun reclineSeat() = allow.also { calls += "recline" }
        override suspend fun restoreSeat() = allow.also { calls += "restore" }
        override suspend fun wakeBurst() = allow.also { calls += "wakeBurst" }
        override suspend fun crackWindow(open: Boolean) = allow.also { calls += "window:$open" }
        // 공개 Pleos SDK 처럼 비상등은 켤 수 없다
        override suspend fun hazardLights(on: Boolean) = false.also { calls += "hazard:$on" }
    }

    /** 1초마다 가상 1분씩 흐르는 시속 100km 주행. [gear]·[warning] 은 테스트가 바꾼다. */
    private class FakeCar : VehicleSource, LocationSource, Navigator, MediaSource {
        @Volatile var gear = Gear.DRIVE
        @Volatile var warning = false
        @Volatile var recirculation = false
        @Volatile var handsOff = false
        @Volatile var camera = false
        val waypoints = mutableListOf<String>()
        private var t = 1_800_000_000_000L
        override val snapshots: Flow<VehicleSnapshot> = flow {
            while (true) {
                emit(VehicleSnapshot(t, if (gear == Gear.PARK) 0f else 100f, gear, drowsinessWarning = warning, recirculationOn = recirculation, handsOffWarning = handsOff, cameraDrowsiness = camera))
                t += 60_000
                delay(1_000)
            }
        }
        override suspend fun current() = GeoPoint(37.25, 127.10)
        override suspend fun headingDeg() = 180.0
        override suspend fun addWaypoint(stop: RestStop) = true.also { waypoints += stop.name }
        override suspend fun remainingKm() = 380.0
        override fun state(nowMs: Long) = MediaState()
    }

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val voice = FakeVoice()
    private val family = FakeFamily()
    private val car = FakeCar()
    private lateinit var cabin: FakeCabin

    /** 시연 버튼 중 정차·출발만 차에 전한다. */
    private inner class FakeDemo : DemoControls {
        override fun laneChange() = Unit
        override fun hardBrake() = Unit
        override fun togglePassenger() = Unit
        override fun vehicleDrowsinessWarning() = Unit
        override fun weave() = Unit
        override fun toggleLaneAssist() = Unit
        override fun laneIntervention() = Unit
        override fun parkForRest() { car.gear = Gear.PARK }
        override fun resumeDriving() { car.gear = Gear.DRIVE }
        override fun toggleMusic() = Unit
        override fun skipTrack() = Unit
        override fun togglePhoneCall() = Unit
    }

    private fun platform(allow: Boolean, restPlanning: Boolean = false, demo: Boolean = false): Platform {
        cabin = FakeCabin(allow) { car.recirculation = false }
        return Platform(
            demo = if (demo) FakeDemo() else null,
            vehicle = car, location = car, navigator = car, cabin = cabin, media = car,
            config = CopilotConfig(
                normalIntervalMs = 20_000, cautionIntervalMs = 10_000, napMs = 15_000,
                quietModeMs = 60_000, quietModeRiskyMs = 30_000,
                steeringBaselineSamples = 30, steeringWindowSamples = 12,
                songGapWaitMs = 15_000, interactionGraceMs = 10_000, maxDeferMs = 30_000,
                restPlanning = restPlanning, coffeeWaitMs = 15_000, sampleRestStops = true,
            ),
        )
    }

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.SEND_SMS)
        app.getSharedPreferences("yeopjari", 0).edit().clear().commit()
        TestHooks.voice = { voice }
        TestHooks.family = { family }
    }

    @After fun tearDown() {
        TestHooks.platform = null
        TestHooks.voice = null
        TestHooks.family = null
    }

    private fun start(allow: Boolean, restPlanning: Boolean = false, demo: Boolean = false): CopilotViewModel {
        val p = platform(allow, restPlanning, demo)
        TestHooks.platform = { p }
        return CopilotViewModel(app).also { it.setPersona(Persona.entries.first()) }
    }

    /** 메인 루퍼(코루틴 지연)를 [seconds] 초 흘린다. */
    private fun run(seconds: Long) {
        repeat((seconds * 4).toInt()) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)) }
    }

    /** [check] 가 참이 될 때까지 최대 [maxSeconds] 초 흘린다. */
    private fun runUntil(maxSeconds: Long, check: () -> Boolean): Boolean {
        repeat((maxSeconds * 2).toInt()) {
            if (check()) return true
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        }
        return check()
    }

    private fun said(part: String) = voice.spoken.any { part in it }

    @Test
    fun `대답이 느려지고 끊기면 주의 → 위험 → 깨우기 → 가족 연락, 다시 대답하면 안심 문자`() {
        val vm = start(allow = true)

        // 1) 장거리 목적지라 출발 직후 함께하고, 빠른 대답 세 번으로 평소 속도를 잡는다
        assertTrue("동행 시작", runUntil(30) { vm.ui.value.accompanying })
        assertTrue("첫인사", runUntil(30) { said("옆자리가 함께할게요") })
        assertTrue("평소 속도 보정", runUntil(240) { vm.ui.value.baselineSec in 0.8..1.0 && voice.spoken.size >= 6 })
        assertEquals(AlertStage.NORMAL, vm.ui.value.stage)

        // 2) 대답이 평소의 두 배 넘게 느려지면 주의: 묻지 않고 환기·창문
        voice.reply = Reply.SLOW
        assertTrue("주의 단계", runUntil(300) { vm.ui.value.stage == AlertStage.CAUTION })
        assertTrue("환기", runUntil(30) { "freshAir" in cabin.calls && "window:true" in cabin.calls })
        assertTrue("환기 안내", runUntil(30) { said("창문 조금 열고, 시원한 바람 틀어 드릴게요") })
        // 주의 단계에서 대답을 들어도 창문을 바로 닫지 않는다(환기 중).
        run(20)
        if (vm.ui.value.stage == AlertStage.CAUTION) assertFalse("주의 단계 중 창문 유지", "window:false" in cabin.calls)

        // 3) 대답이 끊기면: 한 번 더 묻고 → 위험 → 깨우기(알람·찬 바람·열혈 교관)
        voice.reply = Reply.SILENT
        assertTrue("다시 묻기", runUntil(300) { said("제 말 들리세요?") })
        assertTrue("위험 단계", runUntil(300) { vm.ui.value.stage == AlertStage.DANGER })
        assertTrue("깨우기", runUntil(120) { "wakeBurst" in cabin.calls && said(Persona.DRILL.wakeLine) })

        // 4) 그래도 대답이 없으면: 비상등은 켤 수 없으니 켜 달라고 하고, 가족에게 한 번 연락
        assertTrue("비상등 요청", runUntil(300) { said("비상등을 켜고, 가장 가까운 안전한 곳에") })
        assertFalse("켰다고 말하지 않음", said("비상등을 켰어요"))
        assertTrue("가족 위급 문자", runUntil(60) { family.sent.any { "깨워도 대답이 없어요" in it } })
        assertTrue("쉼터 이름까지만", family.sent.first { "깨워도" in it }.contains("샘플 졸음쉼터 A"))
        assertTrue(WAKE_FAMILY in vm.ui.value.wakeActions)
        val alerts = family.sent.count { "깨워도" in it }
        val asks = voice.spoken.count { "비상등을 켜고" in it }
        run(120)
        assertEquals("위급 문자는 한 번만", alerts, family.sent.count { "깨워도" in it })
        // 2단계는 계속 무응답이어도 3분에 한 번만 다시 울린다(10초 확인 간격마다 울리지 않는다).
        assertTrue("2단계 반복 제한", voice.spoken.count { "비상등을 켜고" in it } - asks <= 1)

        // 5) 다시 대답하면 깨우기를 멈추고 창문을 닫고, 가족에게 안심 문자
        voice.reply = Reply.FAST
        assertTrue("안심 문자", runUntil(300) { family.sent.any { "다시 대답했어요" in it } })
        assertTrue("창문 닫기", "window:false" in cabin.calls)
        assertTrue(vm.ui.value.wakeActions.isEmpty())
    }

    @Test
    fun `차량 졸음 경고가 오면 바로 위험으로 올려 쉼터를 경유지로 넣는다`() {
        val vm = start(allow = true)
        assertTrue(runUntil(30) { vm.ui.value.accompanying })
        run(10)
        car.warning = true
        assertTrue("위험", runUntil(10) { vm.ui.value.stage == AlertStage.DANGER })
        assertTrue("차량 경고 안내", runUntil(60) { said("차량에서도 졸음 신호가 감지됐어요") })
        assertTrue("쉼터 제안", runUntil(60) { said("샘플 졸음쉼터 A") && said("들를까요?") })
        assertTrue("경유지", runUntil(60) { "샘플 졸음쉼터 A" in car.waypoints })
    }

    @Test
    fun `실내 카메라 졸음 경고도 바로 위험으로 올린다`() {
        val vm = start(allow = true)
        assertTrue(runUntil(30) { vm.ui.value.accompanying })
        run(10)
        car.camera = true
        assertTrue("위험", runUntil(10) { vm.ui.value.stage == AlertStage.DANGER })
        assertTrue("기록", vm.ui.value.log.any { "실내 카메라 졸음 경고" in it })
        assertTrue("쉼터 제안", runUntil(60) { said("들를까요?") })
    }

    @Test
    fun `핸들 놓음 경고가 오면 다음 확인을 기다리지 않고 바로 묻는다`() {
        val vm = start(allow = true)
        assertTrue(runUntil(30) { vm.ui.value.accompanying })
        run(25)
        val before = voice.spoken.size
        car.handsOff = true
        assertTrue("기록", runUntil(5) { vm.ui.value.log.any { "핸들 놓음 경고" in it } })
        // 확인 간격(20초)보다 훨씬 빨리 말을 건다
        assertTrue("바로 확인", runUntil(8) { voice.spoken.size > before })
    }

    @Test
    fun `차가 제어를 거절하면 음성으로 대신한다`() {
        val vm = start(allow = false)
        assertTrue(runUntil(240) { vm.ui.value.baselineSec in 0.8..1.0 && voice.spoken.size >= 6 })
        voice.reply = Reply.SLOW
        assertTrue("주의", runUntil(300) { vm.ui.value.stage == AlertStage.CAUTION })
        assertTrue("창문을 열어 달라고 부탁", runUntil(30) { said("창문을 살짝 열어 주실래요?") })
        assertFalse("열었다고 말하지 않음", said("창문 조금 열고") || said("창문을 조금 열게요"))
        assertFalse(WAKE_WINDOW in vm.ui.value.wakeActions)
    }

    @Test
    fun `출발할 때 잠을 묻고, 약속한 휴식이 다가오면 쉼터를 권하고, 커피 냅으로 쉰다`() {
        voice.scripted += "네 시간밖에 못 잤어"
        val vm = start(allow = true, restPlanning = true)
        assertTrue("잠 질문", runUntil(30) { said("어젯밤엔 몇 시간쯤 주무셨어요?") })
        assertTrue("짧은 잠 → 휴식 약속", runUntil(30) { said("잠이 많이 짧았네요.") && said("쯤 뒤에 한 번 쉬어 가요") })
        val plan = vm.ui.value.let { s -> s.restPlanMin?.let { it - s.drivingMinutes } }
        assertTrue("지금부터 60분 이하로 앞당김", plan != null && plan <= 60)
        // 가상 1분 = 실제 1초: 약속 10분 전이 되면 묻는다
        assertTrue("약속 알림", runUntil(90) { said("약속한 휴식 시간이 다가와요.") })
        assertFalse("쉼터 안내는 휴식 약속에서 나왔다", said("쉴 곳이 없어요"))
        assertTrue("경유지", runUntil(60) { "샘플 졸음쉼터 A" in car.waypoints })

        // 쉼터에 서면 파워냅 → 커피 냅 → 버튼으로 바로 시작
        car.gear = Gear.PARK
        assertTrue("파워냅 제안", runUntil(60) { said("15분만 눈 붙이고 가실래요?") })
        // 샘플 쉼터에는 카페 자료가 없다: "커피 한 잔"을 권하지 않고 가진 커피가 있는지 묻는다.
        assertTrue("가진 커피부터", runUntil(30) { said(CoffeeNap.ASK_CARRY) })
        assertTrue("커피 냅 제안", runUntil(30) { said(CoffeeNap.WITH_OWN) })
        assertFalse("카페 없는 곳에서 커피 한 잔 권하지 않음", said("커피 한 잔 드시고"))
        assertTrue("커피 기다림", runUntil(30) { vm.ui.value.coffeeWaiting })
        assertFalse("아직 눕히지 않음", "recline" in cabin.calls)
        vm.startNapNow()
        assertTrue("좌석 눕힘", runUntil(30) { "recline" in cabin.calls })
        assertFalse(vm.ui.value.coffeeWaiting)
    }

    @Test
    fun `목소리를 한 번도 못 들었으면 무응답을 졸음으로 보지 않고, 화면 대답으로 이어 간다`() {
        voice.reply = Reply.SILENT
        val vm = start(allow = true)
        assertTrue("동행 시작", runUntil(30) { vm.ui.value.accompanying })
        assertTrue("마이크 안내", runUntil(240) { vm.ui.value.voiceIssues.any { it.startsWith(SILENT_HINT) } })
        run(120)
        assertEquals("마이크 문제일 수 있어 위험으로 올리지 않음", AlertStage.NORMAL, vm.ui.value.stage)
        assertFalse("깨우기 없음", "wakeBurst" in cabin.calls)
        // 화면의 빠른 대답은 목소리와 같은 길로 들어간다
        assertTrue("듣는 중", runUntil(120) { vm.ui.value.listening })
        vm.quickReply("응, 괜찮아")
        assertTrue("대답으로 기록", runUntil(10) { vm.ui.value.chat.any { it.fromDriver && it.text == "응, 괜찮아" } })
        // 목소리를 알아들으면 안내가 사라진다
        voice.reply = Reply.FAST
        assertTrue("안내 사라짐", runUntil(240) { vm.ui.value.voiceIssues.none { it.startsWith(SILENT_HINT) } })
    }

    @Test
    fun `출발할 때 커피 좋아한다고 하면 쉼터에서 그 기억으로 커피 냅을 권한다`() {
        voice.scripted += "다섯 시간쯤? 커피 마시면 괜찮아"
        val vm = start(allow = true, restPlanning = true)
        assertTrue("잠 질문", runUntil(30) { said("어젯밤엔 몇 시간쯤 주무셨어요?") })
        assertTrue("기억", runUntil(10) { vm.ui.value.remembered.contains("커피") })
        assertTrue("경유지", runUntil(120) { "샘플 졸음쉼터 A" in car.waypoints })
        car.gear = Gear.PARK
        assertTrue("파워냅 제안", runUntil(60) { said("15분만 눈 붙이고 가실래요?") })
        assertTrue("기억으로 권함", runUntil(30) { said("아까 커피 좋아하신다고 하셨죠?") })
        assertFalse("묻지 않음", said(CoffeeNap.ASK))
        assertFalse("자료에 없는 가게 이름은 말하지 않음", said("여기 "))
    }

    @Test
    fun `내기 순환이 30분 넘게 이어지면 바깥 공기로 바꾼다`() {
        val vm = start(allow = true)
        assertTrue(runUntil(30) { vm.ui.value.accompanying })
        car.recirculation = true
        assertTrue("외기 전환", runUntil(60) { said("내기 순환이") && said("바깥 공기로 바꿀게요") })
        assertTrue("freshAir" in cabin.calls)
        assertFalse("다시 외기", car.recirculation)
        assertTrue(vm.ui.value.staleAirFixed)
    }

    @Test
    fun `파워냅 중 출발하면 가족 문자가 없어도 낮잠을 멈추고, 달리는 중엔 알람도 좌석도 움직이지 않는다`() {
        family.phone = null                                     // 가족 번호 없음: 예전에는 이때 낮잠이 취소되지 않았다
        val vm = start(allow = true)
        assertTrue(runUntil(30) { vm.ui.value.accompanying })
        run(10)
        car.warning = true
        assertTrue("경유지", runUntil(90) { "샘플 졸음쉼터 A" in car.waypoints })
        car.warning = false
        car.gear = Gear.PARK
        assertTrue("파워냅 제안", runUntil(60) { said("15분만 눈 붙이고 가실래요?") })
        assertTrue("커피 기다림", runUntil(60) { vm.ui.value.coffeeWaiting })
        vm.startNapNow()
        assertTrue("낮잠 중", runUntil(30) { vm.ui.value.napping && "recline" in cabin.calls })
        car.gear = Gear.DRIVE
        assertTrue("출발하면 낮잠 끝", runUntil(10) { !vm.ui.value.napping })
        run(30)
        assertFalse("달리는 중 알람 없음", said("일어날 시간이에요"))
        assertFalse("달리는 중 좌석 안 움직임", "restore" in cabin.calls)
        car.gear = Gear.PARK
        assertTrue("다음 정차 때 좌석 원위치", runUntil(10) { "restore" in cabin.calls })
    }

    @Test
    fun `정상 단계에서 휴게소 제안에 한 번 대답이 없으면 다시 묻고, 그래도 없으면 깨우지 않는다`() {
        voice.scripted += "피곤해"
        voice.reply = Reply.SILENT
        val vm = start(allow = true)
        assertTrue(runUntil(30) { vm.ui.value.accompanying })
        assertTrue("휴게소 제안", runUntil(120) { said("들를까요?") })
        assertTrue("다시 묻기", runUntil(60) { said("'응'이나 '아니'로 말씀해 주세요") })
        assertTrue("그냥 감", runUntil(60) { said("일단 그냥 갈게요") })
        assertEquals(AlertStage.NORMAL, vm.ui.value.stage)
        assertFalse("깨우지 않음", "wakeBurst" in cabin.calls)
        assertTrue("다음 정차 쉼터로 기억하지 않음", car.waypoints.isEmpty())
    }

    @Test
    fun `마이크를 쓸 수 없으면 한 번 소리로 알리고 화면 대답으로 확인을 이어 간다`() {
        voice.canListen = false
        val vm = start(allow = true)
        assertTrue(runUntil(30) { vm.ui.value.accompanying })
        assertTrue("소리로 알림", runUntil(120) { said("마이크를 쓸 수 없어서") })
        assertTrue("듣는 중(화면 대답)", runUntil(60) { vm.ui.value.listening })
        vm.quickReply("응")
        assertTrue("대답으로 기록", runUntil(10) { vm.ui.value.chat.any { it.fromDriver && it.text == "응" } })
        run(120)
        assertEquals("알림은 한 번만", 1, voice.spoken.count { "마이크를 쓸 수 없어서" in it })
        assertEquals("무응답을 졸음으로 보지 않음", AlertStage.NORMAL, vm.ui.value.stage)
        assertFalse("wakeBurst" in cabin.calls)
    }

    @Test
    fun `시연 빌드에서 화면으로 대답하던 운전자가 대답하지 않으면 위험까지 올라가 깨운다`() {
        voice.canListen = false
        val vm = start(allow = true, demo = true)
        assertTrue(runUntil(30) { vm.ui.value.accompanying })
        assertTrue("듣는 중(화면 대답)", runUntil(120) { vm.ui.value.listening })
        vm.quickReply("응, 괜찮아")
        assertTrue("대답으로 기록", runUntil(10) { vm.ui.value.chat.any { it.fromDriver && it.text == "응, 괜찮아" } })
        // 이제 화면 대답도 하지 않는다: 다시 묻고, 그래도 없으면 무응답으로 쌓인다
        assertTrue("다시 물음", runUntil(120) { said("제 말 들리세요?") })
        assertTrue("위험", runUntil(240) { vm.ui.value.stage == AlertStage.DANGER })
        assertTrue("깨우기", runUntil(60) { "wakeBurst" in cabin.calls })
    }

    @Test
    fun `약속한 휴식을 미루면 한동안 확인을 더 자주 한다`() {
        voice.scripted += "네 시간밖에 못 잤어"
        voice.answers["들를까요?"] = "아직 괜찮아, 조금 더 갈게"
        val vm = start(allow = true, restPlanning = true)
        assertTrue("약속 알림", runUntil(120) { said("약속한 휴식 시간이 다가와요.") })
        assertTrue("미룸 → 더 자주", runUntil(30) { said("그럼 조금 더 자주 여쭤볼게요.") })
        assertTrue("경유지 넣지 않음", car.waypoints.isEmpty())
        assertEquals(AlertStage.NORMAL, vm.ui.value.stage)
    }

    @Test
    fun `가족 문자는 통신사 전송 결과가 없으면 보냈다고 하지 않는다`() {
        val prefs = app.getSharedPreferences("sms_test", 0)
        val sms = ai.yeopjari.app.notify.SmsFamilyNotifier(app, prefs, sentTimeoutMs = 3_000)
        var result: Boolean? = null
        sms.setPhone("010-1234-5678")
        sendAsync(sms) { result = it }
        runUntil(10) { result != null }
        assertEquals("전송 결과(RESULT_OK)가 안 오면 false", false, result)
        shadowOf(app).denyPermissions(Manifest.permission.SEND_SMS)
        result = null
        sendAsync(sms) { result = it }
        runUntil(5) { result != null }
        assertEquals("권한 없으면 false", false, result)
    }

    // 한글 이름 테스트 안에서 suspend 람다를 만들면 클래스 파일 이름에 한글이 들어가 일부 환경에서 깨지므로 따로 둔다.
    private fun sendAsync(sms: FamilyNotifier, done: (Boolean) -> Unit) {
        CoroutineScope(Dispatchers.Main).launch { done(sms.send("test")) }
    }

    @Test
    fun `시연의 휴식 정차는 휴식을 제안한 적이 없어도 파워냅을 권한다`() {
        val vm = start(allow = true, demo = true)
        assertTrue(runUntil(30) { vm.ui.value.accompanying })
        run(5)
        assertEquals(AlertStage.NORMAL, vm.ui.value.stage)
        vm.parkForRest()
        assertTrue("파워냅 제안", runUntil(60) { said("15분만 눈 붙이고 가실래요?") })
    }
}
