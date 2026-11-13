package com.example.meomunjari.domain

import com.example.meomunjari.Fixtures
import com.example.meomunjari.core.model.LatLng
import com.example.meomunjari.core.model.PowerType
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

    private fun estimate(pct: Double, wake: LocalTime? = null, target: Double = 22.0) =
        CampingEnergyPlanner.estimateDeparture(pct, start, spot, car, 5.1, departure, wake, target, true)!!

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

    @Test fun departureForecastUsesRouteConsumptionAndSelectedWakeTime() {
        val result = estimate(25.0, LocalTime.of(6, 30))
        assertEquals(departure.toLocalDate().plusDays(1).atTime(0, 10), result.arrival)
        assertEquals(380, result.stayMinutes)
        val distance = CampingEnergyPlanner.roadKm(start, spot.location)
        val arrivalPct = 25.0 - EnergyPredictor.consumptionPct(distance, car, 5.1)
        assertEquals(arrivalPct, result.arrivalPct, 0.0001)
        val usedKWh = 0.2 + 0.7 * result.stayMinutes / 60
        assertEquals(arrivalPct - usedKWh / 72.6 * 100, result.morningPct!!, 0.0001)
        assertTrue(estimate(25.0, LocalTime.of(7, 0)).morningPct!! < result.morningPct)
    }

    @Test fun missingNightDataAndUnreachableRoutesRemainDistinct() {
        val unsupported = estimate(25.0, target = 20.0)
        assertTrue(unsupported.reachable)
        assertNull(unsupported.morningPct)
        val stranded = estimate(0.0)
        assertFalse(stranded.reachable)
        assertNull(stranded.morningPct)
    }

    @Test fun departureForecastUsesTheWeatherAtArrival() {
        val tripStart = departure.withHour(17).withMinute(40)
        val weather = spot.copy(hourlyTemperaturesC = spot.hourlyTemperaturesC + ("18:00" to 5.0))
        val result = CampingEnergyPlanner.estimateDeparture(25.0, start, weather, car, 5.1,
            tripStart, null, 22.0, true)!!
        assertEquals(LocalTime.of(18, 50), result.arrival.toLocalTime())
        assertNull(result.morningPct)
    }

    @Test fun wakeTimeAcrossMidnightIncludesTheFullStay() {
        val wake = LocalTime.of(6, 30)
        assertEquals(450, WakeSchedule.stayMinutes(bedtime, wake))
        assertEquals(480, WakeSchedule.stayMinutes(bedtime, null))
        assertEquals(1440, WakeSchedule.stayMinutes(bedtime, bedtime))
        assertEquals(30, WakeSchedule.stayMinutes(wake, wake.plusMinutes(30)))
    }
}
