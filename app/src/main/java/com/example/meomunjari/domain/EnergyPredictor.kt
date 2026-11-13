package com.example.meomunjari.domain

import com.example.meomunjari.core.model.CarProfile
import com.example.meomunjari.core.model.PowerType
import kotlin.math.max

enum class ForecastBasis(val label: String) {
    MEASURED("이 스팟 실측 기반"),
    REFERENCE("실측이 적어 참고치"),
    SEASONAL_DEFAULT("계절 기본값 (참고치)"),
}

data class EnergyForecast(
    val nowPct: Double,
    val routeKm: Double,
    val arrivePct: Double,
    val overnightPct: Double,
    val morningPct: Double,
    val stayHours: Double,
    val basis: ForecastBasis,
    val sampleCount: Int,
)

/** 주행 구간 소모량과 내연기관의 차박 연료 예측. 전기차의 야간 소모는 CampingEnergyPlanner에서 계산한다. */
object EnergyPredictor {
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

        return EnergyForecast(
            nowPct = nowPct,
            routeKm = routeKm,
            arrivePct = arrive,
            overnightPct = overnight,
            morningPct = morning,
            stayHours = stayHours,
            basis = basis,
            sampleCount = energySamples,
        )
    }
}
