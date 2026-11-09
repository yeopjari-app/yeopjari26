package ai.yeopjari.app.ui

import ai.yeopjari.app.ChatLine
import ai.yeopjari.app.DiagState
import ai.yeopjari.app.R
import ai.yeopjari.app.RestOption
import ai.yeopjari.app.UiState
import ai.yeopjari.core.FatigueForecast
import ai.yeopjari.app.WAKE_COLD_AIR
import ai.yeopjari.app.WAKE_FAMILY
import ai.yeopjari.app.WAKE_SEAT
import ai.yeopjari.app.WAKE_VOICE
import ai.yeopjari.app.WAKE_WINDOW
import ai.yeopjari.core.AlertStage
import ai.yeopjari.core.AudioMode
import ai.yeopjari.core.Persona
import ai.yeopjari.core.spokenDuration
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import kotlin.math.min
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.exclude
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import ai.yeopjari.core.FamilyNotice
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** 화면에서 할 수 있는 일. 시연 조작은 가상 주행 빌드에서만 효과가 있다. */
interface CopilotActions {
    fun startNow()
    fun requestStory()
    fun laneChange()
    fun hardBrake()
    fun togglePassenger()
    fun vehicleDrowsinessWarning()
    fun weave()
    fun toggleLaneAssist() = Unit
    fun laneIntervention() = Unit
    fun handsOffWarning() = Unit
    fun cameraDrowsiness() = Unit
    fun parkForRest()
    fun resumeDriving()
    fun toggleMusic()
    fun skipTrack()
    fun togglePhoneCall()
    fun setFamilyPhone(input: String)
    fun setPersona(persona: Persona)
    fun openDiagnostics() = Unit
    fun closeDiagnostics() = Unit
    fun testControl(control: String) = Unit
    fun startNapNow() = Unit
    fun toggleRecirculation() = Unit
    fun quickReply(text: String) = Unit
    /** 질문을 기다리지 않고 운전자가 먼저 말한다(화면의 "말하기"). */
    fun talk() = Unit
    fun refreshVoiceStatus() = Unit
}

/** 짙은 남색 바탕 위에 유리 같은 타일을 얹는 화면의 색. 단계 색은 [Tone]. */
private object P {
    val bgTop = Color(0xFF0E1420)
    val bgBottom = Color(0xFF090D14)
    val glassTop = Color(0xB81A2230)
    val glassBottom = Color(0xB80E131C)
    val stroke = Color(0x14FFFFFF)
    val chip = Color(0x0DFFFFFF)
    val panelHi = Color(0xFF232B38)
    val text = Color(0xFFF2F5F9)
    val sub = Color(0xFF9AA6B6)
    val blue = Color(0xFF3D8BFF)
    val blueDeep = Color(0xFF2D74EA)
    val cyan = Color(0xFF38E1FF)
    val violet = Color(0xFF8C7CFF)
    val ok = Color(0xFF2FE39A)
    val caution = Color(0xFFFFB020)
    val danger = Color(0xFFFF5A3C)
    val bubble = Color(0xFF252E3B)
    val option = Color(0xFF1C3550)
    val cold = Color(0xFF5FD4FF)
}

/** 머리글 글자표(워드마크): Pretendard SemiBold(SIL OFL 1.1, assets/licenses/Pretendard-OFL.txt). 워드마크 글자만 담은 부분 글꼴. */
private val Wordmark = FontFamily(Font(R.font.pretendard_wordmark, FontWeight.SemiBold))

private val WantedSans = FontFamily(
    Font(R.font.wanted_sans_regular, FontWeight.Normal),
    Font(R.font.wanted_sans_medium, FontWeight.Medium),
    Font(R.font.wanted_sans_semibold, FontWeight.SemiBold),
    Font(R.font.wanted_sans_bold, FontWeight.Bold),
    Font(R.font.wanted_sans_extrabold, FontWeight.ExtraBold),
)

/** 화면이 보여 주는 단계. [color] 는 점·테두리 빛, [orb] 는 오브의 세 색(밝은 색 → 중간 → 깊은 색). */
private enum class Tone(val color: Color, val orb: List<Color>) {
    GRAY(Color(0xFF6B7682), listOf(Color(0xFF38E1FF), Color(0xFF3D8BFF), Color(0xFF8C7CFF))),
    GREEN(Color(0xFF2FE39A), listOf(Color(0xFF38E1FF), Color(0xFF3D8BFF), Color(0xFF8C7CFF))),
    AMBER(Color(0xFFFFB020), listOf(Color(0xFFFFE08A), Color(0xFFFFB020), Color(0xFFFF7A3C))),
    RED(Color(0xFFFF5A3C), listOf(Color(0xFFFF9A80), Color(0xFFFF5A3C), Color(0xFFFF2D55))),
    INDIGO(Color(0xFF8C7CFF), listOf(Color(0xFFC9C0FF), Color(0xFF8C7CFF), Color(0xFF5A4CFF))),
}

private fun toneOf(state: UiState): Tone = when {
    state.napping -> Tone.INDIGO
    !state.accompanying -> Tone.GRAY
    state.stage == AlertStage.CAUTION -> Tone.AMBER
    state.stage == AlertStage.DANGER -> Tone.RED
    else -> Tone.GREEN
}

private const val TAU = 6.2831855f
private val WindowShape = RoundedCornerShape(24.dp)
private val TileShape = RoundedCornerShape(16.dp)
private val GlassBrush = Brush.verticalGradient(listOf(P.glassTop, P.glassBottom))

/**
 * 애니메이션용 시계(초). 매 프레임 갱신하므로 값은 Canvas 그리기 단계에서만 읽어 재구성을 피한다.
 * Float 정밀도 때문에 1000초마다 되감는다(그때 오브가 한 번 살짝 튄다).
 */
@Composable
private fun rememberClock(): State<Float> = produceState(0f) {
    val start = withFrameNanos { it }
    while (true) withFrameNanos { value = ((it - start) / 1_000_000_000.0 % 1000.0).toFloat() }
}

/**
 * 옆자리 앱 창. 시연 영상의 앱 화면과 같은 배치다.
 * - 가로로 넓은 창(차량 화면·태블릿·가로 에뮬레이터): 머리글 아래 세 칸 — 왼쪽 AI 오브와 자막, 가운데 센서 타일 3개,
 *   오른쪽 졸음 예보와 아래 영역(대화 · 깨우기 동작 · 휴식 장소 · 오늘의 운행) — 그리고 아래 동작 줄.
 *   [WIDE_W]×[WIDE_H] 기준으로 그린 뒤 창에 꽉 차게 키운다(밀도를 바꿔서). 그래서 큰 화면에서도 작아 보이지 않는다.
 * - 세로 휴대전화: 한 줄로 쌓되 폭 [NARROW_W] 기준으로 키운다.
 * 듣는 동안은 빠른 대답과 입력칸이 함께 뜬다(목소리 대신이 아니라, 마이크가 안 될 때도 대화를 이어 가도록).
 * 목소리 대화에 문제가 있으면(마이크 권한·음성 인식·한국어 TTS) 위에 고칠 방법을 띄운다.
 */
