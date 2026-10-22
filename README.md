# 옆자리 (Yeopjari)

**장거리·야간 운전자와 대화하며, 평소보다 반응이 느려지는 순간을 알아채 "응" 한마디로 가장 가까운 졸음쉼터·휴게소까지 안내하는 차량용 AI 음성 동반자**

- 반응 속도가 곧 신호: 주행 시간만 세지 않고, 짧은 음성 질문에 대한 응답 속도·정확도로 각성 상태를 판단합니다.
- 나와 비교: 처음 세 번의 응답으로 운전자 본인의 평소 응답 속도를 정하고, 그보다 느려질 때만 개입합니다.
- 경고가 아닌 행동: 위험 단계에서는 가장 가까운 휴식 장소를 제안하고, 동의하면 경유지로 추가합니다.

> 옆자리는 각성 유지를 돕는 보조 서비스이며 의료적 진단을 하지 않습니다.

## APK 파일 위치

- `apk/팀명_Yeopjari_release.apk` (제출 전 팀명으로 바꿔 업로드)
- 용량이 GitHub 제한을 넘으면 GitHub Releases 에 올리고 해당 링크를 여기에 적습니다.

## 실행 방법

### 1) 시연 빌드 (`sim`) — 일반 Android 에뮬레이터

Pleos SDK 없이 가상 고속도로 주행(실제 1초 = 가상 1분)으로 전체 흐름을 시연합니다.

```bash
./gradlew :app:assembleSimRelease
adb install app/build/outputs/apk/sim/release/app-sim-release.apk
```

1. 앱을 열고 마이크 권한을 허용합니다.
2. 약 30초 뒤(가상 고속 주행 30분) "옆자리가 함께할게요"와 함께 동행이 시작됩니다.
3. 20초마다 질문이 나옵니다. 처음 세 번은 평소처럼 바로 답해 기준 응답 속도를 만듭니다.
4. 이후 일부러 늦게 답하거나 틀리게 답하면 **주의**(환기 제안), 답하지 않으면 **위험**(휴식 장소 제안)으로 바뀝니다.
5. 위험 단계에서 "응"이라고 답하면 경유지 추가가 기록됩니다. **휴식 정차** 버튼을 누르고 10초(가상 10분) 기다리면 휴식이 인정되고, **다시 출발**을 누르면 컨디션을 확인합니다.

### 2) Pleos 빌드 (`pleos`) — Pleos Connect 에뮬레이터

```bash
./gradlew :app:assemblePleosRelease
adb install app/build/outputs/apk/pleos/release/app-pleos-release.apk
```

Vehicle SDK 의 차속·기어 리스너 연결이 끝나야 실제 주행 데이터로 동작합니다. 아래 **개발 현황**을 참고하세요.

### 릴리스 서명

루트에 `keystore.properties`(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`)가 있으면 그 키로 서명하고,
없으면 디버그 키로 서명해 바로 설치할 수 있습니다. 키 파일과 `keystore.properties` 는 저장소에 올리지 않습니다.

## 테스트 환경 및 필요한 설정

| 항목 | 내용 |
|---|---|
| 개발 도구 | Android Studio, JDK 17 이상 |
| Android | minSdk 28, target/compileSdk 35 |
| 빌드 | Gradle 8.10.2(wrapper), AGP 8.8.2, Kotlin 2.0.0 |
| Pleos | Vehicle SDK 2.3.2 (`ai.pleos.playground:Vehicle`, `pleos` 빌드만) |
| 시연 빌드 에뮬레이터 | 일반 Android 에뮬레이터, 한국어 음성 인식·TTS 가 가능한 이미지(Google 앱 포함) |
| Pleos 빌드 에뮬레이터 | Pleos Connect Emulator (일반 AVD 에서는 Vehicle SDK 가 동작하지 않음) |
| 권한 | 마이크(음성 체크), 위치(휴식 장소 거리 계산) |

단위 테스트(각성 판정·주행 상태·질문·거리 계산):

```bash
./gradlew :core:test
```

## 구조

```text
core/   순수 Kotlin 로직 (단위 테스트 포함)
  AlertnessEngine      응답 지연·정답을 본인 기준선과 비교해 정상/주의/위험 판정
  DriveSessionTracker  고속 주행 누적 → 동행 시작, P단 10분 → 휴식 인정
  QuestionBank         산수·대화형 질문, 한국어 숫자 답 인식
  RestStops            가장 가까운 휴식 장소 계산
app/    Android 앱 (Jetpack Compose)
  CopilotViewModel     차량 상태 → 음성 체크 → 단계별 개입 흐름
  voice/AndroidVoiceIO Android TTS·음성 인식 (Pleos TTS/STT SDK 로 교체 가능)
  src/sim              가상 주행·시연 조작
  src/pleos            Vehicle SDK 연동
```

## 개발 현황

| 기능 | 상태 |
|---|---|
| 각성 판정·주행 상태·질문·휴식 장소 계산 (core) | 완료, 단위 테스트 15개 |
| 음성 질문·응답 지연 측정 (Android TTS·음성 인식) | 구현 |
| 시연 빌드: 가상 주행·휴식 정차 | 구현 |
| Pleos 차속·기어 리스너 | `TODO(pleos)` — API 참조에서 타입 API 이름 확인 후 연결 |
| HVAC 외기 유입 | `TODO(pleos)` — 제어 가능 여부 확인 후 연결, 불가 시 창문 권유 |
| NaviHelper 경유지 추가 | `TODO(pleos)` — 연결 전에는 음성 안내만 |
| 휴식 장소 데이터 | 시연용 샘플 4곳 → 공공데이터포털 고속도로 휴게소·졸음쉼터 데이터로 교체 예정 |
