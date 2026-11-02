package ai.yeopjari.core

/**
 * 옆자리의 목소리 캐릭터. 고르면 말투(TTS 높낮이·빠르기)와 소개·깨우기 대사가 바뀐다.
 * 대답 속도 판정과 개입 순서는 캐릭터와 상관없이 같다. 무응답 깨우기에서는 고른 캐릭터와 상관없이
 * 잠깐 [DRILL] 목소리로 바꿔 강하게 깨운 뒤 원래 캐릭터로 돌아온다.
 */
enum class Persona(val label: String, val tagline: String, val pitch: Float, val rate: Float) {
    FRIEND("다정한 친구", "편하게, 곁에서", 1.05f, 1.0f),
    UNCLE("경상도 아재", "투박해도 든든하게", 0.9f, 0.95f),
    DRILL("열혈 교관", "짧고 단호하게", 0.85f, 1.12f),
    JOKER("장난꾸러기", "놀리고, 웃기고, 안 재우고", 1.18f, 1.05f);

    /** 고를 때 들려주는 한마디. */
    val intro: String
        get() = when (this) {
            FRIEND -> "오늘도 같이 가! 피곤하면 내가 옆에서 챙길게."
            UNCLE -> "어이, 눈꺼풀 무겁나? 내 옆에 있다 아이가. 졸리모 바로 말해래이."
            DRILL -> "운전자 확인! 허리 펴고, 눈 크게! 대답은 크게!"
            JOKER -> "졸기만 해 봐. 네 플레이리스트, 싹 다 동요로 바꿔 놓는다? 차 탈 때마다 '상어 가족' 무한 반복! 오, 눈 번쩍 떴네!"
        }

    /** 무응답 깨우기 1단계 대사. */
    val wakeLine: String
        get() = when (this) {
            DRILL -> "운전자! 눈 뜨십시오! 지금 바로 쉬셔야 합니다!"
            else -> "일어나세요! 지금 바로 쉬어야 해요."
        }

    companion object {
        fun parse(name: String?): Persona = entries.firstOrNull { it.name == name } ?: FRIEND
    }
}
