package com.example.meomunjari.domain

import com.example.meomunjari.core.Geo
import com.example.meomunjari.core.model.CarProfile
import com.example.meomunjari.core.model.LatLng
import com.example.meomunjari.core.model.MeasurementReport
import com.example.meomunjari.core.model.PowerType
import com.example.meomunjari.core.model.Spot
import com.example.meomunjari.core.navi.ChargingStop
import com.example.meomunjari.core.navi.NaviDestination
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
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

data class RouteEstimate(
    val arrival: LocalDateTime,
    val arrivalPct: Double,
    val reachable: Boolean,
    val stayMinutes: Int,
    val morningPct: Double?,
    val sampleCount: Int,
)

data class DepartureComparison(
    val departure: LocalDateTime,
    val direct: RouteEstimate,
    val via: RouteEstimate?,
    val stop: ChargingStop?,
    val requiresCharge: Boolean,
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
        return estimateWindow(0, records, elapsedHours, hvacNow, protectionTriggered, stayMinutes, temperatures::getOrNull)
    }

    fun estimateNight(
        spot: Spot, start: LocalTime, records: List<MeasurementReport>, elapsedHours: Double = 0.0,
        hvacNow: Boolean? = null, protectionTriggered: Boolean = false,
        stayMinutes: Int = WakeSchedule.DEFAULT_MINUTES,
    ): NightEstimate? {
        if (spot.hourlyTemperaturesC.isEmpty()) {
            return estimateNight(spot.overnightTemperaturesC, records, elapsedHours, hvacNow, protectionTriggered, stayMinutes)
        }
        val startMinute = start.hour * 60 + start.minute
        return estimateWindow(startMinute, records, elapsedHours, hvacNow, protectionTriggered, stayMinutes) { hour ->
            spot.hourlyTemperaturesC[LocalTime.of(hour % 24, 0).toString()]
        }
    }

    private fun estimateWindow(
        startMinute: Int, records: List<MeasurementReport>, elapsedHours: Double, hvacNow: Boolean?,
        protectionTriggered: Boolean, stayMinutes: Int, temperatureAt: (Int) -> Double?,
    ): NightEstimate? {
        if (records.size < 2 || stayMinutes !in 1..1440 || !elapsedHours.isFinite()) return null
        val end = startMinute + stayMinutes
        val remainingStart = startMinute + (elapsedHours * 60).coerceIn(0.0, stayMinutes.toDouble())
        val currentHour = (remainingStart / 60).toInt()
        var heatingMinutes = 0.0
        for (hour in currentHour until ceil(end / 60.0).toInt()) {
            val overlap = minOf(end, (hour + 1) * 60) - maxOf(remainingStart, hour * 60.0)
            if (overlap <= 0) continue
            val temperature = temperatureAt(hour)?.takeIf { it.isFinite() } ?: return null
            val heating = if (hour == currentHour && hvacNow != null) hvacNow else temperature < HEATING_THRESHOLD_C
            if (heating && !protectionTriggered) heatingMinutes += overlap
        }
        val offRecords = records.filter { it.hvacHours == 0.0 }
        val idleRate = if (offRecords.isEmpty()) IDLE_PCT_PER_HOUR
            else offRecords.sumOf { it.energyUsedPct } / offRecords.sumOf { it.durationHours }
        val onRecords = records.filter { it.hvacHours > 0 }
        val onRate = if (onRecords.isEmpty()) HEATING_PCT_PER_HOUR else {
            val onDrain = onRecords.sumOf { it.energyUsedPct - idleRate * (it.durationHours - it.hvacHours) }
            (onDrain / onRecords.sumOf { it.hvacHours }).coerceAtLeast(idleRate)
        }
        val heat = heatingMinutes / 60
        val idle = (end - remainingStart - heatingMinutes).coerceAtLeast(0.0) / 60
        return NightEstimate(heat * onRate + idle * idleRate, heat, records.size)
    }

    fun plan(
        nowPct: Double, from: LatLng, spot: Spot, car: CarProfile, efficiency: Double?,
        night: NightEstimate?, stations: List<NaviDestination>, includeOptionalStop: Boolean = false,
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
        val stop = if (!low && !includeOptionalStop) null else stations.mapNotNull { station ->
            val firstKm = roadKm(from, station.location)
            val secondKm = roadKm(station.location, spot.location)
            if (firstKm + secondKm > directKm + maxOf(3.0, directKm * 0.25)) return@mapNotNull null
            val atStation = nowPct - cost(firstKm)
            val required = cost(secondKm) + (night?.drainPct ?: 0.0) + reserve
            if (atStation < 0 || required > 100) return@mapNotNull null
            val target = min(100.0, required + EnergyPredictor.CHARGE_MARGIN_PCT)
            val rate = EnergyPredictor.FAST_CHARGE_PCT_PER_MIN
            val minutes = if (low) (ceil((target - atStation) / rate / 5).toInt() * 5).coerceAtLeast(5)
                else minOf(15, ceil((100 - atStation) / rate).toInt())
            if (minutes <= 0) return@mapNotNull null
            val departurePct = min(100.0, atStation + minutes * rate)
            ChargingStop(station, minutes, ceil(if (low) target else departurePct).toInt()) to (firstKm + secondKm)
        }.minByOrNull { it.second }?.first
        return CampingEnergyPlan(arrival.coerceAtLeast(0.0), night, morning, low, stop, reserve)
    }

    fun drivingMinutes(km: Double): Int = (km / 45.0 * 60).toInt()

    fun compareRoutes(
        nowPct: Double, from: LatLng, spot: Spot, car: CarProfile, efficiency: Double?,
        departure: LocalDateTime, wake: LocalTime?, reports: List<MeasurementReport>, stations: List<NaviDestination>,
    ): DepartureComparison? {
        val start = departure.truncatedTo(ChronoUnit.MINUTES)
        val directKm = roadKm(from, spot.location)
        val directArrival = start.plusMinutes(drivingMinutes(directKm).toLong())
        val directStay = WakeSchedule.stayMinutes(directArrival.toLocalTime(), wake)
        val directRecords = matchingReports(reports, spot, directArrival.monthValue)
        val directNight = estimateNight(spot, directArrival.toLocalTime(), directRecords, stayMinutes = directStay)
        val plan = plan(nowPct, from, spot, car, efficiency, directNight, stations, includeOptionalStop = true) ?: return null

        val eff = efficiency?.takeIf { it.isFinite() && it > 0 } ?: car.efficiencyKmPerKWh
        fun cost(km: Double) = EnergyPredictor.consumptionPct(km, car, eff)
        val reachable = nowPct >= cost(directKm)
        val direct = RouteEstimate(directArrival, plan.arrivalPct, reachable, directStay,
            plan.morningPct.takeIf { reachable }, directRecords.size)
        val via = plan.stop?.let { stop ->
            val first = roadKm(from, stop.station.location)
            val last = roadKm(stop.station.location, spot.location)
            val arrival = start.plusMinutes(drivingMinutes(first + last).toLong() + stop.minutes)
            val charged = min(100.0, nowPct - cost(first) + stop.minutes * EnergyPredictor.FAST_CHARGE_PCT_PER_MIN)
            val arrivalPct = charged - cost(last)
            // 명시한 기상 시각은 두 경로에 같은 날짜로 고정한다.
            val stay = if (wake == null) WakeSchedule.DEFAULT_MINUTES
                else ChronoUnit.MINUTES.between(arrival, directArrival.plusMinutes(directStay.toLong())).toInt()
            val records = matchingReports(reports, spot, arrival.monthValue)
            val morning = when {
                stay < 0 -> null
                stay == 0 -> arrivalPct
                else -> estimateNight(spot, arrival.toLocalTime(), records, stayMinutes = stay)
                    ?.let { (arrivalPct - it.drainPct).coerceAtLeast(0.0) }
            }
            RouteEstimate(arrival, arrivalPct, true, stay, morning, records.size)
        }
        return DepartureComparison(start, direct, via, plan.stop, plan.requiresCharge)
    }

    fun roadKm(from: LatLng, to: LatLng) = Geo.distanceM(from, to) * Geo.ROAD_FACTOR / 1000
}
