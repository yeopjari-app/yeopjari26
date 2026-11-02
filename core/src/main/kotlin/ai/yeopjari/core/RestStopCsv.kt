package ai.yeopjari.core

/**
 * 공공데이터포털 표준데이터 CSV(전국휴게소정보표준데이터, 전국졸음쉼터표준데이터)를 읽는다.
 *
 * 해마다 열 순서가 바뀔 수 있어 열은 이름으로 찾는다. 이름·위도·경도가 없으면 읽지 않고 [Result.missing] 에 남긴다.
 * 편의시설은 이름이 "유무"·"여부"·"수"로 끝나는 열만 본다("주유소전화번호" 같은 열을 잘못 읽지 않도록).
 * 좌표가 비었거나 국내 범위를 벗어난 줄은 건너뛰고 [Result.skipped] 로 센다.
 *
 * 이름은 소리 내어 읽기 좋게 고친다: 휴게소 "건천(부산)" → "건천휴게소", 졸음쉼터 "오창" → "오창 졸음쉼터".
 * 괄호나 "○○기점 + ○○종점" 에 적힌 행선지는 [RestStop.direction]("부산방향")과 [RestStop.towards] 로 옮긴다.
 */
object RestStopCsv {
    data class Result(val stops: List<RestStop>, val skipped: Int, val missing: List<String>)

    private val NAME = listOf("휴게소명", "졸음쉼터명", "시설명", "명칭")
    private val LAT = listOf("위도")
    private val LON = listOf("경도")
    private val ROUTE = listOf("도로노선명", "노선명")
    private val DIRECTION = listOf("도로노선방향", "방향")
    private val PARKING = listOf("주차면수", "주차가능대수")
    private val FOOD = listOf("휴게소대표음식명", "대표음식명", "대표음식")
    private val AMENITY_WORDS = listOf(
        "화장실" to Amenity.TOILET, "편의점" to Amenity.CONVENIENCE, "매점" to Amenity.CONVENIENCE,
        "음식점" to Amenity.FOOD, "식당" to Amenity.FOOD, "전기차" to Amenity.EV_CHARGER,
        "LPG" to Amenity.LPG, "주유소" to Amenity.FUEL, "약국" to Amenity.PHARMACY, "수유실" to Amenity.NURSING_ROOM,
    )

    fun parse(text: String, kind: RestStopKind): Result {
        val rows = rows(text.removePrefix("﻿"))
        if (rows.isEmpty()) return Result(emptyList(), 0, listOf("헤더"))
        val header = rows.first().map { it.trim() }
        fun col(names: List<String>) = names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } }
        val name = col(NAME)
        val lat = col(LAT)
        val lon = col(LON)
        val missing = listOfNotNull("이름".takeIf { name == null }, "위도".takeIf { lat == null }, "경도".takeIf { lon == null })
        if (missing.isNotEmpty()) return Result(emptyList(), rows.size - 1, missing)
        val route = col(ROUTE)
        val direction = col(DIRECTION)
        val parking = col(PARKING)
        val food = col(FOOD)
        val amenityCols = header.withIndex().mapNotNull { (i, h) ->
            if (!(h.endsWith("유무") || h.endsWith("여부") || h.endsWith("수"))) return@mapNotNull null
            AMENITY_WORDS.firstOrNull { (word, _) -> h.contains(word) }?.let { i to it.second }
        }
        var skipped = 0
        val stops = rows.drop(1).mapIndexedNotNull { i, r ->
            val cell = { c: Int? -> c?.let { r.getOrNull(it)?.trim() }?.takeIf { it.isNotEmpty() } }
            val n = cell(name)
            val la = cell(lat)?.toDoubleOrNull()
            val lo = cell(lon)?.toDoubleOrNull()
            if (n == null || la == null || lo == null || la !in 33.0..38.7 || lo !in 124.5..131.9) {
                skipped++
                return@mapIndexedNotNull null
            }
            val rawDirection = cell(direction)
            val dest = if (kind == RestStopKind.SERVICE_AREA) Destinations.fromServiceAreaName(n) else Destinations.fromShelterDirection(rawDirection)
            RestStop(
                id = "${if (kind == RestStopKind.SERVICE_AREA) "sa" else "ds"}-${i + 1}",
                name = spokenName(n, kind),
                point = GeoPoint(la, lo),
                kind = kind,
                route = cell(route),
                direction = dest?.let { "${it}방향" } ?: rawDirection,
                towards = Destinations.point(dest),
                amenities = amenityCols.filter { (c, _) -> truthy(cell(c)) }.map { it.second }.toSet(),
                parkingSpaces = cell(parking)?.toDoubleOrNull()?.toInt(),
                signatureFood = cell(food),
                bound = rawDirection.takeIf { kind == RestStopKind.SERVICE_AREA },
            )
        }
        return Result(stops, skipped, emptyList())
    }

    private fun spokenName(raw: String, kind: RestStopKind): String {
        val base = baseName(raw)
        return when (kind) {
            RestStopKind.SERVICE_AREA -> if (base.endsWith("휴게소")) base else "${base}휴게소"
            RestStopKind.DROWSY_SHELTER -> if (base.endsWith("쉼터")) base else "$base 졸음쉼터"
        }
    }

    /** "Y", "있음", "O", "유", 또는 0보다 큰 숫자면 있음. */
    private fun truthy(v: String?): Boolean {
        if (v == null) return false
        if (v.uppercase() in setOf("Y", "YES", "O", "있음", "유", "가능", "TRUE")) return true
        return (v.toDoubleOrNull() ?: 0.0) > 0
    }

    /** 따옴표 안의 쉼표·줄바꿈을 지키는 CSV 줄 나누기. */
    internal fun rows(text: String): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row.add(cell.toString()); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row.add(cell.toString()); cell.clear()
                    if (row.any { it.isNotBlank() }) out.add(row)
                    row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        row.add(cell.toString())
        if (row.any { it.isNotBlank() }) out.add(row)
        return out
    }
}
