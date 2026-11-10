package com.example.meomunjari.trip

import com.example.meomunjari.Fixtures
import com.example.meomunjari.core.Gateways
import com.example.meomunjari.core.model.LatLng
import com.example.meomunjari.core.vehicle.Gear
import com.example.meomunjari.data.mock.toRepositories
import com.example.meomunjari.domain.SunriseMode
import com.example.meomunjari.domain.TagNormalizer
import com.example.meomunjari.sim.SimulatedWorld
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 자동 주행으로 스팟에 들어오면, 마지막 주행 방향이 차 앞머리 방향이 된다. */
class ParkingHeadingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repos = Fixtures.seed().toRepositories()
    private val world = SimulatedWorld(scope, MutableStateFlow(1), { repos.spots.spots.value })
    private val trip = TripStore(
        scope, Gateways(world, world, world, world, "mock"), repos, TagNormalizer(repos.reference.tags),
        MutableStateFlow(1), nowMs = { 0L }, today = { LocalDate.of(2026, 11, 5) },
    )

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun headingFromApproachAndAdvice() = runBlocking {
        val spot = repos.spots.get("s-003")!!
        // 스팟 정서쪽 3km에서 출발 → 동쪽으로 달려 들어온다
        world.setLocation(LatLng(spot.lat, spot.lng - 0.034))
        trip.navigateTo(spot)
        world.setAutoDrive(true)
        repeat(20) {
            world.tick(1_000)
            trip.evaluateArrival()
        }
        world.setGear(Gear.PARK)
        trip.evaluateArrival()

        val heading = trip.heading.value
        assertNotNull(heading)
        // 마지막 구간은 스팟 북쪽 40m 지점을 향한 동쪽 방향
        assertTrue("heading=$heading", heading!! in 60.0..100.0)

        val advice = trip.parkingAdvice()!!
        assertEquals(SunriseMode.WINDSHIELD, advice.mode)
        assertTrue(advice.turnDeg!! > 0) // 일출(약 110°)은 더 오른쪽

        trip.flipHeading()
        assertEquals((heading + 180) % 360, trip.heading.value!!, 0.01)
    }
}
