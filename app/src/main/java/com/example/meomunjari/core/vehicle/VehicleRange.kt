package com.example.meomunjari.core.vehicle

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 앱은 창문·등받이를 0~100%로 저장하고, 차는 차종마다 다른 위치 범위(예: 창문 0~10, 등받이 0~100)를 쓴다.
 * 둘 사이를 바꾼다. 범위는 Vehicle SDK의 checkXxxCapabilities 결과(AreaBounds)에서 온다.
 */
object VehicleRange {

    /**
     * % → 차 위치. [roundUp]이면 0%가 아닌 값은 올림한다.
     * 예: 창문 범위 0~10에서 5%는 반올림하면 1이 아니라 0(닫힘)이 될 수 있어, 조금이라도 열려면 올림한다.
     */
    fun fromPercent(pct: Int, range: IntRange, roundUp: Boolean = false): Int {
        val p = pct.coerceIn(0, 100)
        val span = range.last - range.first
        val raw = range.first + p * span / 100.0
        val pos = if (roundUp && p > 0) ceil(raw).toInt() else raw.roundToInt()
        return pos.coerceIn(range.first, range.last)
    }

    /** 차 위치 → %. */
    fun toPercent(position: Int, range: IntRange): Int {
        val span = range.last - range.first
        if (span <= 0) return 0
        return ((position - range.first) * 100.0 / span).roundToInt().coerceIn(0, 100)
    }
}
