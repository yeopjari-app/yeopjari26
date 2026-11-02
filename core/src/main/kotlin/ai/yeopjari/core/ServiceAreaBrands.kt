package ai.yeopjari.core

/**
 * 휴게소 입점 브랜드 매장. 한국도로공사_노선별 방향별 휴게소 편의시설 현황(LOD)
 * (공공데이터포털 https://www.data.go.kr/data/15043721/fileData.do, 데이터 기준일 2025-07-08)을 그대로 옮긴
 * service_area_brands.csv(휴게소명,방향,브랜드,편의시설,대표음식 · 여러 값은 | 로 구분)를 읽어 휴게소 목록에 붙인다.
 *
 * 표준데이터의 "추풍령(부산)"은 이 자료의 이름 "추풍령"·방향 "부산"과 맞춘다. 엉뚱한 쪽 휴게소의 가게를 말하지 않도록
 * 아래 규칙으로 확실한 곳만 붙인다([join]).
 * 1. 이름: 같으면 붙인다. 이 자료가 옛 이름·짧은 이름을 쓰면("남성주" ↔ "남성주참외", "정읍" ↔ "정읍녹두장군")
 *    한쪽이 다른 쪽으로 시작하고 그런 휴게소가 하나뿐일 때만 같은 곳으로 본다. 둘 이상이면("천안" ↔ "천안삼거리"·"천안호두")
 *    방향까지 정확히 같을 때만 붙인다. 괄호가 붙은 이름("하남(만남)")은 이름이 정확히 같을 때만 쓴다.
 * 2. 방향: 같으면 붙인다. 이 자료는 방향을 행선지로 적고(예: 서해안선 "시흥"), 표준데이터는 괄호 속 행선지와 상행·하행을
 *    함께 적는다(예: "서천(서울)", 상행). 같은 노선의 표준데이터 행들에서 행선지가 상행·하행 중 어느 쪽에 쓰였는지
 *    세어(두 번 이상 같은 쪽에 쓰인 것만) 방향을 맞춘다: 그 휴게소의 두 방향 중 하나가 반대쪽 행선지이면 나머지가 이쪽이다
 *    ("서천" 목포·시흥 → 목포는 서해안선 하행 → 서천(서울) 상행은 시흥).
 * 3. 양방향 휴게소는 이 자료에 한 줄만 있을 때 붙인다.
 * 어느 규칙으로도 확실하지 않으면 붙이지 않는다(그 휴게소에서는 가게 이름을 말하지 않는다).
 */
object ServiceAreaBrands {
    /** 커피 전문점으로 보는 브랜드. 도넛·제과·아이스크림 가게는 넣지 않는다. */
    val CAFES = listOf("엔제리너스", "까페베네", "할리스", "까페띠아모")

    data class Entry(val name: String, val direction: String, val brands: List<String>, val amenities: List<String>, val food: String?)

