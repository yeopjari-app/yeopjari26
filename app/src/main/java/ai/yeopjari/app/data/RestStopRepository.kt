package ai.yeopjari.app.data

import ai.yeopjari.core.GeoPoint
import ai.yeopjari.core.RestStop
import ai.yeopjari.core.RestStopCsv
import ai.yeopjari.core.RestStopKind
import ai.yeopjari.core.ServiceAreaBrands
import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 휴식 장소 목록.
 *
 * assets 에 공공데이터포털 표준데이터 CSV(service_areas.csv = 전국휴게소정보표준데이터,
 * drowsy_shelters.csv = 전국졸음쉼터표준데이터)가 있으면 그것을 읽고, 없으면 시연용 샘플 rest_stops.json 을 읽는다.
 * 포털 CSV는 보통 EUC-KR(CP949)이라, UTF-8 로 읽히지 않으면 CP949 로 다시 읽는다. [samplesOnly] 면 샘플만 읽는다.
 * service_area_brands.csv(한국도로공사_노선별 방향별 휴게소 편의시설 현황, 2025-07-08)가 있으면 입점 브랜드를 휴게소에 붙인다.
 */
object RestStopRepository {
    private val FILES = listOf("service_areas.csv" to RestStopKind.SERVICE_AREA, "drowsy_shelters.csv" to RestStopKind.DROWSY_SHELTER)

    /** 시연용 샘플(작은 JSON). 공공데이터 CSV 를 읽는 동안이나 읽지 못했을 때 쓰는 대체 목록이다. */
    fun loadSamples(context: Context): List<RestStop> = runCatching { samples(context) }.getOrElse {
        Log.w(TAG, "샘플 휴식 장소를 읽지 못함", it)
        emptyList()
    }

    /** [load] 를 하되, 파일이 깨졌거나 읽다 오류가 나면 앱을 멈추지 않고 [fallback] 을 돌려준다. */
    fun loadOrFallback(context: Context, samplesOnly: Boolean, fallback: List<RestStop>): List<RestStop> =
        runCatching { load(context, samplesOnly) }.getOrElse {
            Log.w(TAG, "휴식 장소 자료를 읽지 못해 대체 목록을 씀", it)
            fallback
        }

    fun load(context: Context, samplesOnly: Boolean = false): List<RestStop> {
        if (samplesOnly) return samples(context)
        val names = context.assets.list("")?.toSet().orEmpty()
        val fromCsv = FILES.filter { it.first in names }.flatMap { (file, kind) ->
            val result = RestStopCsv.parse(decode(context.assets.open(file).use { it.readBytes() }), kind)
            if (result.missing.isNotEmpty()) Log.w(TAG, "$file: 열을 찾지 못함 ${result.missing}")
            Log.i(TAG, "$file: ${result.stops.size}곳 읽음, ${result.skipped}줄 건너뜀")
            result.stops
        }
        if (fromCsv.isEmpty()) return samples(context)
        if (BRANDS !in names) return fromCsv
        val entries = ServiceAreaBrands.parse(decode(context.assets.open(BRANDS).use { it.readBytes() }))
        return ServiceAreaBrands.join(fromCsv, entries).also { joined ->
            Log.i(TAG, "$BRANDS: ${entries.size}줄, 휴게소 ${joined.count { it.brands.isNotEmpty() }}곳에 브랜드 연결")
        }
    }

    private fun decode(bytes: ByteArray): String {
        val utf8 = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            utf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            val cp949 = listOf("x-windows-949", "MS949", "EUC-KR").firstNotNullOf { runCatching { Charset.forName(it) }.getOrNull() }
            String(bytes, cp949)
        }
    }

    private fun samples(context: Context): List<RestStop> {
        val json = context.assets.open("rest_stops.json").bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            RestStop(
                id = o.getString("id"),
                name = o.getString("name"),
                point = GeoPoint(o.getDouble("lat"), o.getDouble("lon")),
                kind = RestStopKind.valueOf(o.getString("kind")),
            )
        }
    }

    private const val TAG = "RestStops"
    private const val BRANDS = "service_area_brands.csv"
}
