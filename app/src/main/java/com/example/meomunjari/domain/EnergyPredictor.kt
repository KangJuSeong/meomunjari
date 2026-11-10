package com.example.meomunjari.domain

import com.example.meomunjari.core.model.CarProfile
import com.example.meomunjari.core.model.PowerType
import kotlin.math.ceil
import kotlin.math.max

enum class ForecastBasis(val label: String) {
    MEASURED("이 스팟 실측 기반"),
    REFERENCE("실측이 적어 참고치"),
    SEASONAL_DEFAULT("계절 기본값 (참고치)"),
}

enum class Verdict { ENOUGH, CHARGE_ON_ROUTE }

data class EnergyForecast(
    val powerType: PowerType,
    val nowPct: Double,
    val routeKm: Double,
    val arrivePct: Double,
    val overnightPct: Double,
    val morningPct: Double,
    val stayHours: Double,
    val chargerKm: Double,
    val neededAtMorningPct: Double,
    val verdict: Verdict,
    /** CHARGE_ON_ROUTE일 때 권장 충전 시간(분, 5분 단위). */
    val chargeMinutes: Int?,
    val basis: ForecastBasis,
    val sampleCount: Int,
) {
    fun message(): String = when (verdict) {
        Verdict.ENOUGH -> if (powerType == PowerType.EV) "가장 가까운 충전소까지 충분해요" else "가장 가까운 주유소까지 충분해요"
        Verdict.CHARGE_ON_ROUTE -> if (powerType == PowerType.EV) {
            "경로상 충전소에서 ${chargeMinutes}분 충전 후 출발하세요"
        } else {
            "출발 전 경로상 주유소에서 주유하세요"
        }
    }
}

/**
 * 도착 시 잔량 = 현재 − 경로 소모분
 * 아침 잔량  = 도착 시 잔량 − (스팟·월 실측 시간당 소모율 × 머무는 시간)
 * 아침 잔량이 '가장 가까운 충전소까지 소모분 + 여유분'보다 적으면 경로상 충전을 제안한다.
 */
object EnergyPredictor {
    const val RESERVE_PCT = 15.0
    const val CHARGE_MARGIN_PCT = 5.0
    const val FAST_CHARGE_PCT_PER_MIN = 1.0
    const val DEFAULT_STAY_HOURS = 8.0

    /** 실측이 없을 때 쓰는 시간당 소모율(%/h). EV는 공조, 내연기관은 공회전 기준. */
    fun seasonalDrainPerHour(month: Int, powerType: PowerType): Double {
        val season = ReportAggregator.season(month)
        return when (powerType) {
            PowerType.EV -> when (season) {
                Season.WINTER -> 2.0
                Season.SUMMER -> 1.6
                else -> 1.2
            }
            PowerType.ICE -> when (season) {
                Season.WINTER -> 1.4
                Season.SUMMER -> 1.2
                else -> 0.8
            }
        }
    }

    /** distanceKm를 달리는 데 드는 잔량(%). */
    fun consumptionPct(distanceKm: Double, car: CarProfile, efficiencyKmPerKWh: Double? = null): Double =
        when (car.powerType) {
            PowerType.EV -> {
                val eff = efficiencyKmPerKWh?.takeIf { it > 0 } ?: car.efficiencyKmPerKWh
                distanceKm / eff / car.batteryKWh * 100.0
            }
            PowerType.ICE -> distanceKm / car.fuelKmPerL / car.fuelTankL * 100.0
        }

    fun forecast(
        nowPct: Double,
        routeKm: Double,
        car: CarProfile,
        efficiencyKmPerKWh: Double?,
        summary: ReportSummary?,
        month: Int,
        chargerKm: Double,
        stayHours: Double = DEFAULT_STAY_HOURS,
    ): EnergyForecast {
        val energySamples = summary?.energySampleCount ?: 0
        val measured = summary?.avgDrainPerHour?.takeIf { energySamples > 0 }
        val drainPerHour = measured ?: seasonalDrainPerHour(month, car.powerType)
        val basis = when {
            measured == null -> ForecastBasis.SEASONAL_DEFAULT
            energySamples < ReportAggregator.MIN_RELIABLE -> ForecastBasis.REFERENCE
            else -> ForecastBasis.MEASURED
        }

        val arrive = max(0.0, nowPct - consumptionPct(routeKm, car, efficiencyKmPerKWh))
        val overnight = drainPerHour * stayHours
        val morning = max(0.0, arrive - overnight)
        val needed = consumptionPct(chargerKm, car, efficiencyKmPerKWh) + RESERVE_PCT

        val verdict = if (morning >= needed) Verdict.ENOUGH else Verdict.CHARGE_ON_ROUTE
        val minutes = if (verdict == Verdict.CHARGE_ON_ROUTE && car.powerType == PowerType.EV) {
            val raw = (needed - morning + CHARGE_MARGIN_PCT) / FAST_CHARGE_PCT_PER_MIN
            (ceil(raw / 5.0) * 5).toInt()
        } else {
            null
        }

        return EnergyForecast(
            powerType = car.powerType,
            nowPct = nowPct,
            routeKm = routeKm,
            arrivePct = arrive,
            overnightPct = overnight,
            morningPct = morning,
            stayHours = stayHours,
            chargerKm = chargerKm,
            neededAtMorningPct = needed,
            verdict = verdict,
            chargeMinutes = minutes,
            basis = basis,
            sampleCount = summary?.energySampleCount ?: 0,
        )
    }
}
