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
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** 자동 주행으로 스팟에 들어오면, 마지막 주행 방향이 차 앞머리 방향이 된다. */
class ParkingHeadingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repos = Fixtures.seed().toRepositories()
    private val world = SimulatedWorld(scope, MutableStateFlow(1), { repos.spots.spots.value }, repos.user.user.value.car.batteryKWh)
    private var now = Instant.parse("2026-11-05T18:00:00Z") // 11월 6일 03시, 한국시간
    private val trip = TripStore(
        scope, Gateways(world, world, world, world, "mock"), repos, TagNormalizer(repos.reference.tags),
        MutableStateFlow(1), nowMs = { now.toEpochMilli() }, today = { LocalDate.of(2026, 11, 5) },
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

        val heading = trip.parkingAdvice.value?.currentHeadingDeg
        assertNotNull(heading)
        // 마지막 구간은 스팟 북쪽 40m 지점을 향한 동쪽 방향
        assertTrue("heading=$heading", heading!! in 60.0..100.0)

        val advice = trip.parkingAdvice.value!!
        assertEquals(SunriseMode.WINDSHIELD, advice.mode)
        assertTrue(advice.turnDeg!! > 0) // 일출(약 110°)은 더 오른쪽

        trip.flipHeading()
        assertEquals((heading + 180) % 360, trip.parkingAdvice.value!!.currentHeadingDeg!!, 0.01)
        assertTrue(trip.parkingAdvice.value!!.headingFlipped)
        trip.setSunriseMode(SunriseMode.TAILGATE)
        assertEquals((advice.targetHeadingDeg + 180) % 360, trip.parkingAdvice.value!!.targetHeadingDeg, 0.01)
        trip.flipHeading()
        assertEquals(heading, trip.parkingAdvice.value!!.currentHeadingDeg!!, 0.01)
    }

    @Test
    fun speedWithUnknownGearCannotEstablishAFrontHeading() {
        val spot = repos.spots.get("s-003")!!
        world.setGear(Gear.UNKNOWN)
        world.setSpeed(15f)
        world.setLocation(LatLng(spot.lat, spot.lng - 0.001))
        trip.evaluateArrival()
        world.setLocation(spot.location)
        trip.evaluateArrival()
        assertNull(trip.parkingAdvice.value)

        world.setGear(Gear.PARK)
        trip.evaluateArrival()
        assertNull(trip.parkingAdvice.value!!.currentHeadingDeg)
        assertNull(trip.parkingAdvice.value!!.turnDeg)
    }

    @Test
    fun destinationRequiresArrivalAndClockRollsToNextSunriseWhileParked() = runBlocking {
        val spot = repos.spots.get("s-003")!!
        trip.navigateTo(spot)
        trip.evaluateArrival()
        assertNull(trip.parkingAdvice.value)

        world.setLocation(spot.location)
        world.setGear(Gear.PARK)
        trip.evaluateArrival()
        val advice = trip.parkingAdvice.value!!
        assertNull(advice.turnDeg)
        assertEquals(LocalDate.of(2026, 11, 6), advice.sunrise.time.toLocalDate())

        now = advice.sunrise.time.toInstant().plusSeconds(1)
        trip.evaluateArrival()
        assertEquals(LocalDate.of(2026, 11, 7), trip.parkingAdvice.value!!.sunrise.time.toLocalDate())
        world.setGear(Gear.DRIVE)
        trip.evaluateArrival()
        assertNull(trip.parkingAdvice.value)
    }
}