@Composable
fun CopilotScreen(state: UiState, actions: CopilotActions) {
    // 화면을 돌려도 열린 창·입력 중인 글이 남는다. 주행이 시작되면(동행 중 + 정차 아님) 창을 닫는다.
    var editingPhone by rememberSaveable { mutableStateOf(false) }
    var pickingVoice by rememberSaveable { mutableStateOf(false) }
    val typing = rememberSaveable { mutableStateOf("") }
    val driving = state.accompanying && !state.parked
    LaunchedEffect(driving, state.diag != null) {
        if (driving) {
            editingPhone = false
            pickingVoice = false
            if (state.diag != null) actions.closeDiagnostics()   // 점검 화면도 주행이 시작되면 닫는다
        }
    }
    val clock = rememberClock()
    val tone = toneOf(state)
    // 배경은 화면 끝까지 칠하고, 내용은 상태 표시줄·내비게이션 바·카메라 홈을 피해 그린다
    // (Android 15 부터 targetSdk 35 앱은 화면 끝까지 그려지므로, 이게 없으면 머리글과 아래 버튼 줄이 가려진다).
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(P.bgTop, P.bgBottom)))
            // 키보드(IME)는 빼고 잡는다: 키보드가 올라올 때마다 배율·배치(넓은/좁은)가 바뀌어 입력칸이 사라지지 않게.
            .windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime))
    ) {
        val (wide, scale) = layoutScale(maxWidth.value, maxHeight.value)
        val base = LocalDensity.current
        // 화면을 줄여 그리면(배율 1 미만) 48dp 도 실제로는 작아진다. 누르는 영역은 실제 48dp 가 되도록 그만큼 키운다
        // (Material 최소 크기와, 작은 버튼의 누르는 영역을 넓혀 주는 최소 터치 크기 둘 다).
        val touch = touchTargetDp(scale).dp
        val baseConfig = LocalViewConfiguration.current
        val config = remember(baseConfig, touch) {
            object : ViewConfiguration by baseConfig {
                override val minimumTouchTargetSize = DpSize(touch, touch)
            }
        }
        CompositionLocalProvider(
            LocalDensity provides Density(base.density * scale, base.fontScale),
            LocalMinimumInteractiveComponentSize provides touch,
            LocalViewConfiguration provides config,
        ) {
            // 한국어는 낱말(띄어쓰기) 단위로 줄을 바꾼다: "그 / 려요"처럼 한 글자만 다음 줄로 넘어가지 않게(Android 13 이상).
            ProvideTextStyle(TextStyle(fontFamily = WantedSans, color = P.text, lineBreak = KoreanLineBreak)) {
                // 키보드가 올라오면 내용만 그 위로 올린다(배율은 그대로).
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime).clip(WindowShape)) {
                    if (wide) WideLayout(state, actions, tone, clock, typing, onFamily = { editingPhone = true }, onVoice = { pickingVoice = true })
                    else NarrowLayout(state, actions, tone, clock, typing, onFamily = { editingPhone = true }, onVoice = { pickingVoice = true })
                    EdgeLight(tone, clock)
                }
                if (editingPhone) FamilyPhoneDialog(state.familyPhone, onDone = { input ->
                    editingPhone = false
                    if (input != null) actions.setFamilyPhone(input)
                })
                state.diag?.let { DiagnosticsDialog(it, actions) }
                if (pickingVoice) PersonaDialog(state.persona, onDone = { picked ->
                    pickingVoice = false
                    if (picked != null) actions.setPersona(picked)
                })
            }
        }
    }
}

/** 넓은 창의 기준 크기(dp). 영상 속 앱(1600×894)의 3/4 이다. */
private const val WIDE_W = 1200f
private const val WIDE_H = 670f
/** 세로 휴대전화의 기준 크기(dp). */
private const val NARROW_W = 420f
private const val NARROW_H = 760f
/** 이보다 좁은 가로 창(가로로 든 휴대전화)은 세 칸이 너무 작아져 세로 배치를 쓴다. */
private const val WIDE_MIN_DP = 840f
private const val MIN_SCALE = .7f
private const val MAX_SCALE = 3f

/**
 * 창 크기(dp)로 배치와 배율을 정한다. 넓은 배치는 가로 창이 [WIDE_MIN_DP] 이상이고 [MIN_SCALE] 로 줄여도 다 들어갈 때만.
 * 배율은 늘 유한한 값이고 [MIN_SCALE]..[MAX_SCALE] 로 자른다(크기가 0이거나 무한대인 측정에서도 깨지지 않게).
 */
/** 배율 [scale] 로 그릴 때 실제 48dp 가 되는 누르는 영역(그리는 단위 dp). 키울 때는 그대로 48. */
internal fun touchTargetDp(scale: Float): Float = 48f / scale.coerceAtMost(1f)

internal fun layoutScale(widthDp: Float, heightDp: Float): Pair<Boolean, Float> {
    fun safe(v: Float) = if (v.isFinite() && v > 0f) v.coerceIn(MIN_SCALE, MAX_SCALE) else 1f
    val wideFit = min(widthDp / WIDE_W, heightDp / WIDE_H)
    val wide = widthDp > heightDp && widthDp >= WIDE_MIN_DP && wideFit.isFinite() && wideFit >= MIN_SCALE
    return wide to safe(if (wide) wideFit else min(widthDp / NARROW_W, heightDp / NARROW_H))
}

@Composable
private fun WideLayout(state: UiState, actions: CopilotActions, tone: Tone, clock: State<Float>, typing: MutableState<String>, onFamily: () -> Unit, onVoice: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header(state, tone, clock, onVoice)
        VoiceIssueBanner(state.voiceIssues)
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // 왼쪽: 목소리(오브 · 자막 · 판단 칩 · 빠른 대답). 파워냅 중에는 남은 시간 링.
            GlassPanel(Modifier.weight(339f).fillMaxHeight()) {
                Crossfade(targetState = state.napping, label = "napSwitch") { napping ->
                    Column(Modifier.fillMaxSize().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (napping) NapView(state) else {
                            AiOrb(state, tone, clock, Modifier.fillMaxWidth().weight(1f))
                            Caption(state)
                            HeardLine(state)
                            Spacer(Modifier.height(6.dp))
                            if (state.listening) QuickReplies(actions, typing) else JudgementChips(judgements(state), stacked = true)
                            // 듣는 중이 아니어도 입력칸과 "말하기"는 늘 둔다: 질문을 기다리지 않고 "조용히 해 줘"·"피곤해"를 말할 수 있게.
                            if (!state.listening && state.accompanying) { Spacer(Modifier.height(8.dp)); InputRow(actions, typing, listening = false) }
                        }
                    }
                }
            }
            // 가운데: 대답 속도 · 조향 · 연속 주행
            Tiles(state, clock, vertical = true, modifier = Modifier.weight(339f).fillMaxHeight())
            // 오른쪽: 졸음 예보 + 아래 영역
            GlassPanel(Modifier.weight(450f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ForecastCard(state, Modifier.fillMaxWidth().weight(1f))
                    LowArea(state)
                }
            }
        }
        ActionRow(state, actions, onFamily, onVoice)
        if (state.demoAvailable) DemoRow(state, actions)
    }
}

@Composable
private fun NarrowLayout(state: UiState, actions: CopilotActions, tone: Tone, clock: State<Float>, typing: MutableState<String>, onFamily: () -> Unit, onVoice: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 낮은 창(가로로 든 휴대전화)에서는 안내·버튼 줄을 아래에 붙박지 않고 본문과 함께 스크롤한다.
        // 붙박으면 본문 칸이 한 줄 높이로 줄어 타일이 거의 보이지 않는다.
        val short = maxHeight < 700.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Header(state, tone, clock, onVoice)
            if (!short) VoiceIssueBanner(state.voiceIssues)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                // 낮은 창에서는 오브를 줄여 타일까지 한눈에 들어오게 한다.
                val orb = if (short || maxHeight < 420.dp) 64.dp else 200.dp
                Crossfade(targetState = state.napping, label = "napSwitch") { napping ->
                    Column(
                        Modifier.fillMaxHeight().fillMaxWidth().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(if (short) 10.dp else 0.dp),
                    ) {
                        if (short) VoiceIssueBanner(state.voiceIssues)
                        if (napping) NapView(state) else MainView(state, actions, tone, clock, typing, orb)
                        if (short) {
                            ActionRow(state, actions, onFamily, onVoice)
                            if (state.demoAvailable) DemoRow(state, actions)
                        }
                    }
                }
            }
            if (!short) {
                ActionRow(state, actions, onFamily, onVoice)
                if (state.demoAvailable) DemoRow(state, actions)
            }
        }
    }
}

@Composable
private fun GlassPanel(modifier: Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(modifier.clip(shape).background(GlassBrush).border(1.dp, P.stroke, shape)) { content() }
}

/** 목소리 대화가 안 되는 이유와 고칠 방법. 고쳐질 때까지 위에 남는다. */
@Composable
private fun VoiceIssueBanner(issues: List<String>) {
    if (issues.isEmpty()) return
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Color(0x26FFB020)).border(1.dp, Color(0x66FFB020), shape).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        issues.forEach { Text("⚠ $it", fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFFD27A)) }
    }
}

/** 방금 알아들은 운전자 말. 앱이 들었다는 걸 바로 보여 준다. */
@Composable
private fun HeardLine(state: UiState) {
    val heard = state.chat.lastOrNull { it.fromDriver } ?: return
    Text(
        "들은 말 · “${heard.text}”" + (heard.latencySec?.let { " · %.1f초".format(it) } ?: ""),
        fontSize = 13.sp, color = P.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    )
}

/**
 * 듣는 동안 누를 수 있는 빠른 대답과 입력칸. 목소리와 같은 길로 들어가고, 듣기 시작부터 잰 시간이 대답 속도가 된다.
 * 입력 중인 글([typing])은 듣기가 끝났다 다시 시작해도 남도록 화면 위쪽에서 들고 있다. 키보드의 보내기로도 보낸다.
 */
@Composable
private fun QuickReplies(actions: CopilotActions, typing: MutableState<String>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QUICK_REPLIES.forEach { reply ->
                Box(
                    Modifier.minimumInteractiveComponentSize().clip(CircleShape).background(P.option).border(1.dp, Color(0x553D8BFF), CircleShape)
                        .clickable(role = Role.Button, onClickLabel = "대답하기") { actions.quickReply(reply) }.padding(horizontal = 12.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(reply, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1) }
            }
        }
        InputRow(actions, typing, listening = true)
    }
}

