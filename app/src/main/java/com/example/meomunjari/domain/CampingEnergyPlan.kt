package com.example.meomunjari.domain

import com.example.meomunjari.core.Geo
import com.example.meomunjari.core.model.CarProfile
import com.example.meomunjari.core.model.LatLng
import com.example.meomunjari.core.model.PowerType
import com.example.meomunjari.core.model.Spot
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

data class NightEstimate(val drainPct: Double, val heatingHours: Double, val energyKWh: Double)
data class RouteEstimate(
    val departure: LocalDateTime,
    val arrival: LocalDateTime,
    val arrivalPct: Double,
    val reachable: Boolean,
    val stayMinutes: Int,
    val morningPct: Double?,
)

object CampingEnergyPlanner {
    const val IONIQ_5_HEAT_PUMP = "ioniq5-2021-awd-72.6-heat-pump"
    private const val PACK_KWH = 72.6
    // AVL 시험의 외기 0°C·AUTO 22°C 구간 평균 전력이다 (docs/hvac-power-research.md).
    private const val WARMUP_KW = 1.1
    private const val MAINTENANCE_KW = 0.5
    // 초기 구간 길이와 비공조 대기 전력은 시험에 없는 목업 가정이다.
    private const val WARMUP_MINUTES = 20
    private const val AUXILIARY_KW = 0.2

    /** 도착 직후부터 기상까지의 계획 중 아직 지나지 않은 구간만 적산한다. */
    fun estimateNight(
        spot: Spot, start: LocalTime, car: CarProfile, targetTempC: Double?, heatingEnabled: Boolean,
        elapsedHours: Double = 0.0,
        stayMinutes: Int = WakeSchedule.DEFAULT_MINUTES,
    ): NightEstimate? {
        if (car.hvacPowerProfile != IONIQ_5_HEAT_PUMP || car.powerType != PowerType.EV || car.batteryKWh != PACK_KWH ||
            stayMinutes !in 1..1440 || !elapsedHours.isFinite() || elapsedHours < 0) return null

        // 이전 목업의 배열은 도착 시점부터 매 60분, 현행 예보는 스팟의 현지 정각부터 유효하다.
        val byLocalHour = spot.hourlyTemperaturesC.isNotEmpty()
        val startMinute = if (byLocalHour) start.hour * 60 + start.minute else 0
        val end = startMinute + stayMinutes
        val remainingStart = startMinute + (elapsedHours * 60).coerceAtMost(stayMinutes.toDouble())
        val remainingMinutes = end - remainingStart
        val heatingMinutes = if (heatingEnabled) remainingMinutes else 0.0
        if (heatingMinutes > 0) {
            if (targetTempC != 22.0) return null
            for (hour in (remainingStart / 60).toInt() until ceil(end / 60.0).toInt()) {
                val outside = if (byLocalHour) spot.hourlyTemperaturesC[LocalTime.of(hour % 24, 0).toString()]
                    else spot.overnightTemperaturesC.getOrNull(hour)
                if (outside != 0.0) return null
            }
        }
        val warmupRemaining = (startMinute + WARMUP_MINUTES - remainingStart).coerceAtLeast(0.0)
        val warmupMinutes = minOf(heatingMinutes, warmupRemaining)
        val heatingHours = heatingMinutes / 60
        val energyKWh = AUXILIARY_KW * remainingMinutes / 60 +
            WARMUP_KW * warmupMinutes / 60 + MAINTENANCE_KW * (heatingMinutes - warmupMinutes) / 60
        return NightEstimate(energyKWh / car.batteryKWh * 100, heatingHours, energyKWh)
    }

    fun drivingMinutes(km: Double): Int = (km / 45.0 * 60).toInt()

    fun estimateDeparture(
        nowPct: Double, from: LatLng, spot: Spot, car: CarProfile, efficiency: Double?,
        departure: LocalDateTime, wake: LocalTime?, targetTempC: Double?, heatingEnabled: Boolean,
    ): RouteEstimate? {
        val effectiveEfficiency = efficiency?.takeIf { it.isFinite() && it > 0 } ?: car.efficiencyKmPerKWh
        if (car.powerType != PowerType.EV || nowPct !in 0.0..100.0 || !car.batteryKWh.isFinite() || car.batteryKWh <= 0 ||
            !effectiveEfficiency.isFinite() || effectiveEfficiency <= 0) return null

        val distanceKm = roadKm(from, spot.location)
        if (!distanceKm.isFinite()) return null
        val start = departure.truncatedTo(ChronoUnit.MINUTES)
        val arrival = start.plusMinutes(drivingMinutes(distanceKm).toLong())
        val arrivalPct = nowPct - EnergyPredictor.consumptionPct(distanceKm, car, effectiveEfficiency)
        val reachable = arrivalPct >= 0
        val stayMinutes = WakeSchedule.stayMinutes(arrival.toLocalTime(), wake)
        val night = if (reachable) estimateNight(spot, arrival.toLocalTime(), car, targetTempC, heatingEnabled,
            stayMinutes = stayMinutes) else null
        return RouteEstimate(
            departure = start,
            arrival = arrival,
            arrivalPct = arrivalPct.coerceAtLeast(0.0),
            reachable = reachable,
            stayMinutes = stayMinutes,
            morningPct = night?.let { (arrivalPct - it.drainPct).coerceAtLeast(0.0) },
        )
    }

    fun roadKm(from: LatLng, to: LatLng) = Geo.distanceM(from, to) * Geo.ROAD_FACTOR / 1000
}
