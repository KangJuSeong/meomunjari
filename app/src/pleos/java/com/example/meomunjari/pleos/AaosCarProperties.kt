package com.example.meomunjari.pleos

import android.content.Context
import android.util.Log
import com.example.meomunjari.core.vehicle.Gear

/**
 * Vehicle SDK에 Gear 도메인·외기 온도가 없어 AAOS CarPropertyManager로 기어·속도·외기 온도를 읽는다.
 * android.car 라이브러리를 컴파일 의존성으로 두지 않기 위해 리플렉션으로 접근한다.
 * 읽지 못하면 null/UNKNOWN을 돌려주고, 앱은 '정지 상태'로 대신 판단한다(DriveGuard).
 */
internal class AaosCarProperties(private val context: Context) {
    private var car: Any? = null
    private var manager: Any? = null

    /** 속성별 마지막으로 읽은 결과. 1초마다 읽으므로 바뀐 때만 로그를 남긴다. */
    private val lastLogged = mutableMapOf<Int, String>()

    /**
     * 차량 서비스에 연결한다. 리스너 없이 Car.createCar(context)로 만들면, 차량 서비스가 재시작될 때
     * AAOS가 이 앱 프로세스를 같이 죽인다(에뮬레이터에서 VHAL이 강제 종료되며 실제로 발생).
     * 그래서 수명주기 리스너를 넘겨, 서비스가 내려가면 읽기를 멈추고 다시 올라오면 매니저를 새로 받는다.
     */
    fun connect(): Boolean = runCatching {
        PleosLog.info("→ AAOS Car.createCar(context, handler, 대기 안 함, 수명주기 리스너)")
        val carClass = Class.forName("android.car.Car")
        val listenerClass = Class.forName("android.car.Car\$CarServiceLifecycleListener")
        val listener = java.lang.reflect.Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { self, method, args ->
            when (method.name) {
                "onLifecycleChanged" -> {
                    val ready = args?.getOrNull(1) as? Boolean ?: false
                    onCarLifecycle(carClass, args?.getOrNull(0), ready)
                    null
                }
                "equals" -> self === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(self)
                "toString" -> "MeomunjariCarLifecycleListener"
                else -> null
            }
        }
        val createCar = carClass.getMethod(
            "createCar", Context::class.java, android.os.Handler::class.java,
            Long::class.javaPrimitiveType, listenerClass,
        )
        // 대기 시간 0(CAR_WAIT_TIMEOUT_DO_NOT_WAIT): 연결되면 리스너로 알려준다.
        car = createCar.invoke(null, context, null, 0L, listener)
        manager != null || car != null
    }.onFailure {
        PleosLog.warn("✗ AAOS Car.createCar: ${it.message}", it)
        Log.w(TAG, "CarPropertyManager unavailable: ${it.message}")
    }.getOrDefault(false)

    private fun onCarLifecycle(carClass: Class<*>, newCar: Any?, ready: Boolean) {
        if (!ready) {
            manager = null
            lastLogged.clear()
            PleosLog.warn("⇠ AAOS 차량 서비스 연결 끊김(재시작 중) → 다시 연결될 때까지 기어·속도 읽기 중단")
            return
        }
        car = newCar ?: car
        manager = runCatching { carClass.getMethod("getCarManager", String::class.java).invoke(car, "property") }
            .onFailure { PleosLog.warn("✗ AAOS getCarManager(\"property\"): ${it.message}", it) }
            .getOrNull()
        PleosLog.info("⇠ AAOS 차량 서비스 연결됨 → CarPropertyManager ${if (manager != null) "받음" else "없음"}")
    }

    fun gear(): Gear {
        val v = read(GEAR_SELECTION, java.lang.Integer::class.java) as? Int
            ?: read(CURRENT_GEAR, java.lang.Integer::class.java) as? Int
            ?: return Gear.UNKNOWN
        return when {
            v == GEAR_PARK -> Gear.PARK
            v == GEAR_REVERSE -> Gear.REVERSE
            v == GEAR_NEUTRAL -> Gear.NEUTRAL
            v >= GEAR_DRIVE -> Gear.DRIVE
            else -> Gear.UNKNOWN
        }
    }

    /** m/s → km/h */
    fun speedKmh(): Float? = (read(PERF_VEHICLE_SPEED, java.lang.Float::class.java) as? Float)?.let { it * 3.6f }

    /**
     * 외기 온도(°C). Vehicle SDK에서 외기 온도 API를 찾지 못해 AAOS 표준 속성을 읽는다.
     * Pleos 가이드상 SDK에 없는 속성을 직접 쓰려면 기술지원 검토가 필요하다. 못 읽으면 null.
     */
    fun outsideTempC(): Float? = read(ENV_OUTSIDE_TEMPERATURE, java.lang.Float::class.java) as? Float

    private fun read(propertyId: Int, type: Class<*>): Any? {
        val m = manager ?: return null
        var outcome = ""
        val result = runCatching {
            val getProperty = m.javaClass.getMethod(
                "getProperty",
                Class::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            val value = getProperty.invoke(m, type, propertyId, 0)
            if (value == null) {
                outcome = "null"
                return@runCatching null
            }
            val status = value.javaClass.getMethod("getStatus").invoke(value)
            if (status != 0) {
                outcome = "status=$status (사용 불가)"
                return@runCatching null
            }
            value.javaClass.getMethod("getValue").invoke(value).also { outcome = PleosLog.fmt(it) }
        }.onFailure {
            val cause = (it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it
            outcome = "✗ ${cause.javaClass.simpleName}: ${cause.message}"
        }.getOrNull()
        if (lastLogged[propertyId] != outcome) {
            lastLogged[propertyId] = outcome
            PleosLog.info("AAOS getProperty(${propertyName(propertyId)}) = $outcome")
        }
        return result
    }

    private fun propertyName(id: Int): String = when (id) {
        GEAR_SELECTION -> "GEAR_SELECTION"
        CURRENT_GEAR -> "CURRENT_GEAR"
        PERF_VEHICLE_SPEED -> "PERF_VEHICLE_SPEED"
        ENV_OUTSIDE_TEMPERATURE -> "ENV_OUTSIDE_TEMPERATURE"
        else -> id.toString()
    }

    fun release() {
        PleosLog.info("→ AAOS Car.disconnect()")
        runCatching { car?.javaClass?.getMethod("disconnect")?.invoke(car) }
        car = null
        manager = null
    }

    companion object {
        private const val TAG = "AaosCar"

        // android.car.VehiclePropertyIds
        const val GEAR_SELECTION = 289408000
        const val CURRENT_GEAR = 289408001
        const val PERF_VEHICLE_SPEED = 291504647
        const val ENV_OUTSIDE_TEMPERATURE = 291505923

        // android.car.VehicleGear
        const val GEAR_NEUTRAL = 0x1
        const val GEAR_REVERSE = 0x2
        const val GEAR_PARK = 0x4
        const val GEAR_DRIVE = 0x8
    }
}