/**
 * 입력칸 한 줄. 듣는 중이면 그 질문의 대답으로, 아니면 운전자가 먼저 하는 말로 보낸다.
 * 듣는 중이 아닐 때는 앞에 "말하기"(누르고 말하기)를 둔다.
 */
@Composable
private fun InputRow(actions: CopilotActions, typing: MutableState<String>, listening: Boolean) {
    val send = {
        if (typing.value.isNotBlank()) {
            actions.quickReply(typing.value)
            typing.value = ""
        }
    }
    val hint = if (listening) "직접 입력해 대답하기" else "직접 입력해 말하기"
    run {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!listening) Box(
                Modifier.heightIn(min = LocalMinimumInteractiveComponentSize.current).clip(RoundedCornerShape(12.dp)).background(P.option)
                    .border(1.dp, Color(0x553D8BFF), RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClickLabel = "질문을 기다리지 않고 말하기") { actions.talk() }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("말하기", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, softWrap = false) }
            // 입력칸 테두리는 버튼과 같은 높이로 그리고, 글자는 그 안에서 세로 가운데에 둔다(작은 배율에서 위로 붙지 않게).
            Box(
                Modifier.weight(1f).heightIn(min = LocalMinimumInteractiveComponentSize.current).clip(RoundedCornerShape(12.dp))
                    .background(P.chip).border(1.dp, P.stroke, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    typing.value, { typing.value = it }, singleLine = true,
                    textStyle = TextStyle(fontFamily = WantedSans, fontSize = 14.sp, color = P.text),
                    cursorBrush = Brush.verticalGradient(listOf(P.cyan, P.cyan)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint }.padding(horizontal = 12.dp, vertical = 10.dp),
                    decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) { if (typing.value.isEmpty()) Text(hint, fontSize = 14.sp, color = P.sub, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis); inner() } },
                )
            }
            val canSend = typing.value.isNotBlank()
            Box(
                Modifier.heightIn(min = LocalMinimumInteractiveComponentSize.current).clip(RoundedCornerShape(12.dp)).background(if (canSend) P.blue else P.blue.copy(alpha = .35f))
                    .clickable(enabled = canSend, role = Role.Button) { send() }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("보내기", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
        }
    }
}

/** "응"은 제안("들를까요?"·"눈 붙이고 가실래요?")에 그렇다고 하는 대답. "응, 괜찮아"는 "괜찮아"가 들어 있어 제안에는 거절로 읽힌다. */
private val QUICK_REPLIES = listOf("응", "응, 괜찮아", "피곤해", "조용히 해 줘", "재밌는 얘기 해 줘", "어, 해 봐")

/**
 * 오늘의 졸음 예보(출발할 때 잠 질문에 답하면 그려진다). 가로축은 출발 뒤 시간, 세로축은 KSS 5~9.
 * 졸림(KSS 7)·위험(KSS 8) 띠, 위험이 시작되는 점, 지금 위치를 함께 그린다.
 */
@Composable
private fun ForecastCard(state: UiState, modifier: Modifier) {
    val pts = state.forecast
    val riskAt = pts.firstOrNull { it.second >= FatigueForecast.RISK_KSS }?.first
    Column(modifier.clip(TileShape).background(P.chip).border(1.dp, P.stroke, TileShape).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("오늘의 졸음 예보", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = P.sub, modifier = Modifier.weight(1f))
            state.restPlanMin?.let { Text("${spokenDuration(it)}에 휴식 약속", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = P.cyan) }
        }
        val dep = state.forecastDepartMin
        Text(
            if (riskAt != null && dep != null) "${FatigueForecast.spokenClock(dep + riskAt)} 위험" else if (pts.isNotEmpty()) "위험 구간 없음" else "출발할 때 그려져요",
            fontSize = if (pts.isEmpty()) 20.sp else 26.sp, fontWeight = FontWeight.Bold, color = if (riskAt != null) Color(0xFFFF8A70) else P.text,
        )
        if (pts.isEmpty()) {
            // 예보 전: 빈 상자 대신 어떤 그림이 그려질지 흐리게 보여 준다(졸림·위험 띠와 점선 곡선).
            Canvas(Modifier.fillMaxWidth().weight(1f)) {
                drawRect(Color(0x14FF6F61), Offset(0f, 0f), Size(size.width, size.height * .25f))
                drawRect(Color(0x10FFC061), Offset(0f, size.height * .25f), Size(size.width, size.height * .25f))
                val path = Path().apply {
                    moveTo(0f, size.height * .9f)
                    cubicTo(size.width * .35f, size.height * .75f, size.width * .55f, size.height * .2f, size.width, size.height * .15f)
                }
                drawPath(path, P.sub.copy(alpha = .55f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))))
            }
            Text("\"어젯밤 몇 시간 주무셨어요?\"에 답하면 언제 졸릴지 미리 그려요", fontSize = 12.sp, color = P.sub, maxLines = 2)
        }
        if (pts.isNotEmpty()) Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val maxM = pts.last().first.coerceAtLeast(1)
            fun x(m: Int) = size.width * m / maxM
            fun y(k: Float) = size.height * ((9f - k) / 4f).coerceIn(0f, 1f)
            drawRect(Color(0x26FF6F61), Offset(0f, 0f), Size(size.width, y(8f)))
            drawRect(Color(0x1FFFC061), Offset(0f, y(8f)), Size(size.width, y(7f) - y(8f)))
            val path = Path().apply { pts.forEachIndexed { i, (m, k) -> if (i == 0) moveTo(x(m), y(k)) else lineTo(x(m), y(k)) } }
            drawPath(path, P.cyan, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            riskAt?.let { r -> pts.firstOrNull { it.first == r }?.let { drawCircle(Color(0xFFFF6F61), 6.dp.toPx(), Offset(x(r), y(it.second))) } }
            val now = state.forecastNowMin.coerceIn(0, maxM)
            drawLine(Color.White.copy(alpha = .5f), Offset(x(now), 0f), Offset(x(now), size.height), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        }
        if (pts.isNotEmpty()) Text("수면 과학 모형(세 과정 모형) · 달리는 동안 대답 속도로 확인", fontSize = 11.sp, color = P.sub, maxLines = 1)
    }
}

@Composable
private fun Header(state: UiState, tone: Tone, clock: State<Float>, onVoice: () -> Unit) {
    val dot by animateColorAsState(tone.color, label = "stageDot")
    val drill = state.voicePersona == Persona.DRILL
    val canPick = state.parked || !state.accompanying
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // 앱 아이콘(런처 아이콘과 같은 그림). 108 단위 그림의 가운데 72 단위가 보이도록 1.5배로 그리고 자른다.
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF0B0E14)), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(48.dp))
        }
        Text("옆자리", fontFamily = Wordmark, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, maxLines = 1, modifier = Modifier.semantics { contentDescription = "옆자리" })
        Box(
            Modifier.minimumInteractiveComponentSize()
                .clip(CircleShape)
                .background(if (drill) Color(0x1FFF5A3C) else Color(0x0FFFFFFF))
                .border(1.dp, if (drill) Color(0x80FF5A3C) else P.stroke, CircleShape)
                .then(if (canPick) Modifier.clickable(role = Role.Button, onClickLabel = "목소리 고르기", onClick = onVoice) else Modifier)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                state.voicePersona.label + if (canPick) " ▾" else "",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                color = if (drill) Color(0xFFFF9A80) else Color(0xFFCFD7E2),
            )
        }
        // 오른쪽 상태 칩: 남은 폭만 쓰고 길면 말줄임. 듣는 동안은 이 자리에 "듣는 중" 알약이 뜬다(본문을 가리지 않게).
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Crossfade(targetState = state.listening, label = "headerStatus") { listening ->
                if (listening) ListeningPill(clock) else Row(
                    Modifier.clip(CircleShape).background(P.chip).border(1.dp, P.stroke, CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Box(Modifier.size(8.dp).drawBehind {
                        drawCircle(dot.copy(alpha = .35f), radius = 7.dp.toPx())
                        drawCircle(dot)
                    })
                    Text(statusText(state), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
            }
        }
    }
}

// ---------- 본문: 오브 · 자막 · 판단 칩 · 타일 · 아래 영역 ----------