    /** "#" 로 시작하는 줄(출처 설명)은 건너뛴다. */
    fun parse(text: String): List<Entry> {
        val lines = text.removePrefix("﻿").lineSequence().filterNot { it.startsWith("#") }.joinToString("\n")
        val rows = RestStopCsv.rows(lines)
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim() }
        fun col(name: String) = header.indexOf(name)
        val (n, d, b, a, f) = listOf(col("휴게소명"), col("방향"), col("브랜드"), col("편의시설"), col("대표음식"))
        if (n < 0 || d < 0 || b < 0) return emptyList()
        fun split(v: String?) = v.orEmpty().split("|").map { it.trim() }.filter { it.isNotEmpty() }
        return rows.drop(1).mapNotNull { r ->
            val name = r.getOrNull(n)?.trim().orEmpty()
            if (name.isEmpty()) null
            else Entry(name, r.getOrNull(d)?.trim().orEmpty(), split(r.getOrNull(b)), split(r.getOrNull(a)), r.getOrNull(f)?.trim()?.takeIf { it.isNotEmpty() })
        }
    }

    /** 휴게소에 입점 브랜드를 붙인다(대표음식이 비어 있으면 함께 채운다). 짝이 확실하지 않은 휴게소·졸음쉼터는 그대로 둔다. */
    fun join(stops: List<RestStop>, entries: List<Entry>): List<RestStop> {
        val picks = match(stops, entries)
        return stops.mapIndexed { i, s ->
            val e = picks[i] ?: return@mapIndexed s
            s.copy(brands = e.brands, signatureFood = s.signatureFood ?: e.food)
        }
    }

    /** [stops] 의 순번 → 짝지은 [Entry]. 규칙은 이 object 의 설명 참고. */
    internal fun match(stops: List<RestStop>, entries: List<Entry>): Map<Int, Entry> {
        val sa = stops.withIndex().filter { it.value.kind == RestStopKind.SERVICE_AREA }
        val bases = sa.map { key(it.value).first }.toSet()
        // 휴게소 이름 → (이 자료의 줄, 그 이름에 대응하는 휴게소가 하나뿐인지)
        val candidates = mutableMapOf<String, MutableList<Pair<Entry, Boolean>>>()
        for (e in entries) {
            val hasParen = '(' in e.name
            val n = e.name.replace(Regex("""\(.*\)"""), "").replace(" ", "").removeSuffix("휴게소")
            val c = when {
                n in bases -> listOf(n)
                !hasParen && n.length >= 2 -> bases.filter { it.startsWith(n) || n.startsWith(it) }
                else -> emptyList()
            }
            c.forEach { candidates.getOrPut(it) { mutableListOf() }.add(e to (c.size == 1)) }
        }
        // 노선별로 행선지가 상행·하행 중 어느 쪽에 쓰였는지 센다.
        val counts = mutableMapOf<String, MutableMap<Pair<String, String>, Int>>()
        for ((_, s) in sa) {
            val dest = destOf(s)
            val route = s.route ?: continue
            if (dest.isNotEmpty() && s.bound in BOUNDS) counts.getOrPut(route) { mutableMapOf() }.merge(dest to s.bound!!, 1, Int::plus)
        }
        val corroborated = counts.mapValues { (_, c) -> c.filterValues { it >= 2 }.keys }
        fun labels(route: String?, bound: String): Set<String> {
            val c = counts[route] ?: return emptySet()
            val other = opposite(bound)
            return c.keys.map { it.first }.filter { (c[it to bound] ?: 0) > (c[it to other] ?: 0) }.toSet()
        }
        val picks = mutableMapOf<Int, Entry>()
        repeat(3) {
            var changed = false
            for ((i, s) in sa) {
                if (i in picks) continue
                val base = key(s).first
                val dest = destOf(s)
                val c = candidates[base].orEmpty()
                val exact = c.firstOrNull { (e, _) -> dest.isNotEmpty() && e.direction == dest }?.first
                val unique = c.filter { it.second }.map { it.first }
                val bound = s.bound
                val pick = when {
                    exact != null -> exact
                    bound !in BOUNDS -> unique.singleOrNull()?.takeIf { dest.isEmpty() }
                    unique.isEmpty() || (dest to bound!!) !in corroborated[s.route].orEmpty() -> null
                    else -> {
                        val same = labels(s.route, bound)
                        val opp = labels(s.route, opposite(bound))
                        unique.filter { it.direction in same && it.direction !in opp }.singleOrNull()
                            ?: unique.takeIf { it.size == 2 }?.let { two ->
                                val (inOpp, rest) = two.partition { it.direction in opp }
                                rest.singleOrNull()?.takeIf { inOpp.size == 1 }
                            }
                    }
                }
                if (pick != null) {
                    picks[i] = pick
                    changed = true
                    // 새로 알게 된 행선지 표기("시흥" = 서해안선 상행)를 다음 차례에 쓴다.
                    if (exact == null && bound in BOUNDS && s.route != null) counts.getOrPut(s.route) { mutableMapOf() }.merge(pick.direction to bound!!, 1, Int::plus)
                }
            }
            if (!changed) return picks
        }
        return picks
    }

    private val BOUNDS = setOf("상행", "하행")

    /** 이름 괄호에 적힌 행선지("서천(서울)" → "서울"). 괄호가 없어 방향 칸에 "양방향"·"상행"이 그대로 있으면 "". */
    private fun destOf(s: RestStop): String = if (s.bound != null && s.direction == s.bound) "" else key(s).second
    private fun opposite(bound: String) = if (bound == "상행") "하행" else "상행"

    /** "추풍령휴게소" · "부산방향" → ("추풍령", "부산") */
    internal fun key(s: RestStop): Pair<String, String> =
        s.name.removeSuffix("휴게소").trim() to s.direction.orEmpty().removeSuffix("방향").trim()
}
