package com.example.meomunjari.pleos

import android.util.Log
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

/**
 * Pleos SDK(Vehicle·FusedLocation·NaviHelper)와 AAOS 속성 호출의 입력·출력을 한 태그로 남긴다.
 *
 * `adb logcat -s PleosSdk` 로 본다.
 * - `→ #12 Seat.moveSeatBackrestAngle(area=SeatArea(ROW_1_LEFT), value=FORWARD)` 호출(입력)
 * - `← #12 Seat.moveSeatBackrestAngle = [SeatArea(ROW_1_LEFT), FORWARD]` 성공 콜백(출력)
 * - `⇠ #7 HVAC.registerHvacPowerState = [true]` 구독한 값이 바뀔 때마다 들어오는 이벤트
 * - `✗ #12 Seat.moveSeatBackrestAngle: ...` 실패
 *
 * 같은 #번호가 한 호출의 입력과 결과를 잇는다. JVM 단위 테스트에서는 android.util.Log가 없어 조용히 넘어간다.
 */
internal object PleosLog {
    const val TAG = "PleosSdk"
    private const val MAX_LEN = 1_500
    private val seq = AtomicInteger()

    fun nextId(): Int = seq.incrementAndGet()

    fun call(id: Int, api: String, args: String) = i("→ #$id $api($args)")
    fun result(id: Int, api: String, values: List<Any?>) = i("← #$id $api = ${fmt(values)}")
    fun event(id: Int, api: String, values: List<Any?>) = d("⇠ #$id $api = ${fmt(values)}")
    fun error(id: Int, api: String, t: Throwable) = w("✗ #$id $api: ${t.javaClass.simpleName}: ${t.message}", t)

    /** 번호 없이 한 줄로 남길 때 (위치 콜백, 내비 이벤트 등). */
    fun info(message: String) = i(message)
    fun debug(message: String) = d(message)
    fun warn(message: String, t: Throwable? = null) = w(message, t)

    /** 호출 인자 표시. 콜백은 λ, 리스너는 listener로 줄인다. */
    fun arg(value: Any?): String = when {
        value is Function<*> -> "λ"
        value != null && Proxy.isProxyClass(value.javaClass) -> "listener"
        else -> fmt(value)
    }

    fun fmt(value: Any?, depth: Int = 0): String {
        val s = when (value) {
            null -> "null"
            is String -> "\"$value\""
            is Number, is Boolean, is Char -> value.toString()
            is Enum<*> -> value.name
            is Throwable -> "${value.javaClass.simpleName}(${value.message})"
            is Array<*> -> value.joinToString(", ", "[", "]") { fmt(it, depth + 1) }
            is IntArray -> value.contentToString()
            is FloatArray -> value.contentToString()
            is Collection<*> -> value.joinToString(", ", "[", "]") { fmt(it, depth + 1) }
            is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { "${fmt(it.key, depth + 1)}=${fmt(it.value, depth + 1)}" }
            else -> reflect(value, depth)
        }
        return if (s.length > MAX_LEN) s.take(MAX_LEN) + "…(${s.length}자)" else s
    }

    /** SDK 모델 객체는 toString이 없는 경우가 많아 속성을 직접 펼친다. */
    private fun reflect(value: Any, depth: Int): String {
        val plain = value.toString()
        val looksDefault = plain.contains('@') && plain.startsWith(value.javaClass.name)
        if (!looksDefault || depth >= 3) return plain
        val props = runCatching {
            value::class.memberProperties.joinToString(", ") { p ->
                p.isAccessible = true
                "${p.name}=${fmt(runCatching { p.getter.call(value) }.getOrNull(), depth + 1)}"
            }
        }.getOrNull() ?: return plain
        return "${value.javaClass.simpleName}($props)"
    }

    private fun i(m: String) { runCatching { Log.i(TAG, m) } }
    private fun d(m: String) { runCatching { Log.d(TAG, m) } }
    private fun w(m: String, t: Throwable?) { runCatching { if (t != null) Log.w(TAG, m, t) else Log.w(TAG, m) } }
}