@Composable
private fun MainView(state: UiState, actions: CopilotActions, tone: Tone, clock: State<Float>, typing: MutableState<String>, orb: androidx.compose.ui.unit.Dp = 200.dp) {
    AiOrb(state, tone, clock, Modifier.size(orb))
    Caption(state)
    HeardLine(state)
    if (state.listening) QuickReplies(actions, typing) else JudgementChips(judgements(state))
    if (!state.listening && state.accompanying) { Spacer(Modifier.height(8.dp)); InputRow(actions, typing, listening = false) }
    Spacer(Modifier.height(12.dp))
    Tiles(state, clock)
    Spacer(Modifier.height(12.dp))
    // 세로·좁은 화면에서도 넓은 화면처럼 오늘의 졸음 예보를 보여 준다(빈 자리 없이)
    ForecastCard(state, Modifier.fillMaxWidth().height(190.dp))
    Spacer(Modifier.height(12.dp))
    LowArea(state)
}

/** 돌아다니는 빛 덩어리로 만든 AI 오브. 말할 때는 파형, 들을 때는 물결, 주의·위험에서는 후광이 뛴다. */
@Composable
private fun AiOrb(state: UiState, tone: Tone, clock: State<Float>, modifier: Modifier) {
    val spec = tween<Color>(durationMillis = 700)
    val c0 = animateColorAsState(tone.orb[0], spec, label = "orb0")
    val c1 = animateColorAsState(tone.orb[1], spec, label = "orb1")
    val c2 = animateColorAsState(tone.orb[2], spec, label = "orb2")
    val speak = animateFloatAsState(if (state.speaking) 1f else 0f, tween(250), label = "orbSpeak")
    val listen = animateFloatAsState(if (state.listening) 1f else 0f, tween(250), label = "orbListen")
    val level = animateFloatAsState(
        when (tone) {
            Tone.RED -> 1f
            Tone.AMBER -> .5f
            else -> 0f
        },
        tween(500), label = "orbLevel",
    )
    // 덧셈 합성(BlendMode.Plus)이 배경까지 밝히지 않도록 오브만 따로 그린 뒤 얹는다.
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val t = clock.value
        val w = size.minDimension
        if (w <= 1f) return@Canvas   // 자리가 없으면 그리지 않는다(반지름 0 그라데이션은 오류)
        val c = Offset(size.width / 2, size.height / 2)
        val cs = listOf(c0.value, c1.value, c2.value)
        // 말하는 세기: 실제 음량은 없어서 겹친 사인파로 만든 가짜 진폭(말하는 동안만).
        val amp = speak.value * (.35f + .65f * abs(sin(t * 9f) * sin(t * 5.3f + 1f)))
        val breathe = .5f + .5f * sin(t * 1.6f)
        val r = w * .2f * (1f + .05f * breathe + .16f * amp)
        // 바깥 빛무리
        drawCircle(
            Brush.radialGradient(0f to cs[1].copy(alpha = .33f), (r * .2f / (w * .5f)) to cs[1].copy(alpha = .33f), 1f to cs[1].copy(alpha = 0f), center = c, radius = w * .5f),
            radius = w * .5f, center = c, blendMode = BlendMode.Plus,
        )
        // 돌아다니는 빛 덩어리 4개
        for (i in 0 until 4) {
            val a = t * (.6f + i * .17f) + i * 1.7f
            val orbitR = r * (.26f + .08f * sin(t * 1.3f + i))
            val p = Offset(c.x + cos(a) * orbitR, c.y + sin(a * 1.13f) * orbitR)
            drawCircle(
                Brush.radialGradient(
                    0f to cs[i % 3].copy(alpha = .93f), .55f to cs[(i + 1) % 3].copy(alpha = .33f), 1f to cs[(i + 1) % 3].copy(alpha = 0f),
                    center = p, radius = r * 1.05f,
                ),
                radius = r * 1.25f, center = c, blendMode = BlendMode.Plus,
            )
        }
        // 흰 하이라이트
        drawCircle(
            Brush.radialGradient(
                0f to Color.White.copy(alpha = .85f), .35f to Color.White.copy(alpha = .12f), 1f to Color.White.copy(alpha = 0f),
                center = Offset(c.x - r * .25f, c.y - r * .3f), radius = r * 1.1f,
            ),
            radius = r, center = c, blendMode = BlendMode.Plus,
        )
        // 말하는 중: 방사형 파형
        if (amp > .01f) {
            for (i in 0 until 72) {
                val a = i / 72f * TAU
                val n = .5f + .5f * sin(i * 1.7f + t * 14f) * sin(i * .6f - t * 7f)
                val len = w * .02f + w * .09f * amp * n
                val r0 = r * 1.32f
                drawLine(
                    cs[0].copy(alpha = .85f),
                    Offset(c.x + cos(a) * r0, c.y + sin(a) * r0),
                    Offset(c.x + cos(a) * (r0 + len), c.y + sin(a) * (r0 + len)),
                    strokeWidth = w * .008f, cap = StrokeCap.Round,
                )
            }
        }
        // 듣는 중: 퍼지는 물결 3개
        if (listen.value > .01f) {
            for (i in 0 until 3) {
                val p = (t * .7f + i / 3f) % 1f
                drawCircle(
                    Color(0xFF7AB8FF).copy(alpha = .6f * (1f - p) * listen.value),
                    radius = r * (1.25f + p * 1.1f), center = c, style = Stroke(w * .007f),
                )
            }
        }
        // 주의·위험 후광(위험은 두 배 빠르게)
        val lvl = level.value
        if (lvl > .01f) {
            val pulse = .5f + .5f * sin(t * (if (lvl > .9f) 12.6f else 6.3f))
            drawCircle(
                cs[1].copy(alpha = ((.25f + .45f * pulse * lvl) * (lvl * 2f).coerceAtMost(1f)).coerceIn(0f, 1f)),
                radius = r * 1.5f, center = c, style = Stroke(w * .012f),
            )
        }
        // 얇은 궤도 고리와 도는 점
        drawCircle(Color.White.copy(alpha = .10f), radius = r * 1.62f, center = c, style = Stroke(w * .004f))
        val ang = t * .9f
        drawCircle(cs[0], radius = w * .008f * 2f, center = Offset(c.x + cos(ang) * r * 1.62f, c.y + sin(ang) * r * 1.62f))
    }
}

/** 가장 최근 옆자리 말. 아직 말한 적이 없으면 흐린 안내 문구. 열혈 교관 목소리일 때는 붉은 기를 띤다. */
@Composable
private fun Caption(state: UiState) {
    val line = state.chat.lastOrNull { !it.fromDriver }?.text
    val drill = state.voicePersona == Persona.DRILL
    Crossfade(targetState = line, label = "caption") { l ->
        Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 10.dp), contentAlignment = Alignment.TopCenter) {
            Text(
                l ?: "고속 주행이 이어지면 먼저 말을 걸게요",
                // 긴 말도 자르지 않는다(칸이 늘어난다). 아주 긴 말만 6줄에서 끊는다.
                fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                maxLines = 6, overflow = TextOverflow.Ellipsis,
                color = when {
                    l == null -> P.text.copy(alpha = .32f)
                    drill -> Color(0xFFFFB4A3)
                    else -> P.text
                },
            )
        }
    }
}

private enum class Kind { NEUTRAL, CAUTION, DANGER, OK }

private data class Judgement(val text: String, val kind: Kind)

/** 지금 상태에서 읽어 낼 수 있는 판단 근거. 한 줄에 들어가도록 세 개까지만. */
private fun judgements(s: UiState): List<Judgement> {
    if (!s.accompanying) return emptyList()
    val out = mutableListOf<Judgement>()
    s.quietReason?.let { out += Judgement("$it · 말 걸지 않음", Kind.NEUTRAL) }
    if (s.laneAssist) out += Judgement("차로 유지 보조 켜짐 · 대답으로 확인", Kind.NEUTRAL)
    if (s.staleAirFixed) out += Judgement("내기 순환 오래 · 외기로 환기", Kind.NEUTRAL)
    if (s.listening) out += Judgement("대답 기다리는 중", Kind.NEUTRAL)
    val lat = s.lastLatencySec
    if (s.stage == AlertStage.CAUTION) {
        if (lat != null) out += Judgement("대답 %.1f초 · 평소의 %.1f배".format(lat, lat / s.baselineSec.coerceAtLeast(.1)), Kind.CAUTION)
        out += Judgement("판단 · 주의", Kind.CAUTION)
    }
    if (s.stage == AlertStage.DANGER) {
        out += Judgement("판단 · 위험", Kind.DANGER)
        if (s.wakeActions.isNotEmpty()) out += Judgement("깨우기 시작", Kind.DANGER)
    }
    if (s.remembered.isNotEmpty()) out += Judgement("기억 · ${s.remembered}", Kind.NEUTRAL)
    return out.take(3)
}

