package ai.yeopjari.core

/**
 * 공공데이터의 휴게소·졸음쉼터는 어느 쪽으로 가는 차를 위한 곳인지 행선지로 적혀 있다
 * (휴게소 "건천(부산)", 졸음쉼터 "서울기점 + 부산종점"). 그 행선지의 대략적인 좌표.
 *
 * 맞은편 차로의 쉼터를 권하지 않으려고 쓴다: 쉼터에서 행선지를 바라보는 방향이 차의 진행 방향과 맞는지 본다.
 * 도시 중심이나 분기점 위치라 정밀하지 않아도 된다(방향만 본다). 목록에 없는 행선지는 null(기존 방식으로 판단).
 */
object Destinations {
    private val POINTS = mapOf(
        "서울" to GeoPoint(37.57, 126.98), "부산" to GeoPoint(35.18, 129.08), "서부산" to GeoPoint(35.15, 128.99),
        "인천" to GeoPoint(37.46, 126.71), "대전" to GeoPoint(36.35, 127.38), "대구" to GeoPoint(35.87, 128.60),
        "동대구" to GeoPoint(35.88, 128.63), "달서" to GeoPoint(35.83, 128.53), "광주" to GeoPoint(35.16, 126.85),
        "광주광역시" to GeoPoint(35.16, 126.85), "울산" to GeoPoint(35.54, 129.31), "세종" to GeoPoint(36.48, 127.29),
        "순천" to GeoPoint(34.95, 127.49), "목포" to GeoPoint(34.81, 126.39), "창원" to GeoPoint(35.23, 128.68),
        "양평" to GeoPoint(37.49, 127.49), "춘천" to GeoPoint(37.88, 127.73), "강릉" to GeoPoint(37.75, 128.88),
        "천안" to GeoPoint(36.81, 127.11), "통영" to GeoPoint(34.85, 128.43), "영덕" to GeoPoint(36.42, 129.37),
        "청주" to GeoPoint(36.64, 127.49), "포항" to GeoPoint(36.02, 129.34), "제천" to GeoPoint(37.13, 128.19),
        "평택" to GeoPoint(36.99, 127.11), "완주" to GeoPoint(35.90, 127.16), "남이" to GeoPoint(36.57, 127.41),
        "하남" to GeoPoint(37.54, 127.21), "당진" to GeoPoint(36.89, 126.63), "삼척" to GeoPoint(37.45, 129.17),
        "속초" to GeoPoint(38.21, 128.59), "함양" to GeoPoint(35.52, 127.73), "일산" to GeoPoint(37.66, 126.77),
        "새만금" to GeoPoint(35.80, 126.55), "전주" to GeoPoint(35.82, 127.15), "논산" to GeoPoint(36.19, 127.10),
        "영암" to GeoPoint(34.80, 126.70), "공주" to GeoPoint(36.45, 127.12), "서천" to GeoPoint(36.08, 126.69),
        "익산" to GeoPoint(35.95, 126.96), "장수" to GeoPoint(35.65, 127.52), "무안" to GeoPoint(34.99, 126.48),
        "양양" to GeoPoint(38.08, 128.62), "양앙" to GeoPoint(38.08, 128.62), "회덕" to GeoPoint(36.39, 127.43),
        "구리" to GeoPoint(37.59, 127.13), "판교" to GeoPoint(37.39, 127.11), "고창" to GeoPoint(35.44, 126.70),
        "담양" to GeoPoint(35.32, 126.99), "포천" to GeoPoint(37.89, 127.20), "산내" to GeoPoint(36.27, 127.47),
        "언양" to GeoPoint(35.56, 129.12), "산인" to GeoPoint(35.30, 128.43), "기장" to GeoPoint(35.24, 129.22),
        "파주" to GeoPoint(37.76, 126.78), "양주" to GeoPoint(37.79, 127.05),
    )

    fun point(name: String?): GeoPoint? = name?.let { POINTS[it.trim().removeSuffix("방향").trim()] }

    /** 휴게소 이름 괄호 안의 행선지("건천(부산)" → "부산"). 괄호가 없으면 null. */
    fun fromServiceAreaName(name: String): String? =
        Regex("""\(([^)]+)\)""").find(name)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** 졸음쉼터 방향 표기의 종점("서울기점 + 부산종점" → "부산"). 형식이 다르면 null. */
    fun fromShelterDirection(direction: String?): String? =
        direction?.let { Regex("""([^\s+]+)종점""").find(it)?.groupValues?.get(1) }
}
