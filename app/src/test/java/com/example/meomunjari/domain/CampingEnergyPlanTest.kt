package com.example.meomunjari.domain

import com.example.meomunjari.Fixtures
import com.example.meomunjari.core.model.LatLng
import com.example.meomunjari.core.model.PowerType
import com.example.meomunjari.data.mock.mockChargingStations
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class CampingEnergyPlanTest {
    private val seed = Fixtures.seed()
    private val spot = seed.spots.first { it.id == "s-003" }
    private val car = seed.user.car
    private val start = LatLng(38.207, 128.5918)
    private val departure = LocalDateTime.of(2026, 11, 3, 23, 0)
    private val bedtime = LocalTime.of(23, 0)

    private fun plan(pct: Double, night: NightEstimate?) =
        CampingEnergyPlanner.plan(pct, start, spot, car, 5.1, night, mockChargingStations)!!

    private fun compare(pct: Double, wake: LocalTime? = null, target: Double = 22.0) =
        CampingEnergyPlanner.compareRoutes(pct, start, spot, car, 5.1, departure, wake, target, true, mockChargingStations)!!

    @Test fun eightHourColdStartUsesReferencePowerAndAssumptions() {
        val night = CampingEnergyPlanner.estimateNight(spot, bedtime, car, 22.0, true)!!
        assertEquals(8.0, night.heatingHours, 0.0)
        assertEquals(1.1 / 3 + 0.5 * (8 - 1.0 / 3) + 0.2 * 8, night.energyKWh, 0.000001)
        assertEquals(5.8 / 72.6 * 100, night.drainPct, 0.000001)
    }

    @Test fun shortStayAndLiveForecastDoNotCountWarmupTwice() {
        fun estimate(stay: Int, elapsed: Double) = CampingEnergyPlanner.estimateNight(
            spot, bedtime, car, 22.0, true, elapsedHours = elapsed, stayMinutes = stay)!!
        assertEquals(1.3 / 6, estimate(10, 0.0).energyKWh, 0.000001)
        assertEquals((1.3 * 10 + 0.7 * 40) / 60, estimate(60, 1.0 / 6).energyKWh, 0.000001)
        assertEquals(0.7, estimate(120, 1.0).energyKWh, 0.000001)
        assertEquals(0.0, estimate(120, 3.0).energyKWh, 0.0)
    }

    @Test fun calibrationRequiresTrimTemperatureAndTarget() {
        assertEquals(CampingEnergyPlanner.IONIQ_5_HEAT_PUMP, car.hvacPowerProfile)
        listOf(car.copy(hvacPowerProfile = null), car.copy(batteryKWh = 77.4),
            car.copy(hvacPowerProfile = "other"), car.copy(powerType = PowerType.ICE)).forEach {
            assertNull(CampingEnergyPlanner.estimateNight(spot, bedtime, it, 22.0, true))
        }
        listOf(21.0, 23.0, Double.NaN, null).forEach {
            assertNull(CampingEnergyPlanner.estimateNight(spot, bedtime, car, it, true))
        }
        for (outside in listOf(-1.0, 1.0, 20.0, Double.NaN)) {
            val weather = spot.copy(hourlyTemperaturesC = spot.hourlyTemperaturesC + ("01:00" to outside))
            assertNull(CampingEnergyPlanner.estimateNight(weather, bedtime, car, 22.0, true))
        }
    }

    @Test fun partialHoursMidnightAndMissingWeather() {
        val keys = (0..23).map { LocalTime.of(it, 0).toString() }.toSet()
        seed.spots.forEach { assertEquals(it.id, keys, it.hourlyTemperaturesC.keys) }
        val midnight = spot.copy(hourlyTemperaturesC = mapOf("23:00" to 0.0, "00:00" to 0.0))
        val startTime = LocalTime.of(23, 40)
        fun estimate(minutes: Int) = CampingEnergyPlanner.estimateNight(midnight, startTime, car, 22.0, true, stayMinutes = minutes)
        assertEquals(80.0 / 60, estimate(80)!!.heatingHours, 0.000001)
        assertNull(estimate(81))
        val legacy = spot.copy(hourlyTemperaturesC = emptyMap(), overnightTemperaturesC = listOf(0.0))
        assertNotNull(CampingEnergyPlanner.estimateNight(legacy, startTime, car, 22.0, true, stayMinutes = 60))
        assertNull(CampingEnergyPlanner.estimateNight(legacy, startTime, car, 22.0, true, stayMinutes = 61))
        assertNull(estimate(0))
        assertNull(estimate(1441))
    }

    @Test fun heatingOffAndProtectionUseOnlyAuxiliaryDraw() {
        val noWeather = spot.copy(hourlyTemperaturesC = emptyMap(), overnightTemperaturesC = emptyList())
        val off = CampingEnergyPlanner.estimateNight(noWeather, bedtime, car, null, false)!!
        assertEquals(0.0, off.heatingHours, 0.0)
        assertEquals(1.6, off.energyKWh, 0.000001)
        val offAfterProtection = CampingEnergyPlanner.estimateNight(noWeather, bedtime, car, 22.0, false, elapsedHours = 6.5)!!
        assertEquals(0.3, offAfterProtection.energyKWh, 0.000001)
        val live = CampingEnergyPlanner.estimateNight(spot, bedtime, car, 22.0, true, elapsedHours = 6.5)!!
        assertEquals(1.5, live.heatingHours, 0.0)
        assertEquals(1.05, live.energyKWh, 0.000001)
    }

    @Test fun comparisonIncludesDetourChargeAndShorterStayBeforeSameWake() {
        val result = compare(25.0, LocalTime.of(6, 30))
        val via = result.via!!
        val stop = result.stop!!
        val first = CampingEnergyPlanner.roadKm(start, stop.station.location)
        val last = CampingEnergyPlanner.roadKm(stop.station.location, spot.location)
        val minutes = CampingEnergyPlanner.drivingMinutes(first + last) + stop.minutes
        assertEquals(departure.plusMinutes(minutes.toLong()), via.arrival)
        assertTrue(via.arrival.toLocalDate() > departure.toLocalDate())
        assertTrue(via.stayMinutes < result.direct.stayMinutes)
        assertEquals(result.direct.arrival.plusMinutes(result.direct.stayMinutes.toLong()), via.arrival.plusMinutes(via.stayMinutes.toLong()))
        val arrivalPct = 25.0 - EnergyPredictor.consumptionPct(first + last, car, 5.1) + stop.minutes
        assertEquals(arrivalPct, via.arrivalPct, 0.0001)
        val usedKWh = 0.2 + 0.7 * via.stayMinutes / 60
        assertEquals(arrivalPct - usedKWh / 72.6 * 100, via.morningPct!!, 0.0001)
        assertTrue(via.morningPct >= plan(100.0, null).requiredMorningPct)
    }

    @Test fun optionalChargeAndDefaultNightAreAvailableEvenWhenDirectIsEnough() {
        val result = compare(62.0)
        assertFalse(result.requiresCharge)
        assertEquals(15, result.stop!!.minutes)
        assertEquals(480, result.direct.stayMinutes)
        assertEquals(480, result.via!!.stayMinutes)
        assertTrue(result.via.morningPct!! > result.direct.morningPct!!)
        val high = compare(99.0)
        assertTrue(high.stop!!.minutes < 15)
        assertTrue(high.via!!.arrivalPct < 100)
    }

    @Test fun comparisonHandlesMissingDataAndArrivalAfterWake() {
        val directArrival = compare(25.0).direct.arrival
        val late = compare(25.0, directArrival.toLocalTime().plusMinutes(1)).via!!
        assertTrue(late.stayMinutes < 0)
        assertNull(late.morningPct)
        val unsupported = compare(62.0, target = 20.0)
        assertNull(unsupported.direct.morningPct)
        assertNull(unsupported.via!!.morningPct)
        // 주행만으로 예비 잔량을 밑도는 경우에는 야간 소모 자료 없이도 충전이 필요하다.
        assertTrue(compare(25.0, target = 20.0).requiresCharge)
        val stranded = compare(0.0)
        assertFalse(stranded.direct.reachable)
        assertNull(stranded.direct.morningPct)
        assertNull(stranded.via)
    }

    @Test fun directAndViaCheckOnlyTheirOwnHourlyWeather() {
        val tripStart = departure.withHour(17).withMinute(35)
        val weather = spot.copy(hourlyTemperaturesC = mapOf("18:00" to 5.0, "19:00" to 0.0))
        val result = CampingEnergyPlanner.compareRoutes(62.0, start, weather, car, 5.1,
            tripStart, LocalTime.of(20, 0), 22.0, true, mockChargingStations)!!
        assertEquals(LocalTime.of(18, 45), result.direct.arrival.toLocalTime())
        assertEquals(LocalTime.of(19, 0), result.via!!.arrival.toLocalTime())
        assertNull(result.direct.morningPct)
        assertNotNull(result.via.morningPct)
    }

    @Test fun chargeTargetIncludesKnownViaNightWhenDirectWeatherIsUnsupported() {
        val tripStart = departure.withHour(17).withMinute(40)
        val weather = spot.copy(hourlyTemperaturesC = spot.hourlyTemperaturesC + ("18:00" to 5.0))
        val result = CampingEnergyPlanner.compareRoutes(25.0, start, weather, car, 5.1, tripStart, null,
            22.0, true, mockChargingStations)!!
        assertNull(result.direct.morningPct)
        val via = result.via!!
        assertNotNull(via.morningPct)
        val stop = result.stop!!
        val reserve = plan(100.0, null).requiredMorningPct
        val lastLeg = CampingEnergyPlanner.roadKm(stop.station.location, weather.location)
        val morningAtTarget = stop.targetPct!! - EnergyPredictor.consumptionPct(lastLeg, car, 5.1) - 5.8 / 72.6 * 100
        assertTrue(morningAtTarget >= reserve)
        assertTrue(via.morningPct!! >= reserve)
    }

    @Test fun wakeTimeAcrossMidnightChangesChargeDecision() {
        val wake = LocalTime.of(6, 30)
        val minutes = WakeSchedule.stayMinutes(bedtime, wake)
        assertEquals(450, minutes)
        assertEquals(480, WakeSchedule.stayMinutes(bedtime, null))
        assertEquals(1440, WakeSchedule.stayMinutes(bedtime, bedtime))
        assertEquals(30, WakeSchedule.stayMinutes(wake, wake.plusMinutes(30)))
        fun estimate(duration: Int) = CampingEnergyPlanner.estimateNight(spot, bedtime, car, 22.0, true, stayMinutes = duration)!!
        val early = estimate(minutes)
        val late = estimate(minutes + 30)
        val full = plan(100.0, early)
        val pct = 100 - full.arrivalPct + full.requiredMorningPct + (early.drainPct + late.drainPct) / 2
        assertFalse(plan(pct, early).requiresCharge)
        assertTrue(plan(pct, late).requiresCharge)
        assertNotNull(plan(pct, late).stop)
    }
}