@Composable
private fun JudgementChips(chips: List<Judgement>, stacked: Boolean = false) {
    val item: @Composable (Judgement) -> Unit = { chip ->
            val (fg, line) = when (chip.kind) {
                Kind.NEUTRAL -> P.sub to P.stroke
                Kind.CAUTION -> Color(0xFFFFC64D) to Color(0x66FFB020)
                Kind.DANGER -> Color(0xFFFF8A70) to Color(0x66FF5A3C)
                Kind.OK -> P.ok to Color(0x592FE39A)
            }
            Text(
                chip.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clip(CircleShape).background(P.chip).border(1.dp, line, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
            )
    }
    if (stacked) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) { chips.forEach { item(it) } }
    else Row(Modifier.fillMaxWidth().height(26.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        // 칩 글이 길어 한 줄을 넘으면 칩마다 말줄임으로 줄인다(끝이 잘려 나가지 않게).
        chips.forEach { Box(Modifier.weight(1f, fill = false)) { item(it) } }
    }
}

// ---------- 타일 3개 ----------

private const val TILE_MAX_SEC = 3.6
private const val SLOW_RATIO = 1.8
private const val TRIP_MAX_MIN = 180L
private const val REST_MIN = 120L

@Composable
private fun Tiles(state: UiState, clock: State<Float>, vertical: Boolean = false, modifier: Modifier = Modifier) {
    val history = state.latencyHistory
    val lat = state.lastLatencySec ?: state.baselineSec
    val noAnswers = history.takeLastWhile { it < 0 }.size
    val slow = lat > state.baselineSec * SLOW_RATIO
    val tile1Glow = if (state.accompanying && state.stage != AlertStage.NORMAL) (if (state.stage == AlertStage.DANGER) P.danger else P.caution) else null
    val restMin = state.restPlanMin?.toLong() ?: REST_MIN
    val over = state.drivingMinutes >= restMin
    // 넓은 창에서는 세로로 쌓고(영상의 가운데 칸) 글자를 키운다. 좁은 창에서는 한 줄에 셋.
    val each = if (vertical) Modifier.fillMaxWidth() else Modifier
    val body: @Composable (Modifier.() -> Modifier) -> Unit = { w ->
        Tile(
            "대답 속도",
            if (noAnswers > 0) "무응답 ${noAnswers}회" else if (slow) "평소의 %.1f배".format(lat / state.baselineSec.coerceAtLeast(.1)) else "평소 %.1f초".format(state.baselineSec),
            if (noAnswers > 0) Color(0xFFFF8A70) else if (slow) P.caution else P.sub,
            if (noAnswers > 0) AnnotatedString("무응답") else withUnit("%.1f".format(lat), "초"),
            if (noAnswers > 0) P.danger else if (slow) P.caution else P.text,
            tile1Glow, each.w(), big = vertical,
        ) { LatencyBars(history, state.baselineSec) }
        Tile(
            "조향", if (state.weaving) "평소보다 잦음" else "차로 중앙", P.sub,
            AnnotatedString(if (state.weaving) "흔들림" else "안정"), if (state.weaving) P.caution else P.text,
            if (state.weaving && state.accompanying) P.caution else null, each.w(), big = vertical,
        ) { SteerLine(state.steerTrace, state.weaving, clock) }
        // 졸음 예보가 있으면 위험(KSS 8 이상)이 시작되는 시각을 보여 준다.
        val riskAt = state.forecast.firstOrNull { it.second >= FatigueForecast.RISK_KSS }?.first
        val forecastNote = riskAt?.let { r -> state.forecastDepartMin?.let { "${FatigueForecast.spokenClock(it + r)} 졸음 예보" } }
        Tile(
            "연속 주행", if (over) "휴식 권장" else forecastNote ?: if (state.restPlanMin != null) "${spokenDuration(restMin.toInt())}에 휴식 약속" else "2시간마다 휴식",
            if (over) P.caution else P.sub,
            minutesText(state.drivingMinutes), P.text,
            if (over) P.caution else null, each.w(), big = vertical,
        ) { TripBar(state.drivingMinutes, restMin, state.forecast) }
    }
    if (vertical) Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) { body { weight(1f) } }
    else Row(modifier.fillMaxWidth().height(134.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) { body { weight(1f) } }
}

@Composable
private fun Tile(
    title: String, status: String, statusColor: Color, value: AnnotatedString, valueColor: Color,
    glow: Color?, modifier: Modifier, big: Boolean = false, graph: @Composable () -> Unit,
) {
    val glowColor by animateColorAsState(glow ?: Color.Transparent, tween(500), label = "tileGlow")
    Box(modifier.fillMaxHeight().clip(TileShape).background(GlassBrush).border(1.dp, P.stroke, TileShape).semantics(mergeDescendants = true) {}) {
        val label = if (big) 14.sp else 12.sp
        Column(Modifier.padding(horizontal = if (big) 18.dp else 12.dp, vertical = if (big) 15.dp else 11.dp)) {
            if (big) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = label, fontWeight = FontWeight.SemiBold, color = P.sub, maxLines = 1)
                Text(status, fontSize = label, fontWeight = FontWeight.SemiBold, color = statusColor, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            } else {
                // 좁은 타일(세로 휴대전화): 제목 아래에 상태를 둬서 "평소 1.0초" 같은 상태가 잘리지 않게 한다.
                Text(title, fontSize = label, fontWeight = FontWeight.SemiBold, color = P.sub, maxLines = 1)
                Text(status, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = statusColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(value, fontSize = if (big) 40.sp else 25.sp, fontWeight = if (big) FontWeight.Medium else FontWeight.Bold, color = valueColor, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = if (big) 16.dp else 10.dp, end = if (big) 16.dp else 10.dp, bottom = if (big) 14.dp else 9.dp).height(if (big) 46.dp else 38.dp)) { graph() }
        // 지금 상태가 가리키는 타일은 가장자리에 안쪽 빛이 번진다.
        Canvas(Modifier.fillMaxSize()) {
            if (glowColor.alpha > .01f) innerGlow(glowColor, 16.dp.toPx(), 1.5.dp.toPx(), 22.dp.toPx())
        }
    }
}

private fun withUnit(num: String, unit: String): AnnotatedString = buildAnnotatedString {
    append(num)
    withStyle(SpanStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = P.sub)) { append(unit) }
}

private fun minutesText(m: Long): AnnotatedString = buildAnnotatedString {
    val small = SpanStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = P.sub)
    if (m >= 60) {
        append("${m / 60}")
        withStyle(small) { append("시간 ") }
    }
    append("${m % 60}")
    withStyle(small) { append("분") }
}

/** 가장자리에서 안으로 옅어지는 빛. 테두리선 한 줄과, 안쪽으로 겹쳐 쌓은 넓은 띠로 번짐을 만든다. */
private fun DrawScope.innerGlow(color: Color, corner: Float, ring: Float, depth: Float) {
    val steps = 6
    for (i in 0..steps) {
        val band = if (i == steps) ring else ring + depth * (steps - i) / steps
        val a = if (i == steps) color.alpha else color.alpha * .1f
        drawRoundRect(
            color.copy(alpha = a),
            topLeft = Offset(band / 2, band / 2),
            size = Size((size.width - band).coerceAtLeast(0f), (size.height - band).coerceAtLeast(0f)),
            cornerRadius = CornerRadius((corner - band / 2).coerceAtLeast(0f)),
            style = Stroke(band),
        )
    }
}

/** 최근 대답 시간 막대(최대 7개)와 평소 기준 점선. 평소의 1.8배를 넘으면 노랑, 무응답은 빨강. */
@Composable
private fun LatencyBars(history: List<Double>, baseline: Double) {
    Canvas(Modifier.fillMaxSize()) {
        val sx = size.width / 120f
        val sy = size.height / 38f
        val maxSec = maxOf(TILE_MAX_SEC, history.maxOrNull() ?: 0.0, baseline).toFloat()
        val by = (38f - baseline.toFloat() / maxSec * 36f) * sy
        drawLine(
            Color.White.copy(alpha = .35f), Offset(0f, by), Offset(size.width, by),
            strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())),
        )
        history.takeLast(7).forEachIndexed { i, v ->
            val noAnswer = v < 0
            val h = (if (noAnswer) 3.5f else v.toFloat()) / maxSec * 36f * sy
            val color = when {
                noAnswer -> P.danger.copy(alpha = .85f)
                v > baseline * SLOW_RATIO -> P.caution
                else -> P.blue
            }
            drawRoundRect(color, Offset((4 + i * 16) * sx, size.height - h), Size(9 * sx, h), CornerRadius(2.5f * sx))
        }
    }
}

/**
 * 조향 스파크라인. 핸들 각도 표본이 있으면 그대로 그리고(최대 ±값에 맞춰 늘림),
 * 아직 없으면(미리보기·동행 직후) 잔잔한 가짜 선을 흘려 보내며 흔들릴 때만 크게 출렁이게 한다.
 */
