package ai.yeopjari.app.ui

import ai.yeopjari.app.UiState
import ai.yeopjari.app.label
import ai.yeopjari.core.AlertStage
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val StageColors = mapOf(
    AlertStage.NORMAL to Color(0xFF2E7D32),
    AlertStage.CAUTION to Color(0xFFF9A825),
    AlertStage.DANGER to Color(0xFFC62828),
)

/**
 * 주행 중 1초 안에 읽히도록 단계 색과 한 단어만 크게 보여 준다.
 * 상세 정보는 오른쪽에 작게 두고, 시연 조작 버튼은 가상 주행 빌드에서만 보인다.
 */
@Composable
fun CopilotScreen(
    state: UiState,
    onParkForRest: () -> Unit,
    onResumeDriving: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF111418)) {
        Row(modifier = Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            StageCard(state, Modifier.weight(1f).fillMaxHeight())
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("옆자리", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Info("상태", if (state.parked) "정차" else if (state.accompanying) "동행 중" else "대기")
                Info("차속", "${state.speedKph} km/h")
                Info("연속 주행", "${state.drivingMinutes}분")
                Info("평소 응답 속도", "%.1f초".format(state.baselineSec))
                if (!state.canListen) {
                    Text("음성 인식을 사용할 수 없습니다", color = Color(0xFFFFB4AB))
                }
                if (state.lastExchange.isNotEmpty()) {
                    Text(state.lastExchange, color = Color(0xFFCFD8DC), fontSize = 16.sp)
                }
                state.log.forEach { Text("· $it", color = Color(0xFF90A4AE), fontSize = 14.sp) }
                if (state.demoAvailable) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onParkForRest) { Text("휴식 정차") }
                        OutlinedButton(onClick = onResumeDriving) { Text("다시 출발") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StageCard(state: UiState, modifier: Modifier) {
    val color = if (state.accompanying) StageColors.getValue(state.stage) else Color(0xFF37474F)
    Box(
        modifier = modifier.background(color, RoundedCornerShape(32.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (state.accompanying) state.stage.label else "대기",
                fontSize = 96.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = if (state.accompanying) "옆자리가 함께하는 중" else "고속 주행이 이어지면 함께해요",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFF90A4AE), fontSize = 18.sp)
        Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun CopilotScreenPreview() {
    CopilotScreen(
        state = UiState(
            accompanying = true,
            stage = AlertStage.CAUTION,
            speedKph = 100,
            drivingMinutes = 142,
            baselineSec = 1.2,
            lastExchange = "Q. 7 더하기 5 는 얼마일까요?\nA. 13 (2.8초)",
            log = listOf("체크: 2.8초 → 주의", "동행 시작 (고속 주행 30분)"),
            demoAvailable = true,
        ),
        onParkForRest = {},
        onResumeDriving = {},
    )
}
