package ai.yeopjari.core

/**
 * 커피 냅을 권하는 말. 커피를 마시고 바로 15분 자면 깰 때쯤 카페인이 돈다(Reyner & Horne 1997).
 * - 커피를 좋아한다고 들었으면 그 기억을 먼저 꺼내고, 휴게소에 커피 전문점이 있으면([RestStop.cafeBrand]) 이름을 말한다.
 * - 모르면 "커피 드세요?"부터 묻는다. 싫다고 하면 커피 없이 15분만 권한다.
 * - 커피를 안 마신다고 했으면 커피는 꺼내지 않는다.
 * - 휴게소에 커피 전문점 자료가 없으면 "커피 한 잔 드시고"라고 권하지 않고, 차에 마실 커피가 있는지 먼저 묻는다([carryLine]).
 * 가게 이름은 공공데이터에 있을 때만 말하고, 주문·결제는 하지 않는다(Pleos 에 결제 API 가 없다).
 */
object CoffeeNap {
    const val ASK = "커피 드세요?"
    const val NAP_ONLY = "그럼 커피 없이 15분만 주무세요. 깨워 드릴게요."

    const val ASK_CARRY = "혹시 차에 마실 커피 있으세요?"

    /**
     * 바로 권할 말. 커피를 안 마시는 사람이면 [NAP_ONLY]. 먼저 물어야 하면 null:
     * 카페 자료가 없으면 [carryLine], 카페가 있는데 취향을 모르면 [ASK].
     */
    fun offer(likesCoffee: Boolean?, cafeBrand: String?): String? = when {
        likesCoffee == false -> NAP_ONLY
        cafeBrand == null -> null
        likesCoffee == true -> "아까 커피 좋아하신다고 하셨죠? ${here(cafeBrand)}$PITCH"
        else -> null
    }

    /** 카페 자료가 없을 때: 가지고 있는 커피가 있는지 묻는다. */
    fun carryLine(likesCoffee: Boolean?): String = (if (likesCoffee == true) "아까 커피 좋아하신다고 하셨죠? " else "") + ASK_CARRY

    /** [ASK_CARRY] 에 있다고 했을 때. */
    const val WITH_OWN = "그럼 그 커피 드시고 바로 15분 주무실래요? 깰 때쯤 카페인이 돌아서 더 개운해요."

    /** [ASK] 에 좋다고 했을 때. */
    fun afterYes(cafeBrand: String?): String = "${here(cafeBrand)}$PITCH"

    private const val PITCH = "커피 한 잔 드시고 바로 15분 주무실래요? 깰 때쯤 카페인이 돌아서 더 개운해요."
    private fun here(cafe: String?) = cafe?.let { "여기 $it 있어요. " } ?: ""
}
