package com.truelockmc.streambert

/** Same spatial rules as src/tv/tvFocus.js. Key codes are Android KeyEvent values. */
object TvFocus {
    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun directionOf(keyCode: Int): String? = when (keyCode) {
        21 -> "left"
        22 -> "right"
        19 -> "up"
        20 -> "down"
        else -> null
    }

    fun isActivate(keyCode: Int): Boolean = keyCode == 23 || keyCode == 66 || keyCode == 160

    fun pick(rects: List<Rect>, current: Int, direction: String): Int {
        if (rects.isEmpty()) return -1
        if (current < 0 || current >= rects.size) return 0
        val cur = rects[current]
        val cx = (cur.left + cur.right) / 2f
        val cy = (cur.top + cur.bottom) / 2f
        var best = -1
        var bestScore = Float.POSITIVE_INFINITY
        for (i in rects.indices) {
            if (i == current) continue
            val box = rects[i]
            val dx = (box.left + box.right) / 2f - cx
            val dy = (box.top + box.bottom) / 2f - cy
            if (direction == "left" && dx >= -4f) continue
            if (direction == "right" && dx <= 4f) continue
            if (direction == "up" && dy >= -4f) continue
            if (direction == "down" && dy <= 4f) continue
            val horizontal = direction == "left" || direction == "right"
            val score = (if (horizontal) kotlin.math.abs(dx) else kotlin.math.abs(dy)) +
                (if (horizontal) kotlin.math.abs(dy) else kotlin.math.abs(dx)) * 2.4f
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }
}
