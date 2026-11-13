package com.example.meomunjari.domain

import com.example.meomunjari.core.Geo
import com.example.meomunjari.core.model.CarProfile
import com.example.meomunjari.core.model.LatLng
import com.example.meomunjari.core.model.MeasurementReport
import com.example.meomunjari.core.model.PowerType
import com.example.meomunjari.core.model.Spot
import com.example.meomunjari.core.navi.ChargingStop
import com.example.meomunjari.core.navi.NaviDestination
import kotlin.math.ceil
import kotlin.math.min

data class NightEstimate(val drainPct: Double, val heatingHours: Double, val samples: Int)
data class CampingEnergyPlan(
    val arrivalPct: Double,
    val night: NightEstimate?,
    val morningPct: Double?,
    val requiresCharge: Boolean,
    val stop: ChargingStop?,
    val requiredMorningPct: Double,
)

object CampingEnergyPlanner {
    const val HEATING_THRESHOLD_C = 10.0
    private const val IDLE_PCT_PER_HOUR = 0.15
    private const val HEATING_PCT_PER_HOUR = 1.75

    fun matchingReports(reports: List<MeasurementReport>, spot: Spot, month: Int) =
        reports.filter { it.spotId == spot.id && it.month == month && it.powerType == PowerType.EV && it.validEnergy }
            .distinctBy { it.id }

    /** 매 시간의 외기온으로 난방 여부를 예측한다. 차박 중에는 현재 시간 구간에 차량 상태를 우선한다. */
    fun estimateNight(
        temperatures: List<Double>, records: List<MeasurementReport>, elapsedHours: Double = 0.0,
        hvacNow: Boolean? = null, protectionTriggered: Boolean = false,
        stayMinutes: Int = WakeSchedule.DEFAULT_MINUTES,
    ): NightEstimate? {
        if (stayMinutes !in 1..1440 || !elapsedHours.isFinite()) return null
        val duration = stayMinutes / 60.0
        val hours = ceil(duration).toInt()
        if (records.size < 2 || temperatures.size < hours || temperatures.take(hours).any { !it.isFinite() }) return null
        val elapsed = elapsedHours.coerceIn(0.0, duration)
        val offRecords = records.filter { it.hvacHours == 0.0 }
        val idleRate = if (offRecords.isEmpty()) IDLE_PCT_PER_HOUR
            else offRecords.sumOf { it.energyUsedPct } / offRecords.sumOf { it.durationHours }
        val onRecords = records.filter { it.hvacHours > 0 }
        val onRate = if (onRecords.isEmpty()) HEATING_PCT_PER_HOUR else {
            val onDrain = onRecords.sumOf { it.energyUsedPct - idleRate * (it.durationHours - it.hvacHours) }
            (onDrain / onRecords.sumOf { it.hvacHours }).coerceAtLeast(idleRate)
        }
        var heat = 0.0
        for (hour in 0 until hours) {
            val remaining = (minOf(hour + 1.0, duration) - maxOf(hour.toDouble(), elapsed)).coerceAtLeast(0.0)
            val heating = if (hour == elapsed.toInt() && hvacNow != null) hvacNow
                else temperatures[hour] < HEATING_THRESHOLD_C
            if (heating && !protectionTriggered) heat += remaining
        }
        return NightEstimate(heat * onRate + (duration - elapsed - heat) * idleRate, heat, records.size)
    }

    fun plan(
        nowPct: Double, from: LatLng, spot: Spot, car: CarProfile, efficiency: Double?,
        night: NightEstimate?, stations: List<NaviDestination>,
    ): CampingEnergyPlan? {
        val effectiveEfficiency = efficiency?.takeIf { it.isFinite() && it > 0 } ?: car.efficiencyKmPerKWh
        if (car.powerType != PowerType.EV || nowPct !in 0.0..100.0 || !car.batteryKWh.isFinite() || car.batteryKWh <= 0 ||
            !effectiveEfficiency.isFinite() || effectiveEfficiency <= 0) return null
        fun cost(km: Double) = EnergyPredictor.consumptionPct(km, car, effectiveEfficiency)
        val directKm = roadKm(from, spot.location)
        val arrival = nowPct - cost(directKm)
        val morning = night?.let { (arrival - it.drainPct).coerceAtLeast(0.0) }
        val reserve = cost(spot.nearestChargerKm) + EnergyPredictor.RESERVE_PCT
        val low = arrival <= 0 || (morning != null && morning < reserve)
        val stop = if (!low) null else stations.mapNotNull { station ->
            val firstKm = roadKm(from, station.location)
            val secondKm = roadKm(station.location, spot.location)
            if (firstKm + secondKm > directKm + maxOf(3.0, directKm * 0.25)) return@mapNotNull null
            val atStation = nowPct - cost(firstKm)
            val required = cost(secondKm) + (night?.drainPct ?: 0.0) + reserve
            if (atStation < 0 || required > 100) return@mapNotNull null
            val target = min(100.0, required + EnergyPredictor.CHARGE_MARGIN_PCT)
            val minutes = ceil((target - atStation) / EnergyPredictor.FAST_CHARGE_PCT_PER_MIN / 5).toInt() * 5
            ChargingStop(station, minutes.coerceAtLeast(5)) to (firstKm + secondKm)
        }.minByOrNull { it.second }?.first
        return CampingEnergyPlan(arrival.coerceAtLeast(0.0), night, morning, low, stop, reserve)
    }

    fun roadKm(from: LatLng, to: LatLng) = Geo.distanceM(from, to) * Geo.ROAD_FACTOR / 1000
}
