package com.example.meomunjari.pleos

import android.content.Context
import android.util.Log
import com.example.meomunjari.core.vehicle.ChargeState
import com.example.meomunjari.core.vehicle.Gear

/**
 * Vehicle SDK에 Gear 도메인이 없어 AAOS CarPropertyManager로 기어·속도를 읽는다.
 * android.car 라이브러리를 컴파일 의존성으로 두지 않기 위해 리플렉션으로 접근한다.
 * 읽지 못하면 null/UNKNOWN을 돌려주고, 앱은 '정지 상태'로 대신 판단한다(DriveGuard).
 */
internal class AaosCarProperties(private val context: Context) {
    private var car: Any? = null
    private var manager: Any? = null

    fun connect(): Boolean = runCatching {
        val carClass = Class.forName("android.car.Car")
        car = carClass.getMethod("createCar", Context::class.java).invoke(null, context)
        manager = carClass.getMethod("getCarManager", String::class.java).invoke(car, "property")
        manager != null
    }.onFailure { Log.w(TAG, "CarPropertyManager unavailable: ${it.message}") }.getOrDefault(false)

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

    /** AAOS VehiclePropertyIds.EV_CHARGE_STATE / EvChargeState. */
    fun chargeState(): ChargeState = when (read(289410881, Int::class.javaObjectType)) {
        1 -> ChargeState.CHARGING
        2 -> ChargeState.FULL
        3 -> ChargeState.IDLE
        4 -> ChargeState.ERROR
        else -> ChargeState.UNKNOWN
    }

    /** m/s → km/h */
    fun speedKmh(): Float? = (read(PERF_VEHICLE_SPEED, java.lang.Float::class.java) as? Float)?.let { it * 3.6f }

    private fun read(propertyId: Int, type: Class<*>): Any? = runCatching {
        val m = manager ?: return null
        val getProperty = m.javaClass.getMethod(
            "getProperty",
            Class::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        val value = getProperty.invoke(m, type, propertyId, 0) ?: return null
        if (value.javaClass.getMethod("getStatus").invoke(value) != 0) return null
        value.javaClass.getMethod("getValue").invoke(value)
    }.getOrNull()

    fun release() {
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

        // android.car.VehicleGear
        const val GEAR_NEUTRAL = 0x1
        const val GEAR_REVERSE = 0x2
        const val GEAR_PARK = 0x4
        const val GEAR_DRIVE = 0x8
    }
}
