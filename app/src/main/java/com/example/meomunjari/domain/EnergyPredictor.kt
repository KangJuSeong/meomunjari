package com.example.meomunjari.domain

import com.example.meomunjari.core.model.CarProfile
import com.example.meomunjari.core.model.PowerType
import kotlin.math.max

enum class ForecastBasis(val label: String) {
    MEASURED("이 스팟 실측 기반"),
    REFERENCE("실측이 적어 참고치"),
    SEASONAL_DEFAULT("계절 기본값 (참고치)"),
}

enum class Verdict { ENOUGH, REFUEL_ON_ROUTE }

data class EnergyForecast(
    val nowPct: Double,
    val routeKm: Double,
    val arrivePct: Double,
    val overnightPct: Double,
    val morningPct: Double,
    val stayHours: Double,
    val chargerKm: Double,
    val neededAtMorningPct: Double,
    val verdict: Verdict,
    val basis: ForecastBasis,
    val sampleCount: Int,
) {
    fun message(): String = when (verdict) {
        Verdict.ENOUGH -> "가장 가까운 주유소까지 충분해요"
        Verdict.REFUEL_ON_ROUTE -> "출발 전 경로상 주유소에서 주유하세요"
    }
}

/** 주행 구간 소모량과 내연기관의 차박 연료 예측. 전기차의 야간 소모는 CampingEnergyPlanner에서 계산한다. */
object EnergyPredictor {
    const val RESERVE_PCT = 15.0
    const val CHARGE_MARGIN_PCT = 5.0
    const val FAST_CHARGE_PCT_PER_MIN = 1.0
    const val DEFAULT_STAY_HOURS = 8.0

    private fun seasonalFuelDrainPerHour(month: Int): Double = when (ReportAggregator.season(month)) {
        Season.WINTER -> 1.4
        Season.SUMMER -> 1.2
        else -> 0.8
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

    fun fuelForecast(
        nowPct: Double,
        routeKm: Double,
        car: CarProfile,
        summary: ReportSummary?,
        month: Int,
        chargerKm: Double,
        stayHours: Double = DEFAULT_STAY_HOURS,
    ): EnergyForecast? {
        if (car.powerType != PowerType.ICE) return null
        val fuelSummary = summary?.takeIf { it.powerType == PowerType.ICE }
        val energySamples = fuelSummary?.energySampleCount ?: 0
        val measured = fuelSummary?.avgDrainPerHour?.takeIf { energySamples > 0 }
        val drainPerHour = measured ?: seasonalFuelDrainPerHour(month)
        val basis = when {
            measured == null -> ForecastBasis.SEASONAL_DEFAULT
            energySamples < ReportAggregator.MIN_RELIABLE -> ForecastBasis.REFERENCE
            else -> ForecastBasis.MEASURED
        }

        val arrive = max(0.0, nowPct - consumptionPct(routeKm, car))
        val overnight = drainPerHour * stayHours
        val morning = max(0.0, arrive - overnight)
        val needed = consumptionPct(chargerKm, car) + RESERVE_PCT

        val verdict = if (morning >= needed) Verdict.ENOUGH else Verdict.REFUEL_ON_ROUTE

        return EnergyForecast(
            nowPct = nowPct,
            routeKm = routeKm,
            arrivePct = arrive,
            overnightPct = overnight,
            morningPct = morning,
            stayHours = stayHours,
            chargerKm = chargerKm,
            neededAtMorningPct = needed,
            verdict = verdict,
            basis = basis,
            sampleCount = energySamples,
        )
    }
}
