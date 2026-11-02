package ai.yeopjari.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 앱에 넣은 한국도로공사 편의시설 현황(브랜드) 파일을 실제 휴게소 목록에 붙여 본다. */
class ServiceAreaBrandsTest {
    private val assets = File("../app/src/main/assets")
    private val stops = RestStopCsv.parse(File(assets, "service_areas.csv").readText(), RestStopKind.SERVICE_AREA).stops
    private val entries = ServiceAreaBrands.parse(File(assets, "service_area_brands.csv").readText())
    private val joined = ServiceAreaBrands.join(stops, entries)
    private fun stop(name: String, dir: String) = joined.single { it.name == name && it.direction == dir }

    @Test
    fun `출처 줄을 건너뛰고 168곳을 읽는다`() {
        assertEquals(168, entries.size)
        assertTrue(File(assets, "service_area_brands.csv").readLines().first().contains("data.go.kr/data/15043721"))
    }

    @Test
    fun `추풍령 부산방향에는 까페베네가 있다`() {
        val s = stop("추풍령휴게소", "부산방향")
        assertEquals(listOf("까페베네", "파리바게트"), s.brands)
        assertEquals("까페베네", s.cafeBrand)
    }

    @Test
    fun `금강 부산방향에는 커피 전문점이 없다(롯데리아뿐)`() {
        val s = stop("금강휴게소", "부산방향")
        assertEquals(listOf("롯데리아"), s.brands)
        assertNull(s.cafeBrand)
    }

    @Test
    fun `도넛·제과·아이스크림은 커피 전문점으로 치지 않는다`() {
        val s = RestStop("x", "가상휴게소", GeoPoint(36.0, 128.0), RestStopKind.SERVICE_AREA, brands = listOf("던킨도너츠", "파리바게트", "베스킨라빈스", "할리스"))
        assertEquals("할리스", s.cafeBrand)
        assertNull(s.copy(brands = listOf("던킨도너츠", "베스킨라빈스")).cafeBrand)
    }

    @Test
    fun `확실한 짝만 붙인다 - 붙은 수(이전 99곳·브랜드 79곳·카페 28곳)`() {
        val matched = ServiceAreaBrands.match(stops, entries).size
        assertEquals("짝지은 휴게소 수", 144, matched)
        assertEquals("브랜드가 붙은 휴게소 수(공공데이터 범위, 2025-07-08)", 114, joined.count { it.brands.isNotEmpty() })
        assertEquals("커피 전문점이 있는 휴게소 수", 43, joined.count { it.cafeBrand != null })
        // 반대 방향 휴게소에 붙지 않는다
        assertTrue(stop("추풍령휴게소", "서울방향").brands.isEmpty())
    }

    @Test
    fun `짧은 옛 이름도 하나뿐이면 같은 휴게소로 본다`() {
        assertEquals(listOf("까페베네"), stop("남성주참외휴게소", "양평방향").brands)        // "남성주" 양평
        assertEquals(listOf("로띠번", "BBQ올리브돈까스"), stop("정읍녹두장군휴게소", "천안방향").brands) // "정읍" 논산(= 천안 쪽)
    }

    @Test
    fun `이름이 여럿에 걸리면 방향이 정확히 같을 때만 붙인다`() {
        assertEquals(listOf("롯데리아", "던킨도너츠", "스태프핫도그"), stop("천안삼거리휴게소", "서울방향").brands)
        assertEquals("엔제리너스", stop("천안호두휴게소", "부산방향").cafeBrand)
        // "함평" 목포 → 함평천지(목포)에만, 함평나비에는 붙이지 않는다
        assertEquals(listOf("시젠"), stop("함평천지휴게소", "목포방향").brands)
    }

    @Test
    fun `행선지로 적힌 방향을 상행·하행으로 맞춘다`() {
        // 서천: 이 자료는 목포·시흥. 서해안선에서 목포는 하행 쪽 → 서천(서울) 상행은 시흥 쪽 줄
        assertEquals(listOf("로띠번", "BBQ올리브돈까스"), stop("서천휴게소", "서울방향").brands)
        // 안성맞춤: 음성·평택. 평택제천선에서 평택은 상행 쪽 → 안성맞춤(제천) 하행은 음성 쪽 줄
        assertEquals(listOf("로띠번", "전철우랭면", "코바코"), stop("안성맞춤휴게소", "제천방향").brands)
    }

    @Test
    fun `상행·하행 표기가 한 번뿐이라 확실하지 않으면 붙이지 않는다`() {
        // 보성녹차(영암)은 남해선에서 "영암"이 한 번만 상행으로 쓰여 방향을 믿을 수 없다
        assertTrue(stop("보성녹차휴게소", "영암방향").brands.isEmpty())
    }
}
