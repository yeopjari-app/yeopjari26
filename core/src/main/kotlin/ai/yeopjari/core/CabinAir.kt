package ai.yeopjari.core

/**
 * 내기 순환(외기 차단)이 오래 이어지는지 본다.
 *
 * 내기 순환으로 달리면 실내 이산화탄소가 빠르게 쌓인다(두 사람이 타면 15~20분 만에 2,500ppm 넘게 오른다는 측정이 있다).
 * 높은 이산화탄소가 졸음운전을 직접 일으킨다는 근거는 약하므로 "졸음 원인"이 아니라 "환기 습관"으로 다룬다:
 * [limitMs](새벽 0~6시에는 [nightLimitMs]) 넘게 이어지면 한 번만 바깥 공기로 바꾸자고 알린다.
 * 운전자가 다시 내기 순환으로 돌리면 그때부터 다시 잰다. 터널·미세먼지 때문에 일부러 켠 짧은 순환은 건드리지 않는다.
 */
class CabinAirTracker(
    private val limitMs: Long = 30 * 60_000L,
    private val nightLimitMs: Long = 20 * 60_000L,
) {
    private var since: Long? = null
    private var fired = false

    var recirculating = false
        private set

    /** 표본 하나를 넣는다. 바깥 공기로 바꿀 때가 된 순간이면 true(한 번만). */
    fun update(snapshot: VehicleSnapshot, hour: Int): Boolean {
        recirculating = snapshot.recirculationOn
        if (!recirculating) {
            since = null
            fired = false
            return false
        }
        if (snapshot.gear == Gear.PARK) {
            since = null  // 정차해 문을 열면 공기가 바뀐다. 다시 달리기 시작할 때부터 잰다.
            return false
        }
        val start = since ?: snapshot.timeMs.also { since = it }
        if (fired) return false
        val limit = if (hour in 0..5) nightLimitMs else limitMs
        if (snapshot.timeMs - start < limit) return false
        fired = true
        return true
    }

    /** 내기 순환이 이어진 분. */
    fun minutes(nowMs: Long): Long = since?.let { (nowMs - it) / 60_000 } ?: 0
}
