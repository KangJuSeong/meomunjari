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

    @Test fun insufficientRecordsAndMissingForecast() {
        val r = records.first()
        val mixed = listOf(r, r, r.copy(id = "ice", powerType = PowerType.ICE), r.copy(id = "missing", hasEnergyReading = false),
            r.copy(id = "other", spotId = "other"), r.copy(id = "month", month = 2))
        val eligible = CampingEnergyPlanner.matchingReports(mixed, spot, 11)
        assertEquals(1, eligible.size)
        assertNull(CampingEnergyPlanner.estimateNight(spot.overnightTemperaturesC, eligible))
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

    @Test fun atCampCountsOnlyRemainingTimeAndObservedHvac() {
        val weather = List(8) { 5.0 }
        val partial = CampingEnergyPlanner.estimateNight(weather, records, 6.5, hvacNow = false)!!
        assertEquals(1.0, partial.heatingHours, 0.0)
        val protected = CampingEnergyPlanner.estimateNight(weather, records, 6.5, false, true)!!
        assertEquals(0.0, protected.heatingHours, 0.0)
        assertEquals(0.0, CampingEnergyPlanner.estimateNight(weather, records, 9.0)!!.drainPct, 0.0)
    }
}