@Composable
private fun SteerLine(trace: List<Float>, weaving: Boolean, clock: State<Float>) {
    val line = if (weaving) P.caution else P.cyan
    Canvas(Modifier.fillMaxSize()) {
        val mid = size.height / 2
        drawLine(Color.White.copy(alpha = .12f), Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1.dp.toPx())
        val n = 60
        val path = Path()
        val scale = maxOf(10f, trace.maxOfOrNull { abs(it) } ?: 0f)
        val t = clock.value
        for (i in 0..n) {
            val v = if (trace.size >= 2) {
                trace[(i * (trace.size - 1)) / n] / scale * (mid * .85f / (24f * size.height / 38f))
            } else {
                // 표본이 아직 없으면 실제 신호처럼 보이지 않게 잔잔한 기다림 선만 흘린다.
                val tt = t - 10f + i / 6f
                .06f * sin(tt * 3.1f) + .03f * sin(tt * 7.3f)
            }
            val x = size.width * i / n
            val y = mid - v * 24f * size.height / 38f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** 연속 주행 3시간 기준 진행 막대와 2시간 눈금. 2시간을 넘기면 노랑. */
@Composable
private fun TripBar(minutes: Long, restMin: Long = REST_MIN, forecast: List<Pair<Int, Float>> = emptyList()) {
    val over = minutes >= restMin
    // 휴식 약속이 3시간보다 길면 막대 끝을 그 약속에 맞춘다(눈금이 막대 밖으로 나가지 않게).
    val maxMin = maxOf(TRIP_MAX_MIN, restMin)
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val sy = size.height / 38f
            val h = 5 * sy
            val top = 26 * sy
            drawRoundRect(Color.White.copy(alpha = .08f), Offset(0f, top), Size(size.width, h), CornerRadius(h / 2))
            val frac = (minutes.toFloat() / maxMin).coerceIn(0f, 1f)
            if (frac > 0f) drawRoundRect(if (over) P.caution else P.blue, Offset(0f, top), Size(size.width * frac, h), CornerRadius(h / 2))
            // 졸음 예보: 막대 위에 졸림(KSS 7) 구간은 주황, 위험(KSS 8) 구간은 빨강으로 얇게 칠한다.
            for ((m, kss) in forecast) {
                if (kss < FatigueForecast.CAUTION_KSS || m > maxMin) continue
                val x0 = size.width * m / maxMin
                val w = size.width * 5f / maxMin
                drawRect((if (kss >= FatigueForecast.RISK_KSS) P.danger else P.caution).copy(alpha = .75f), Offset(x0, top - 3 * sy), Size(w, 2 * sy))
            }
            val tick = size.width * restMin / maxMin
            drawLine(Color.White.copy(alpha = .5f), Offset(tick, 20 * sy), Offset(tick, 36 * sy), strokeWidth = 1.2.dp.toPx())
        }
        // 휴식 라벨("2h", "1.5h"): 눈금 위치에 가운데를 맞춘다.
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(restMin.coerceAtLeast(1).toFloat()))
            Text(
                when {
                    restMin % 60 == 0L -> "${restMin / 60}h"
                    restMin % 30 == 0L -> "${restMin / 60}.5h"
                    restMin < 60 -> "${restMin}m"
                    else -> "${restMin / 60}h${restMin % 60}"
                }, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = P.sub, maxLines = 1,
                modifier = Modifier.layout { measurable, constraints ->
                    val p = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
                    layout(0, p.height) { p.place(-p.width / 2, 0) }
                },
            )
            Spacer(Modifier.weight((maxMin - restMin).coerceAtLeast(1).toFloat()))
        }
    }
}

// ---------- 아래 영역: 대화 / 깨우기 동작 / 휴식 장소 / 오늘의 운행 ----------

private enum class LowMode { SUMMARY, WAKE, REST, CHAT }

@Composable
private fun LowArea(state: UiState) {
    val mode = when {
        state.tripSummary.isNotEmpty() && (state.parked || !state.accompanying) -> LowMode.SUMMARY
        state.wakeActions.isNotEmpty() -> LowMode.WAKE
        state.restOption != null -> LowMode.REST
        else -> LowMode.CHAT
    }
    Crossfade(targetState = mode, label = "lowArea") { m ->
        Box(Modifier.fillMaxWidth().heightIn(min = 118.dp)) {
            when (m) {
                LowMode.SUMMARY -> TripSummaryCard(state.tripSummary)
                LowMode.WAKE -> WakeList(state.wakeActions)
                LowMode.REST -> state.restOption?.let { RestCard(it, state.speedKph) }
                LowMode.CHAT -> ChatLow(state)
            }
        }
    }
}

@Composable
private fun ChatLow(state: UiState) {
    Column(Modifier.fillMaxWidth().heightIn(min = 118.dp), verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.Bottom)) {
        if (state.chat.isEmpty()) state.log.firstOrNull()?.let { Text(it, fontSize = 13.sp, color = P.sub) }
        state.chat.takeLast(2).forEach { Bubble(it) }
    }
}

@Composable
private fun Bubble(line: ChatLine) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (line.fromDriver) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        val meta = @Composable {
            Column(horizontalAlignment = if (line.fromDriver) Alignment.End else Alignment.Start, modifier = Modifier.padding(start = 7.dp, end = 7.dp, bottom = 3.dp)) {
                Text(line.time, fontSize = 10.5.sp, color = P.sub, maxLines = 1)
                if (line.fromDriver) Text(line.latencySec?.let { "운전석 · %.1f초".format(it) } ?: "운전석", fontSize = 10.5.sp, color = P.sub, maxLines = 1)
            }
        }
        if (line.fromDriver) meta()
        val shape = RoundedCornerShape(18.dp)
        Text(
            line.text,
            fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
            maxLines = 6, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).widthIn(max = 330.dp)
                .background(if (line.fromDriver) Brush.verticalGradient(listOf(P.blue, P.blueDeep)) else Brush.verticalGradient(listOf(P.bubble, P.bubble)), shape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
        if (!line.fromDriver) meta()
    }
}

/** 깨우기 동작 목록. 실행되기 전 줄은 흐리게, 실행되면 선명해지며 ✓ 가 켜진다. */
@Composable
private fun WakeList(fired: List<String>) {
    val rows = listOf(
        Triple(WAKE_WINDOW, 0, P.cold),
        Triple(WAKE_COLD_AIR, 1, P.cold),
        Triple(WAKE_SEAT, 2, P.cold),
        Triple(WAKE_VOICE, 3, Color(0xFFFF8A70)),
    ) + listOfNotNull(Triple(WAKE_FAMILY, 4, Color(0xFFFF8A70)).takeIf { WAKE_FAMILY in fired })
    // 가족 연락은 보냈을 때만 다섯째 줄로 붙인다. 칸 높이는 그대로 두고 줄 간격만 줄인다.
    val rowH = if (rows.size > 4) 22.dp else 26.dp
    Column(Modifier.fillMaxWidth().height(118.dp)) {
        rows.forEach { (label, icon, tint) ->
            val on = label in fired
            val a by animateFloatAsState(if (on) 1f else .25f, tween(400), label = "wakeRow")
            Row(Modifier.fillMaxWidth().height(rowH).graphicsLayer { alpha = a }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Canvas(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(tint.copy(alpha = .15f)).padding(4.dp)) {
                    drawWakeIcon(icon, tint)
                }
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (on) Text("✓", fontSize = 13.sp, color = P.ok)
            }
        }
    }
}

/** 깨우기 동작 아이콘(24 단위 좌표를 칸 크기에 맞춘 선 그림): 0 창문, 1 찬 바람, 2 시트, 3 목소리, 4 문자. */
private fun DrawScope.drawWakeIcon(kind: Int, color: Color) {
    val u = size.minDimension / 24f
    val st = Stroke(1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(color, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), strokeWidth = 1.9f * u, cap = StrokeCap.Round)
    when (kind) {
        0 -> {
            drawRoundRect(color, Offset(3 * u, 4 * u), Size(18 * u, 14 * u), CornerRadius(2 * u), style = st)
            line(3f, 12f, 21f, 12f)
            line(12f, 15f, 12f, 18f)
        }
        1 -> {
            line(3f, 8f, 14f, 8f)
            line(3f, 12f, 18f, 12f)
            line(3f, 16f, 10f, 16f)
        }
        2 -> {
            val p = Path().apply {
                moveTo(7 * u, 4 * u)
                lineTo(9 * u, 16 * u)
                lineTo(17 * u, 16 * u)
                lineTo(18 * u, 21 * u)
            }
            drawPath(p, color, style = st)
            line(9f, 16f, 6f, 21f)
        }
        4 -> {
            drawRoundRect(color, Offset(3 * u, 6 * u), Size(18 * u, 12 * u), CornerRadius(2 * u), style = st)
            line(3.5f, 7f, 12f, 13f)
            line(20.5f, 7f, 12f, 13f)
        }
        else -> {
            line(12f, 3f, 12f, 6f)
            line(5f, 7f, 7f, 9f)
            line(19f, 7f, 17f, 9f)
            drawArc(color, 180f, 180f, useCenter = false, topLeft = Offset(8 * u, 10 * u), size = Size(8 * u, 8 * u), style = st)
            line(8f, 14f, 8f, 17f)
            line(16f, 14f, 16f, 17f)
            line(8f, 17f, 16f, 17f)
        }
    }
}

