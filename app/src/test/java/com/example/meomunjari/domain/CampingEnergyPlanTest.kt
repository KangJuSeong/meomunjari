package com.example.meomunjari.domain

import com.example.meomunjari.Fixtures
import com.example.meomunjari.core.model.LatLng
import com.example.meomunjari.core.model.PowerType
import com.example.meomunjari.data.mock.mockChargingStations
import org.junit.Assert.*
import org.junit.Test

class CampingEnergyPlanTest {
    private val seed = Fixtures.seed()
    private val spot = seed.spots.first { it.id == "s-003" }
    private val records = CampingEnergyPlanner.matchingReports(seed.reports, spot, 11)
    private val start = LatLng(38.207, 128.5918)
    private fun plan(pct: Double, night: NightEstimate?) =
        CampingEnergyPlanner.plan(pct, start, spot, seed.user.car, 5.1, night, mockChargingStations)!!

    private val departure = java.time.LocalDateTime.of(2026, 11, 3, 23, 0)
    private fun compare(pct: Double, wake: java.time.LocalTime? = null,
        reports: List<com.example.meomunjari.core.model.MeasurementReport> = records) =
        CampingEnergyPlanner.compareRoutes(pct, start, spot, seed.user.car, 5.1,
            departure, wake, reports, mockChargingStations)!!

