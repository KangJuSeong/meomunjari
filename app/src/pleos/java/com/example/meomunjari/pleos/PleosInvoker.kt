package com.example.meomunjari.pleos

import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.MirrorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.MirrorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.declaredMemberFunctions

/**
 * Vehicle SDK 도메인 API를 메서드 이름으로 호출한다.
 *
 * Door를 제외한 도메인은 공개 문서에서 정확한 시그니처를 확인하지 못해, 공식 샘플
 * (Pleos-Playground/pleos-sample-vehicle-app) 의 VehicleApiExplorer 와 같은 방식으로
 * 파라미터 타입을 보고 인자를 채운다. 메서드 이름은 API 레퍼런스 목록에서 확인한 것만 쓴다.
 *
 * - 콜백(FunctionN): 이름에 fail 이 들어가면 실패 콜백, 아니면 성공 콜백
 * - 리스너(ai.pleos.playground.vehicle.listener.*): 동적 Proxy
 * - Area: 힌트 → 운전석 쪽 기본값 순으로 고른다
 * - Boolean/Int/Float/Enum: value 인자로 채운다
 */
internal class PleosInvoker {

    private val listeners = mutableListOf<Any>()
    private var loggedDoors = false

    fun call(
        apiClass: KClass<*>,
        target: Any,
        method: String,
        value: Any? = null,
        areaHint: String? = null,
        onResult: (List<Any?>) -> Unit = {},
        onError: (Throwable) -> Unit = {},
    ): Boolean {
        val function = apiClass.declaredMemberFunctions
            .filter { it.name == method && it.annotations.none { a -> a is Deprecated } }
            .minByOrNull { it.parameters.size }
        if (function == null) {
            onError(NoSuchMethodException("${apiClass.simpleName}.$method"))
            return false
        }
        return try {
            val args = buildArgs(function, target, value, areaHint, onResult, onError) ?: return false
            function.callBy(args)
            true
        } catch (e: Exception) {
            val cause = (e as? InvocationTargetException)?.targetException ?: e
            Log.w(TAG, "${apiClass.simpleName}.$method failed", cause)
            onError(cause)
            false
        }
    }

    private fun buildArgs(
        function: KFunction<*>,
        target: Any,
        value: Any?,
        areaHint: String?,
        onResult: (List<Any?>) -> Unit,
        onError: (Throwable) -> Unit,
    ): Map<KParameter, Any?>? {
        val args = mutableMapOf<KParameter, Any?>()
        for (p in function.parameters) {
            if (p.kind == KParameter.Kind.INSTANCE) {
                args[p] = target
                continue
            }
            val cls = p.type.classifier as? KClass<*>
            val arg: Any? = when {
                cls == null -> null
                cls in FUNCTION_TYPES -> callback(cls, p.name.orEmpty().contains("fail", true), onResult, onError)
                cls.qualifiedName?.startsWith(LISTENER_PACKAGE) == true -> listener(cls, onResult, onError)
                cls in AREA_TYPES -> area(cls, areaHint)
                cls == Boolean::class -> (value as? Boolean) ?: (value as? Number)?.let { it.toInt() != 0 }
                cls == Int::class -> (value as? Number)?.toInt() ?: (value as? Boolean)?.let { if (it) 1 else 0 }
                cls == Float::class -> (value as? Number)?.toFloat()
                cls == Double::class -> (value as? Number)?.toDouble()
                cls.java.isEnum -> enumFor(cls, value)
                else -> null
            }
            when {
                arg != null -> args[p] = arg
                p.isOptional -> Unit
                p.type.isMarkedNullable -> args[p] = null
                else -> {
                    onError(IllegalArgumentException("${function.name}: '${p.name}' (${cls?.simpleName}) 인자를 채울 수 없음"))
                    return null
                }
            }
        }
        return args
    }

    private fun callback(
        cls: KClass<*>,
        isFailure: Boolean,
        onResult: (List<Any?>) -> Unit,
        onError: (Throwable) -> Unit,
    ): Any {
        val deliver: (List<Any?>) -> Unit = { values ->
            val throwable = values.firstOrNull { it is Throwable } as? Throwable
            when {
                throwable != null -> onError(throwable)
                isFailure -> onError(IllegalStateException(values.joinToString()))
                else -> onResult(values)
            }
        }
        return when (cls) {
            Function0::class -> {
                val f: () -> Unit = { deliver(emptyList()) }
                f
            }
            Function1::class -> {
                val f: (Any?) -> Unit = { a -> deliver(listOf(a)) }
                f
            }
            Function2::class -> {
                val f: (Any?, Any?) -> Unit = { a, b -> deliver(listOf(a, b)) }
                f
            }
            Function3::class -> {
                val f: (Any?, Any?, Any?) -> Unit = { a, b, c -> deliver(listOf(a, b, c)) }
                f
            }
            else -> error("unsupported callback $cls")
        }
    }

