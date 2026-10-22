package ai.yeopjari.app.data

import ai.yeopjari.core.GeoPoint
import ai.yeopjari.core.RestStop
import ai.yeopjari.core.RestStopKind
import android.content.Context
import org.json.JSONArray

/**
 * 휴식 장소 목록을 assets/rest_stops.json 에서 읽는다.
 * 지금은 시연용 샘플이며, 공공데이터포털의 고속도로 휴게소·졸음쉼터 데이터로 교체한다.
 */
object RestStopRepository {
    fun load(context: Context): List<RestStop> {
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
}
