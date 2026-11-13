package com.example.meomunjari.trip

import com.example.meomunjari.Fixtures
import com.example.meomunjari.core.Gateways
import com.example.meomunjari.core.Geo
import com.example.meomunjari.domain.CampingEnergyPlanner
import com.example.meomunjari.data.mock.mockChargingStations
import com.example.meomunjari.core.vehicle.ApplyStatus
import com.example.meomunjari.core.vehicle.ControlItem
import com.example.meomunjari.core.vehicle.Gear
import com.example.meomunjari.data.mock.toRepositories
import com.example.meomunjari.domain.BadgeType
import com.example.meomunjari.domain.DriveGuard
import com.example.meomunjari.domain.DriveState
import com.example.meomunjari.domain.SettingCompatibility
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 기획서 '시연 시나리오' 8단계를 mock 플레이버 시뮬레이터로 끝까지 돌린다. */
class DemoScenarioTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repos = Fixtures.seed().toRepositories()
    private val timeScale = MutableStateFlow(600)
    private val world = SimulatedWorld(scope, timeScale, { repos.spots.spots.value })
    private var clock = 0L
    private val trip = TripStore(
        scope = scope,
        gateways = Gateways(world, world, world, world, "mock"),
        repos = repos,
        tags = TagNormalizer(repos.reference.tags),
        timeScale = timeScale,
        nowMs = { clock },
        today = { LocalDate.of(2026, 11, 3) },
    )

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun chargeWaypointRetainsFinalCampsite() = runBlocking {
        val spot = repos.spots.get("s-003")!!
        val records = CampingEnergyPlanner.matchingReports(repos.reports.reports.value, spot, 11)
        val night = CampingEnergyPlanner.estimateNight(spot.overnightTemperaturesC, records)
        val from = world.location.value!!
        val stop = CampingEnergyPlanner.plan(25.0, from, spot, repos.user.user.value.car, 5.1,
            night, mockChargingStations)!!.stop!!
        world.setBattery(25f)
        assertTrue(trip.navigateTo(spot))
        assertTrue(trip.navigateTo(spot, stop))
        assertEquals(spot.id, world.destination.value?.spotId)
        world.setAutoDrive(true)
        var ticks = 0
        while (world.chargingStop.value != null && ticks++ < 100) world.tick(1_000)
        assertEquals(stop.station.location, world.location.value)
        assertEquals(spot.id, world.destination.value?.spotId)
        assertTrue(world.snapshot.value.batteryPct!! > 25f)
        repeat(40) { world.tick(1_000) }
        assertTrue(Geo.distanceM(world.location.value!!, spot.location) < spot.radiusM)
        assertTrue(trip.navigateTo(spot, stop))
        trip.cancelRoute()
        assertNull(world.chargingStop.value)
        assertNull(world.destination.value)
    }

    @Test
    fun fullDemoFlow() = runBlocking {
        val spot = repos.spots.get("s-003")!!

        // 4. 여기로 갈래요 → 내비 목적지
        assertTrue(trip.navigateTo(spot))
        assertEquals("s-003", world.destination.value?.spotId)

        // 주행 중이면 제어 불가
        world.setGear(Gear.DRIVE)
        world.setSpeed(60f)
        assertEquals(DriveState.DRIVING, DriveGuard.evaluate(world.snapshot.value))

        // 5. 스팟 근처로 이동 + P단 → 3초 유지 후 도착 인증
        world.setLocation(Geo.offsetNorth(spot.location, 60.0))
        world.setGear(Gear.PARK)
        trip.evaluateArrival()
        assertNull(trip.certifiedVisit.value)
        clock += 3_500
        trip.evaluateArrival()
        assertNotNull(trip.certifiedVisit.value)
        assertEquals(3, repos.spots.get("s-003")!!.liveCampers)
        assertNull("도착하면 목적지 안내를 끝낸다", trip.destination.value)

        // 6. 별헌터님 세팅 적용 (창문 미지원 차로 가정)
        world.setCapability(ControlItem.WINDOW, false)
        world.setOutsideTemp(7f)
        world.setCabinTemp(12f)
        trip.setConsent(true)
        val master = SettingCompatibility.master(repos.presets.forSpot("s-003"), repos.user.user.value.nickname)!!
        val started = trip.startCamping(master, includeDoor = false, loadedPresetId = master.id)
        assertTrue(started is StartCampingResult.Started)
        val results = (started as StartCampingResult.Started).state.applyResults.associate { it.item to it.status }
        assertEquals(ApplyStatus.APPLIED, results[ControlItem.HVAC])
        assertEquals(ApplyStatus.UNSUPPORTED, results[ControlItem.WINDOW])
        assertEquals(21f, world.snapshot.value.hvacTargetC)
        assertEquals(38, repos.presets.forSpot("s-003").first { it.id == master.id }.loadCount)

        // 7. 밤새 기록: 1초 = 10분 가속, 8시간 = 48샘플. 중간에 비.
        world.setBattery(62f)
        repeat(48) { i ->
            world.setWiper(i in 20..22)
            world.tick(1_000)
            trip.recordSample(trip.camping.value!!)
        }
        val camping = trip.camping.value!!
        assertEquals(49, camping.samples.size)
        assertEquals(8.0, camping.elapsedHours, 0.01)

        val visitsBefore = repos.user.user.value.visits.size
        val finished = trip.endCamping()!!

        // 차박을 끝낸 뒤 같은 자리에 그대로 서 있어도 다시 '도착'으로 인증하지 않는다.
        repeat(5) {
            clock += 1_000
            trip.evaluateArrival()
        }
        assertNull("차박 종료 후 같은 스팟을 재인증하면 안 된다", trip.certifiedVisit.value)
        assertEquals(visitsBefore, repos.user.user.value.visits.size)
        val report = finished.report!!
        assertEquals(8.0, report.durationHours, 0.01)
        assertTrue("공조 8시간이면 배터리가 10~20% 줄어야 한다: ${report.energyUsedPct}", report.energyUsedPct in 10.0..20.0)
        assertTrue(report.rained)
        assertTrue(report.minCabinTempC < 21.0)
        assertEquals(1, finished.creditsEarned)
        assertEquals(13, repos.reports.forSpot("s-003").count { it.month == 11 })

        // 8. 리뷰 + #일출맛집 → 뱃지 진행도
        val reviewed = trip.submitReview(5, "별이 쏟아져요", listOf("#일출", "별"), sharePreset = true)!!
        val after = reviewed.after.badges.associateBy { it.type }
        val before = finished.before.badges.associateBy { it.type }
        assertEquals(before.getValue(BadgeType.EXPLORER).current + 1, after.getValue(BadgeType.EXPLORER).current)
        assertEquals(before.getValue(BadgeType.RECORDER).current + 1, after.getValue(BadgeType.RECORDER).current)
        assertTrue("#별보기 5곳째 → 테마 마스터(별 헌터)", after.getValue(BadgeType.THEME_MASTER).earned)
        assertTrue(reviewed.after.points > finished.before.points)

        val updatedSpot = repos.spots.get("s-003")!!
        assertTrue(updatedSpot.tags.any { it.tag == "#일출맛집" })
        assertEquals(1, repos.reviews.forSpot("s-003").count { it.author == "주성" })
        assertTrue(repos.presets.forSpot("s-003").any { it.ownerNickname == "주성" })

        // 떠났다가 다시 오면 새 방문으로 인증된다.
        world.setLocation(Geo.offsetNorth(spot.location, 1_000.0))
        trip.evaluateArrival()
        world.setLocation(Geo.offsetNorth(spot.location, 60.0))
        trip.evaluateArrival()
        clock += 3_500
        trip.evaluateArrival()
        assertNotNull("다시 찾아오면 인증돼야 한다", trip.certifiedVisit.value)
    }

    @Test
    fun registerBlockedInRestrictedZoneAndAllowedElsewhere() {
        world.setGear(Gear.PARK)
        world.setLocation(com.example.meomunjari.core.model.LatLng(38.1905, 128.6005))
        val blocked = trip.registerSpot("해수욕장 앞", "", com.example.meomunjari.core.model.Amenities(), emptyList(), com.example.meomunjari.core.model.VehicleClass.LARGE)
        assertTrue(blocked is RegisterResult.Restricted)

        world.setLocation(com.example.meomunjari.core.model.LatLng(38.2482, 128.5668))
        assertTrue(trip.isParkedAtUnknownPlace())
        val ok = trip.registerSpot("봉포 해변 공터", "조용한 공터", com.example.meomunjari.core.model.Amenities(toilet = true), listOf("오션뷰"), com.example.meomunjari.core.model.VehicleClass.LARGE)
        assertTrue(ok is RegisterResult.Registered)
        val spot = (ok as RegisterResult.Registered).spot
        assertEquals(listOf("#바다뷰"), spot.tags.map { it.tag })
        assertTrue(trip.status().badges.first { it.type == BadgeType.PIONEER }.current == 2)
        assertNotNull(trip.certifiedVisit.value)
    }
}