/** 제안한 휴식 장소: 큰 거리, 이름, 걸리는 시간, 편의시설, 경유지에 넣었는지. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RestCard(option: RestOption, speedKph: Int) {
    val shape = RoundedCornerShape(18.dp)
    // 현재 속도(없으면 시속 100km)로 달렸을 때의 대략적인 시간.
    val minutes = (option.km * 60.0 / (if (speedKph >= 30) speedKph else 100)).roundToInt().coerceAtLeast(1)
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.linearGradient(listOf(Color(0x383D8BFF), Color(0x0F3D8BFF))), shape)
            .border(1.dp, Color(0x593D8BFF), shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                buildAnnotatedString {
                    append("${option.km}")
                    withStyle(SpanStyle(fontSize = 16.sp)) { append("km") }
                },
                fontSize = 38.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            )
            Column {
                Text(option.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("약 ${minutes}분", fontSize = 12.5.sp, color = Color(0xFFB9C6D8))
            }
        }
        // 칩이 많아도 잘리지 않게 줄을 바꿔 놓는다. 가장 중요한 "경유지 추가"를 맨 앞에.
        FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (option.waypointAdded) RestChip("✓ 경유지 추가", ok = true)
            option.amenities?.split(",", "·")?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { RestChip(it, ok = false) }
        }
    }
}

@Composable
private fun RestChip(text: String, ok: Boolean) {
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (ok) P.ok else P.text, maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(if (ok) Color(0x292FE39A) else Color(0x14FFFFFF)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

// ---------- 파워냅 ----------

/** 낮잠 중 본문: 보라 그라디언트 링 카운트다운과 지금 해 둔 일들. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NapView(state: UiState) {
    val ends = state.napEndsAtMs
    val now by produceState(SystemClock.elapsedRealtime(), ends) {
        while (ends != null) {
            value = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val leftMs = ends?.let { (it - now).coerceAtLeast(0) } ?: state.napTotalMs
    val frac = if (state.napTotalMs > 0) (leftMs.toFloat() / state.napTotalMs).coerceIn(0f, 1f) else 1f
    Spacer(Modifier.height(40.dp))
    Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            val inset = 13.dp.toPx()
            val tl = Offset(inset, inset)
            val sz = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(Color.White.copy(alpha = .07f), -90f, 360f, useCenter = false, topLeft = tl, size = sz, style = Stroke(stroke))
            if (frac > 0f) {
                drawArc(P.violet.copy(alpha = .25f), -90f, 360f * frac, useCenter = false, topLeft = tl, size = sz, style = Stroke(stroke * 2f, cap = StrokeCap.Round))
                drawArc(
                    Brush.linearGradient(listOf(Color(0xFFB7ABFF), Color(0xFF6A5CFF)), start = Offset.Zero, end = Offset(size.width, size.height)),
                    -90f, 360f * frac, useCenter = false, topLeft = tl, size = sz, style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("파워냅", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = P.sub, letterSpacing = 1.5.sp)
            Text("%02d:%02d".format(leftMs / 60_000, leftMs / 1000 % 60), fontSize = 58.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("남음", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = P.sub)
        }
    }
    // 가족 문자 칩까지 붙으면 넓은 배치의 왼쪽 칸보다 길어진다: 잘리지 않게 줄을 바꾼다.
    FlowRow(
        Modifier.padding(top = 26.dp, start = 8.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NapChip("좌석 눕힘", ok = false)
        NapChip("환기", ok = false)
        NapChip("알람", ok = false)
        if (state.familyNotified) NapChip("✓ 가족에게 문자", ok = true)
    }
}

@Composable
private fun NapChip(text: String, ok: Boolean) {
    Text(
        text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        color = if (ok) P.ok else Color(0xFFC9C1FF),
        modifier = Modifier.clip(CircleShape)
            .background(if (ok) Color(0x1F2FE39A) else Color(0x248C7CFF))
            .border(1.dp, if (ok) Color(0x4D2FE39A) else Color(0x4D8C7CFF), CircleShape)
            .padding(horizontal = 13.dp, vertical = 7.dp),
    )
}

// ---------- 가장자리 빛 · 듣는 중 알약 ----------

/** 창 가장자리 빛. 정상은 옅게, 주의는 천천히 숨 쉬듯, 위험은 초당 2번 깜빡인다. */
@Composable
private fun EdgeLight(tone: Tone, clock: State<Float>) {
    val color = animateColorAsState(tone.color, tween(500), label = "edgeColor")
    val level = animateFloatAsState(
        when (tone) {
            Tone.RED -> 1f
            Tone.AMBER -> .55f
            Tone.INDIGO -> .3f
            Tone.GREEN -> .18f
            Tone.GRAY -> 0f
        },
        tween(500), label = "edgeLevel",
    )
    Canvas(Modifier.fillMaxSize()) {
        val t = clock.value
        val pulse = when (tone) {
            Tone.RED -> .5f + .5f * sin(t * 12.6f)
            Tone.AMBER -> .6f + .4f * sin(t * 3.2f)
            else -> 1f
        }
        val corner = 24.dp.toPx()
        val base = 1.5.dp.toPx()
        drawRoundRect(
            Color.White.copy(alpha = .08f), Offset(base / 2, base / 2), Size(size.width - base, size.height - base),
            CornerRadius(corner - base / 2), style = Stroke(base),
        )
        val a = (level.value * pulse).coerceIn(0f, 1f)
        if (a > .01f) innerGlow(color.value.copy(alpha = a), corner, 2.dp.toPx(), 40.dp.toPx())
    }
}

@Composable
private fun ListeningPill(clock: State<Float>) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.shadow(12.dp, shape, ambientColor = P.blue, spotColor = P.blue)
            .background(Brush.verticalGradient(listOf(P.blue, Color(0xFF2A6FE0))), shape)
            .border(1.dp, Color(0x2EFFFFFF), shape)
            .height(34.dp).padding(start = 14.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Canvas(Modifier.size(width = 27.dp, height = 16.dp).semantics { contentDescription = "듣는 중" }) {
            val t = clock.value
            for (i in 0 until 5) {
                val h = (4f + 12f * abs(sin(t * 9f + i * 1.3f))).dp.toPx()
                val x = i * 6.dp.toPx() + 1.5.dp.toPx()
                drawLine(Color.White, Offset(x, (size.height - h) / 2), Offset(x, (size.height + h) / 2), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Text("운전석에서 듣는 중…", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

// ---------- 동작 알약 · 시연 ----------

@Composable
private fun ActionRow(state: UiState, actions: CopilotActions, onFamily: () -> Unit, onVoice: () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!state.accompanying) Pill("같이 가기", primary = true, onClick = actions::startNow)
        if (state.coffeeWaiting) Pill("파워냅 시작", primary = true, onClick = actions::startNapNow)
        Pill("재밌는 얘기", onClick = actions::requestStory)
        if (state.parked || !state.accompanying) Pill("목소리", onClick = onVoice)
        // 번호는 정차 중에만 바꾼다(주행 중 입력을 막는다).
        if (state.parked || !state.accompanying) Pill("가족 번호", onClick = onFamily)
        // 차량 연결 점검(Pleos 빌드): 정차 중이거나 동행 전에만
        if (state.diagAvailable && (state.parked || !state.accompanying)) Pill("차량 연결 점검", onClick = actions::openDiagnostics)
    }
}

@Composable
private fun Pill(text: String, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = LocalMinimumInteractiveComponentSize.current).clip(RoundedCornerShape(14.dp)).background(if (primary) P.blue else P.panelHi)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1) }
}