    private fun listener(cls: KClass<*>, onResult: (List<Any?>) -> Unit, onError: (Throwable) -> Unit): Any {
        val proxy = Proxy.newProxyInstance(cls.java.classLoader, arrayOf(cls.java)) { self, method, arguments ->
            when (method.name) {
                "equals" -> self === arguments?.firstOrNull()
                "hashCode" -> System.identityHashCode(self)
                "toString" -> "MeomunjariListener(${cls.simpleName})"
                else -> {
                    val values = arguments?.toList().orEmpty()
                    val throwable = values.firstOrNull { it is Throwable } as? Throwable
                    if (throwable != null || method.name.contains("fail", true)) {
                        onError(throwable ?: IllegalStateException(method.name))
                    } else {
                        onResult(values)
                    }
                    Unit
                }
            }
        }
        listeners += proxy
        return proxy
    }

    private fun area(cls: KClass<*>, hint: String?): Any? = when (cls) {
        // 문은 잘못 고르면 엉뚱한 곳(후드·프렁크·트렁크)이 열린다. 정확히 맞는 문이 없으면 열지 않는다.
        DoorArea::class -> pickDoor(DoorValue.entries, hint)?.let { DoorArea(it) }
        MirrorArea::class -> MirrorArea(pick(MirrorValue.entries, hint))
        SeatArea::class -> SeatArea(pick(SeatValue.entries, hint))
        WheelArea::class -> WheelArea(pick(WheelValue.entries, hint))
        WindowArea::class -> WindowArea(pick(WindowValue.entries, hint))
        else -> error("unsupported area $cls")
    }

    private fun <T : Enum<T>> pick(values: List<T>, hint: String?): T =
        values.firstOrNull { it.name == hint }
            ?: values.firstOrNull { it.name in PREFERRED_AREAS }
            ?: values.first()

    private fun <T : Enum<T>> pickDoor(values: List<T>, hint: String?): T? {
        if (!loggedDoors) {
            Log.i(TAG, "DoorValue entries = ${values.map { it.name }}")
            loggedDoors = true
        }
        if (hint == null) return null
        val safe = values.filter { v -> FORBIDDEN_DOORS.none { v.name.contains(it, ignoreCase = true) } }
        val chosen = safe.firstOrNull { it.name == hint }
            ?: DOOR_ALIASES[hint]?.firstNotNullOfOrNull { alias -> safe.firstOrNull { it.name.equals(alias, true) } }
        if (chosen == null) Log.w(TAG, "문 '$hint' 에 맞는 DoorValue 없음 → 열지 않음. entries=${values.map { it.name }}")
        return chosen
    }

    private fun enumFor(cls: KClass<*>, value: Any?): Any? {
        val constants = cls.java.enumConstants?.map { it as Enum<*> } ?: return null
        return when (value) {
            is String -> constants.firstOrNull { it.name == value }
            is Boolean -> constants.firstOrNull { it.name == if (value) "ON" else "OFF" }
                ?: constants.firstOrNull { it.name.contains(if (value) "ON" else "OFF") }
            is Enum<*> -> value
            else -> constants.firstOrNull()
        }
    }

    fun clear() = listeners.clear()

    companion object {
        private const val TAG = "PleosInvoker"
        private const val LISTENER_PACKAGE = "ai.pleos.playground.vehicle.listener."
        private val FUNCTION_TYPES = setOf(Function0::class, Function1::class, Function2::class, Function3::class)
        private val AREA_TYPES = setOf(DoorArea::class, MirrorArea::class, SeatArea::class, WheelArea::class, WindowArea::class)
        private val PREFERRED_AREAS = setOf("ROW_1_LEFT", "FRONT_LEFT", "FRONT_WINDSHIELD")
        private val FORBIDDEN_DOORS = listOf("HOOD", "FRUNK", "TRUNK", "TAIL", "REAR_GATE", "LIFTGATE", "BONNET")
        private val DOOR_ALIASES = mapOf(
            "ROW_1_LEFT" to listOf("FRONT_LEFT", "DRIVER", "ROW1_LEFT"),
            "ROW_1_RIGHT" to listOf("FRONT_RIGHT", "PASSENGER", "ROW1_RIGHT"),
            "ROW_2_LEFT" to listOf("REAR_LEFT", "ROW2_LEFT"),
            "ROW_2_RIGHT" to listOf("REAR_RIGHT", "ROW2_RIGHT"),
        )

        /** 콜백 값 중 마지막 숫자. */
        fun number(values: List<Any?>): Float? = values.lastOrNull { it is Number }?.let { (it as Number).toFloat() }

        /** 콜백 값을 on/off로 해석. Boolean, 숫자(0=off), Enum(OFF/STOP/UNKNOWN 외 = on). */
        fun flag(values: List<Any?>): Boolean? {
            val v = values.lastOrNull { it is Boolean || it is Number || it is Enum<*> } ?: return null
            return when (v) {
                is Boolean -> v
                is Number -> v.toInt() != 0
                is Enum<*> -> v.name !in setOf("OFF", "STOP", "UNKNOWN", "NONE", "INTERMITTENT_OFF")
                else -> null
            }
        }

        /** checkXxxCapabilities 결과로 지원 여부 판단. */
        fun supported(values: List<Any?>): Boolean =
            values.filterIsInstance<CapabilityConfig>().none { it.accessType == AccessType.NONE }
    }
}
