package com.example.meomunjari.domain

import com.example.meomunjari.core.Geo
import com.example.meomunjari.core.model.CarProfile
import com.example.meomunjari.core.model.LatLng
import com.example.meomunjari.core.model.PowerType
import com.example.meomunjari.core.model.Spot
import com.example.meomunjari.core.navi.ChargingStop
import com.example.meomunjari.core.navi.NaviDestination
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.min

data class NightEstimate(val drainPct: Double, val heatingHours: Double, val energyKWh: Double)
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
)

data class DepartureComparison(
    val departure: LocalDateTime,
    val direct: RouteEstimate,
    val via: RouteEstimate?,
    val stop: ChargingStop?,
    val requiresCharge: Boolean,
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
        val morning = night?.takeIf { arrival >= 0 }?.let { (arrival - it.drainPct).coerceAtLeast(0.0) }
        val reserve = cost(spot.nearestChargerKm) + EnergyPredictor.RESERVE_PCT
        val low = (morning ?: arrival) < reserve
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
        departure: LocalDateTime, wake: LocalTime?, targetTempC: Double?, heatingEnabled: Boolean,
        stations: List<NaviDestination>,
    ): DepartureComparison? {
        val start = departure.truncatedTo(ChronoUnit.MINUTES)
        val directKm = roadKm(from, spot.location)
        val directArrival = start.plusMinutes(drivingMinutes(directKm).toLong())
        val directStay = WakeSchedule.stayMinutes(directArrival.toLocalTime(), wake)
        val directNight = estimateNight(spot, directArrival.toLocalTime(), car, targetTempC, heatingEnabled, stayMinutes = directStay)
        val plan = plan(nowPct, from, spot, car, efficiency, directNight, stations, includeOptionalStop = true) ?: return null

        val eff = efficiency?.takeIf { it.isFinite() && it > 0 } ?: car.efficiencyKmPerKWh
        fun cost(km: Double) = EnergyPredictor.consumptionPct(km, car, eff)
        val reachable = nowPct >= cost(directKm)
        val direct = RouteEstimate(directArrival, plan.arrivalPct, reachable, directStay,
            plan.morningPct.takeIf { reachable })
        fun viaRoute(stop: ChargingStop): RouteEstimate {
            val first = roadKm(from, stop.station.location)
            val last = roadKm(stop.station.location, spot.location)
            val arrival = start.plusMinutes(drivingMinutes(first + last).toLong() + stop.minutes)
            val charged = min(100.0, nowPct - cost(first) + stop.minutes * EnergyPredictor.FAST_CHARGE_PCT_PER_MIN)
            val arrivalPct = charged - cost(last)
            // 명시한 기상 시각은 두 경로에 같은 날짜로 고정한다.
            val stay = if (wake == null) WakeSchedule.DEFAULT_MINUTES
                else ChronoUnit.MINUTES.between(arrival, directArrival.plusMinutes(directStay.toLong())).toInt()
            val morning = when {
                stay < 0 -> null
                stay == 0 -> arrivalPct
                else -> estimateNight(spot, arrival.toLocalTime(), car, targetTempC, heatingEnabled, stayMinutes = stay)
                    ?.let { (arrivalPct - it.drainPct).coerceAtLeast(0.0) }
            }
            return RouteEstimate(arrival, arrivalPct, true, stay, morning)
        }

        val originalStop = plan.stop ?: return DepartureComparison(start, direct, null, null, plan.requiresCharge)
        val via = viaRoute(originalStop)
        if (plan.requiresCharge && directNight == null && via.morningPct != null && via.stayMinutes > 0) {
            // 늦게 도착하면 지원 조건에 들어올 수 있다. 조기 출발 기준도 경유지의 야간 소모를 포함하도록 갱신한다.
            val viaNight = estimateNight(spot, via.arrival.toLocalTime(), car, targetTempC, heatingEnabled, stayMinutes = via.stayMinutes)
            val adjusted = plan(nowPct, from, spot, car, efficiency, viaNight, listOf(originalStop.station))?.stop
            if (adjusted != null) return DepartureComparison(start, direct, viaRoute(adjusted), adjusted, true)
        }
        return DepartureComparison(start, direct, via, originalStop, plan.requiresCharge)
    }

    fun roadKm(from: LatLng, to: LatLng) = Geo.distanceM(from, to) * Geo.ROAD_FACTOR / 1000
}