@Composable
private fun DemoRow(state: UiState, actions: CopilotActions) {
    // 창이 좁아져도 모두 닿도록 옆으로 밀어 볼 수 있게 한다.
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallPill("차선 변경", actions::laneChange)
        SmallPill("급제동", actions::hardBrake)
        SmallPill(if (state.passengerPresent) "동승자 하차" else "동승자 탑승", actions::togglePassenger)
        SmallPill("차량 졸음 경고", actions::vehicleDrowsinessWarning)
        SmallPill("차로 흔들림", actions::weave)
        SmallPill(if (state.laneAssist) "차로 유지 보조 끄기" else "차로 유지 보조 켜기", actions::toggleLaneAssist)
        SmallPill("차로 이탈 개입", actions::laneIntervention)
        SmallPill("내기 순환 켜기·끄기", actions::toggleRecirculation)
        SmallPill(if (state.musicPlaying) "음악 끄기" else "음악 켜기", actions::toggleMusic)
        SmallPill("곡 넘기기", actions::skipTrack)
        SmallPill(if (state.inCall) "전화 끊기" else "전화 받기", actions::togglePhoneCall)
        SmallPill("휴식 정차", actions::parkForRest)
        SmallPill("다시 출발", actions::resumeDriving)
        // 공개 API 대기 중인 차량 신호(핸들 파지 감지·실내 카메라). 뒤에 두어 기존 버튼 위치를 바꾸지 않는다.
        SmallPill("핸들 놓음 경고", actions::handsOffWarning)
        SmallPill("카메라 졸음 경고", actions::cameraDrowsiness)
    }
}

@Composable
private fun SmallPill(text: String, onClick: () -> Unit) {
    // 보이는 테두리는 작게 두고, 누르는 영역은 48dp 를 보장한다.
    val touch = LocalMinimumInteractiveComponentSize.current
    Box(Modifier.clickable(role = Role.Button, onClick = onClick).sizeIn(minWidth = touch, minHeight = touch), contentAlignment = Alignment.Center) {
        Text(
            text, fontSize = 12.sp, color = P.sub, maxLines = 1,
            modifier = Modifier.border(1.dp, P.stroke, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

private fun statusText(state: UiState): String = when {
    state.napping -> "파워냅 중"
    state.parked -> "정차"
    state.inCall -> "통화 중 · 대화 멈춤"
    state.passengerPresent -> "동승자 탑승 · 대화 멈춤"
    state.quietReason != null -> "조용히 · ${state.quietReason}"
    state.quietMode -> "조용히 모드 · 감지는 계속"
    state.weaving -> "조향 흔들림 · 곧 확인"
    state.listening -> "대답 기다리는 중"
    state.waitingFor != null -> state.waitingFor
    state.speaking -> "말하는 중"
    state.accompanying -> "동행 중"
    else -> "대기"
}

@Composable
private fun Stat(label: String, value: String, color: Color = P.text) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 15.sp, color = P.sub, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(12.dp))
        // 값이 길면 값 쪽에서 줄을 바꾼다(라벨이 글자 단위로 꺾이지 않게).
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = color, textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    }
}

/** 정차하거나 동행이 끝나면 보여 주는 "오늘의 운행". */
@Composable
private fun TripSummaryCard(rows: List<Pair<String, String>>) {
    Column(
        Modifier.fillMaxWidth().clip(TileShape).background(GlassBrush).border(1.dp, P.stroke, TileShape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("오늘의 운행", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        rows.forEach { (label, value) -> Stat(label, value) }
    }
}

/**
 * 차량 연결 점검: 위는 신호(지금 값 · 받는 중 ✓ / 소식 없음 ✗), 아래는 제어(누르면 시험하고 결과·오류 문구를 남긴다).
 * 에뮬레이터에서 신호를 바꿔 보며 이 화면을 녹화하면 무엇이 연결됐는지 그대로 남는다.
 */
@Composable
private fun DiagnosticsDialog(diag: DiagState, actions: CopilotActions) {
    AlertDialog(
        onDismissRequest = actions::closeDiagnostics,
        title = { Text("차량 연결 점검") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("신호", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = P.sub)
                diag.signals.forEach { sig ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (sig.ok) "✓" else "✗", fontSize = 14.sp, color = if (sig.ok) P.ok else Color(0xFFFF8A70))
                        Text(sig.name, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(sig.value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                    }
                }
                Text("제어 · 눌러서 시험", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = P.sub, modifier = Modifier.padding(top = 8.dp))
                diag.controls.forEach { control ->
                    val result = diag.results[control]
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(control, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            when {
                                diag.running == control -> Text("시험 중…", fontSize = 13.sp, color = P.sub)
                                result != null -> Text(if (result.ok) "✓ 성공" else "✗ 실패", fontSize = 13.sp, color = if (result.ok) P.ok else Color(0xFFFF8A70))
                            }
                            TextButton(onClick = { actions.testControl(control) }, enabled = diag.running == null) { Text("시험") }
                        }
                        if (result != null && result.detail.isNotBlank()) Text(result.detail, fontSize = 12.sp, color = P.sub)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = actions::closeDiagnostics) { Text("닫기") } },
    )
}

/** 휴식 시작·재출발 때 문자를 받을 가족 번호. 비우고 저장하면 알림을 끈다. */
@Composable
private fun FamilyPhoneDialog(current: String?, onDone: (String?) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current.orEmpty()) }
    // 비우면 알림 끄기. 무언가 적었는데 휴대전화 번호 형식이 아니면 바로 알려 주고 저장을 막는다.
    val invalid = text.isNotBlank() && FamilyNotice.normalizePhone(text) == null
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("가족 알림 번호") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("쉬어 갈 때와 다시 출발할 때 이 번호로 문자를 보내요. 문자에는 정확한 위치 대신 휴게소 이름까지만 담겨요.", fontSize = 14.sp)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = invalid,
                    placeholder = { Text("010-0000-0000") },
                    supportingText = {
                        Text(if (invalid) "휴대전화 번호 형식이 아니에요 (예: 010-1234-5678)" else "비우고 저장하면 알림을 꺼요")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!invalid) onDone(text) }),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onDone(text) }, enabled = !invalid) { Text("저장") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("취소") } },
    )
}

/** 목소리 캐릭터 고르기. 고르면 한마디 들려준다(정차 중이거나 동행 전). */
@Composable
private fun PersonaDialog(current: Persona, onDone: (Persona?) -> Unit) {
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("오늘은 누구와 갈까요?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Persona.entries.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (p == current) P.option else Color.Transparent, RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button) { onDone(p) }.heightIn(min = LocalMinimumInteractiveComponentSize.current).padding(12.dp),
                    ) {
                        Column {
                            Text(p.label, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text(p.tagline, fontSize = 14.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(null) }) { Text("닫기") } },
    )
}

private val PreviewActions = object : CopilotActions {
    override fun startNow() = Unit
    override fun requestStory() = Unit
    override fun laneChange() = Unit
    override fun hardBrake() = Unit
    override fun togglePassenger() = Unit
    override fun vehicleDrowsinessWarning() = Unit
    override fun weave() = Unit
    override fun parkForRest() = Unit
    override fun resumeDriving() = Unit
    override fun toggleMusic() = Unit
    override fun skipTrack() = Unit
    override fun togglePhoneCall() = Unit
    override fun setFamilyPhone(input: String) = Unit
    override fun setPersona(persona: Persona) = Unit
}

@Preview(widthDp = 520, heightDp = 760)
@Composable
private fun CopilotScreenPreview() {
    CopilotScreen(
        state = UiState(
            accompanying = true,
            stage = AlertStage.CAUTION,
            speedKph = 100,
            drivingMinutes = 142,
            baselineSec = 1.2,
            lastLatencySec = 2.8,
            latencyHistory = listOf(1.1, 1.3, 1.2, 2.8),
            musicPlaying = true,
            nowPlaying = "밤 고속도로",
            mediaHeld = AudioMode.DUCK,
            remembered = "호두과자",
            familyPhone = "01012345678",
            chat = listOf(
                ChatLine(false, "휴게소 간식 중에 뭐 제일 좋아해?", "오전 2:14"),
                ChatLine(true, "음… 호두과자?", "오전 2:14", 2.8),
                ChatLine(false, "기억해 둘게. 호두과자.", "오전 2:14"),
            ),
            restOption = RestOption("추풍령휴게소(부산방향)", 7, "화장실, 푸드코트", waypointAdded = true),
            listening = true,
            demoAvailable = true,
        ),
        actions = PreviewActions,
    )
}

@Preview(widthDp = 520, heightDp = 760)
@Composable
private fun CopilotNapPreview() {
    CopilotScreen(
        state = UiState(
            accompanying = true,
            parked = true,
            napping = true,
            napTotalMs = 900_000,
            familyPhone = "01012345678",
            familyNotified = true,
        ),
        actions = PreviewActions,
    )
}

/** 한국어 줄바꿈: 띄어쓰기(어절) 단위. */
private val KoreanLineBreak = LineBreak(LineBreak.Strategy.HighQuality, LineBreak.Strictness.Default, LineBreak.WordBreak.Phrase)
