package com.example.meomunjari.pleos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.meomunjari.core.vehicle.Gear
import kotlin.math.exp
import kotlin.random.Random

/**
 * Pleos 에뮬레이터에서만 쓰는 가짜 값.
 *
 * 에뮬레이터는 등받이 이동 명령을 받아도 위치가 바뀌지 않고, 실내 온도도 19.1°C에서 움직이지 않는다.
 * 시연·테스트에서 흐름을 확인할 수 있도록 이 두 가지만 흉내 낸다. 실차에서는 [enabled]가 false라 쓰이지 않는다.
 */
internal class EmulatorMock(val enabled: Boolean = isEmulator()) {

    private var cabinC: Double? = null
    private val random = Random(7)

    /** 차가 처음 알려준 실내 온도로 시작점을 잡는다. 이후 차가 보내는 고정 값은 무시한다. */
    fun seedCabin(realC: Float) {
        if (cabinC == null) {
            cabinC = realC.toDouble()
            PleosLog.info("에뮬레이터: 실내 온도를 ${realC}°C에서 시작해 공조·외기에 따라 변하도록 흉내 냄")
        }
    }

    /**
     * 실내 온도를 [simMinutes]만큼 진행한다. 공조가 켜져 있으면 목표 온도로, 꺼져 있으면 외기 쪽으로 다가간다.
     * 차박 기록이 단조롭지 않도록 ±0.15°C 안에서 조금씩 흔들린다.
     */
    fun stepCabin(simMinutes: Double, hvacOn: Boolean, targetC: Float?, outsideC: Float?): Float? {
        val now = cabinC ?: return null
        val goal = if (hvacOn && targetC != null) targetC.toDouble() else (outsideC?.toDouble() ?: now)
        val tau = if (hvacOn) HVAC_TAU_MIN else DRIFT_TAU_MIN
        val next = goal + (now - goal) * exp(-simMinutes / tau)
        cabinC = next
        val wobble = (random.nextDouble() - 0.5) * 0.3
        return (next + wobble).toFloat()
    }

    /**
     * 테스트 스크립트가 정한 기어·속도. 이 빌드의 에뮬레이터는 셸에서 차량 속성을 바꿀 수 없어
     * (`cmd car_service inject-vhal-event`는 user 빌드에서 막힘), 브로드캐스트로 받은 값을 차 값 대신 쓴다.
     * null이면 차가 알려준 값을 그대로 쓴다.
     */
    @Volatile var gearOverride: Gear? = null
    @Volatile var speedOverrideKmh: Float? = null

    /**
     * `adb shell am broadcast -a com.example.meomunjari.DEMO_DRIVE --es gear D --ef speed 60`
     * gear: P·R·N·D 중 하나, 또는 CLEAR(차 값으로 되돌림). speed: km/h (없으면 D일 때 60, 그 외 0).
     */
    fun registerDriveReceiver(context: Context) {
        if (!enabled) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val g = intent?.getStringExtra("gear")?.uppercase() ?: return
                val gear = when (g) {
                    "P" -> Gear.PARK
                    "R" -> Gear.REVERSE
                    "N" -> Gear.NEUTRAL
                    "D" -> Gear.DRIVE
                    else -> null
                }
                gearOverride = gear
                speedOverrideKmh = gear?.let {
                    if (intent.hasExtra("speed")) intent.getFloatExtra("speed", 0f) else if (it == Gear.DRIVE) 60f else 0f
                }
                PleosLog.info("에뮬레이터: 테스트 기어 ${gear ?: "해제(차 값 사용)"} · 속도 ${speedOverrideKmh ?: "-"}km/h")
            }
        }
        ContextCompat.registerReceiver(
            context.applicationContext, receiver, IntentFilter(ACTION_DEMO_DRIVE), ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private var batteryPct: Double? = null

    /** 차가 처음 알려준 배터리 잔량으로 시작점을 잡는다. 이후 차가 보내는 고정 값(100%)은 무시한다. */
    fun seedBattery(realPct: Float) {
        if (batteryPct == null) {
            batteryPct = realPct.toDouble()
            PleosLog.info("에뮬레이터: 배터리를 ${realPct}%에서 시작해 공조·대기 전력만큼 줄어들도록 흉내 냄")
        }
    }

    /**
     * 배터리를 [simMinutes]만큼 쓴다. 공조가 켜져 있으면 공조 전력, 항상 대기 전력을 쓴다.
     * 72.6 kWh 차에서 공조를 켜고 8시간이면 약 12%가 줄어든다.
     */
    fun stepBattery(simMinutes: Double, hvacOn: Boolean, batteryKWh: Double): Float? {
        val now = batteryPct ?: return null
        if (batteryKWh <= 0.0) return now.toFloat()
        val kW = STANDBY_KW + if (hvacOn) HVAC_KW else 0.0
        val next = (now - kW * simMinutes / 60.0 / batteryKWh * 100.0).coerceAtLeast(0.0)
        batteryPct = next
        return next.toFloat()
    }

    companion object {
        const val ACTION_DEMO_DRIVE = "com.example.meomunjari.DEMO_DRIVE"
        /** 공조가 쓰는 평균 전력(kW). */
        private const val HVAC_KW = 0.9
        /** 차박 중 대기 전력(kW). */
        private const val STANDBY_KW = 0.2
        /** 공조가 켜졌을 때 목표 온도에 63% 다가가는 데 걸리는 시간(분). */
        private const val HVAC_TAU_MIN = 15.0
        /** 공조가 꺼졌을 때 외기 쪽으로 63% 식거나 데워지는 시간(분). */
        private const val DRIFT_TAU_MIN = 90.0

        fun isEmulator(): Boolean =
            Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("_avd_")
    }
}