    @Test fun comparisonIncludesDetourChargeAndShorterStayBeforeSameWake() {
        val result = compare(25.0, java.time.LocalTime.of(6, 30))
        val via = result.via!!
        val stop = result.stop!!
        val first = CampingEnergyPlanner.roadKm(start, stop.station.location)
        val last = CampingEnergyPlanner.roadKm(stop.station.location, spot.location)
        val minutes = CampingEnergyPlanner.drivingMinutes(first + last) + stop.minutes
        assertEquals(departure.plusMinutes(minutes.toLong()), via.arrival)
        assertTrue(via.arrival.toLocalDate() > departure.toLocalDate())
        assertTrue(via.stayMinutes < result.direct.stayMinutes)
        assertEquals(result.direct.arrival.plusMinutes(result.direct.stayMinutes.toLong()),
            via.arrival.plusMinutes(via.stayMinutes.toLong()))
        val arrivalPct = 25.0 - EnergyPredictor.consumptionPct(first + last, seed.user.car, 5.1) + stop.minutes
        assertEquals(arrivalPct, via.arrivalPct, 0.0001)
        assertEquals(arrivalPct - via.stayMinutes / 60.0 * 1.75, via.morningPct!!, 0.0001)
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

    @Test fun comparisonExplainsMissingDataAndArrivalAfterWake() {
        val directArrival = compare(25.0).direct.arrival
        val late = compare(25.0, directArrival.toLocalTime().plusMinutes(1)).via!!
        assertTrue(late.stayMinutes < 0)
        assertNull(late.morningPct)
        val sparse = compare(62.0, reports = records.take(1))
        assertNull(sparse.direct.morningPct)
        assertNull(sparse.via!!.morningPct)
        val stranded = compare(0.0)
        assertFalse(stranded.direct.reachable)
        assertNull(stranded.via)
    }

    @Test fun hourlySeedCoversEachSpotAndUsesWallClockBoundaries() {
        val keys = (0..23).map { java.time.LocalTime.of(it, 0).toString() }.toSet()
        seed.spots.forEach { assertEquals(it.id, keys, it.hourlyTemperaturesC.keys) }
        // 방태산 18:00 = 10°C, 19:00 = 9°C. 정각까지는 대기 소모만 있다.
        val start = java.time.LocalTime.of(18, 45)
        val night = CampingEnergyPlanner.estimateNight(spot, start, records, stayMinutes = 60)!!
        assertEquals(0.75, night.heatingHours, 0.0)
        val live = CampingEnergyPlanner.estimateNight(spot, start, records, elapsedHours = 0.5,
            hvacNow = false, stayMinutes = 60)!!
        assertEquals(0.0, live.heatingHours, 0.0)
    }

    @Test fun midnightPartialHoursAndMissingWeather() {
        val midnight = spot.copy(hourlyTemperaturesC = mapOf("23:00" to 11.0, "00:00" to 8.0))
        val start = java.time.LocalTime.of(23, 40)
        fun estimate(minutes: Int) = CampingEnergyPlanner.estimateNight(midnight, start, records, stayMinutes = minutes)
        assertEquals(0.5, estimate(50)!!.heatingHours, 0.0)
        assertEquals(1.0, estimate(80)!!.heatingHours, 0.0)
        assertNull(estimate(81))
        val legacy = spot.copy(hourlyTemperaturesC = emptyMap(), overnightTemperaturesC = listOf(12.0, 5.0))
        assertEquals(0.0, CampingEnergyPlanner.estimateNight(legacy, start, records, stayMinutes = 50)!!.heatingHours, 0.0)
    }

    @Test fun directAndChargingStopUseTheirOwnArrivalHours() {
        val tripStart = departure.withHour(17).withMinute(35)
        val result = CampingEnergyPlanner.compareRoutes(62.0, start, spot, seed.user.car, 5.1,
            tripStart, java.time.LocalTime.of(20, 0), records, mockChargingStations)!!
        val direct = result.direct
        val via = result.via!!
        assertEquals(java.time.LocalTime.of(18, 45), direct.arrival.toLocalTime())
        assertEquals(java.time.LocalTime.of(19, 0), via.arrival.toLocalTime())
        val directDrain = direct.arrivalPct - direct.morningPct!!
        val viaDrain = via.arrivalPct - via.morningPct!!
        val idleRate = CampingEnergyPlanner.estimateNight(listOf(12.0), records, stayMinutes = 60)!!.drainPct
        assertEquals(idleRate / 4, directDrain - viaDrain, 0.0001)
    }

    @Test fun insufficientRecordsAndMissingForecast() {
        val r = records.first()
        val mixed = listOf(r, r, r.copy(id = "ice", powerType = PowerType.ICE), r.copy(id = "missing", hasEnergyReading = false),
            r.copy(id = "other", spotId = "other"), r.copy(id = "month", month = 2))
        val eligible = CampingEnergyPlanner.matchingReports(mixed, spot, 11)
        assertEquals(1, eligible.size)
        assertNull(CampingEnergyPlanner.estimateNight(spot, java.time.LocalTime.MIDNIGHT, eligible))
        assertNull(CampingEnergyPlanner.estimateNight(emptyList(), records))
        assertNull(plan(65.0, null).morningPct)
        assertTrue(plan(1.0, null).requiresCharge)
        assertFalse(plan(65.0, null).requiresCharge)
    }

    @Test fun coldHoursChangeMorningAndRecommendationTogether() {
        val two = records.take(2)
        val mild = CampingEnergyPlanner.estimateNight(List(8) { 10.0 }, two)!!
        val cold = CampingEnergyPlanner.estimateNight(List(8) { 9.0 }, two)!!
        assertEquals(0.0, mild.heatingHours, 0.0)
        assertEquals(8.0, cold.heatingHours, 0.0)
        val pct = 1 + mild.drainPct + plan(100.0, mild).requiredMorningPct +
            (100 - plan(100.0, mild).arrivalPct)
        assertFalse(plan(pct, mild).requiresCharge)
        val low = plan(pct, cold)
        assertTrue(low.requiresCharge)
        val stop = low.stop!!
        assertEquals(0, stop.minutes % 5)
        val first = CampingEnergyPlanner.roadKm(start, stop.station.location)
        val last = CampingEnergyPlanner.roadKm(stop.station.location, spot.location)
        fun cost(km: Double) = EnergyPredictor.consumptionPct(km, seed.user.car, 5.1)
        assertTrue(pct >= cost(first))
        assertTrue(minOf(100.0, pct - cost(first) + stop.minutes) - cost(last) - cold.drainPct >= low.requiredMorningPct)
        assertNull(plan(0.0, cold).stop)
    }

    @Test fun wakeTimeAcrossMidnightAndPartialHourChangesChargeDecision() {
        val bedtime = java.time.LocalTime.of(23, 0)
        val wake = java.time.LocalTime.of(6, 30)
        val minutes = WakeSchedule.stayMinutes(bedtime, wake)
        assertEquals(450, minutes)
        assertEquals(480, WakeSchedule.stayMinutes(bedtime, null))
        assertEquals(1440, WakeSchedule.stayMinutes(bedtime, bedtime))
        assertEquals(30, WakeSchedule.stayMinutes(wake, wake.plusMinutes(30)))
        fun estimate(duration: Int, elapsed: Double = 0.0) = CampingEnergyPlanner.estimateNight(
            List(8) { 5.0 }, records, elapsedHours = elapsed, stayMinutes = duration)!!
        val early = estimate(minutes)
        val late = estimate(minutes + 30)
        assertEquals(7.5, early.heatingHours, 0.0)
        assertEquals(0.25, estimate(minutes, 7.25).heatingHours, 0.0)
        assertEquals(0.0, estimate(minutes, 7.75).drainPct, 0.0)
        val full = plan(100.0, early)
        val pct = 100 - full.arrivalPct + full.requiredMorningPct + (early.drainPct + late.drainPct) / 2
        assertFalse(plan(pct, early).requiresCharge)
        assertNotNull(plan(pct, late).stop)
        assertNull(CampingEnergyPlanner.estimateNight(List(8) { 5.0 }, records, stayMinutes = 481))
    }

    @Test fun atCampCountsOnlyRemainingTimeAndObservedHvac() {
        val weather = List(8) { 5.0 }
        val partial = CampingEnergyPlanner.estimateNight(weather, records, 6.5, hvacNow = false)!!
        assertEquals(1.0, partial.heatingHours, 0.0)
        val protected = CampingEnergyPlanner.estimateNight(weather, records, 6.5, false, true)!!
        assertEquals(0.0, protected.heatingHours, 0.0)
        assertEquals(0.0, CampingEnergyPlanner.estimateNight(weather, records, 9.0)!!.drainPct, 0.0)
    }
}
