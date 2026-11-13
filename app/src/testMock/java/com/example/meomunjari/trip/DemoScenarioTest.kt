package com.example.meomunjari.trip

import com.example.meomunjari.Fixtures
import com.example.meomunjari.core.Gateways
import com.example.meomunjari.core.Geo
import com.example.meomunjari.core.model.SettingPreset
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    private val world = SimulatedWorld(scope, timeScale, { repos.spots.spots.value }, repos.user.user.value.car.batteryKWh)
    private var clock = java.time.LocalDateTime.of(2026, 11, 3, 23, 0)
        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    private var ignorePowerCommand = false
    private val vehicle = object : com.example.meomunjari.core.vehicle.VehicleGateway by world {
        override suspend fun setHvacPower(on: Boolean): Boolean =
            if (ignorePowerCommand) true else world.setHvacPower(on)
    }
    private val trip = TripStore(
        scope = scope,
        gateways = Gateways(vehicle, world, world, world, "mock"),
        repos = repos,
        tags = TagNormalizer(repos.reference.tags),
        timeScale = timeScale,
        nowMs = { clock },
        elapsedMs = { clock },
        today = { LocalDate.of(2026, 11, 3) },
    )

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun chargeWaypointRetainsFinalCampsite() = runBlocking {
        val spot = repos.spots.get("s-003")!!
        val user = repos.user.user.value
        val night = CampingEnergyPlanner.estimateNight(spot, java.time.LocalTime.of(23, 0), user.car,
            user.mySetting.targetTempC, user.mySetting.hvacOn)
        val from = world.location.value!!
        val stop = CampingEnergyPlanner.plan(25.0, from, spot, repos.user.user.value.car, 5.1,
            night, mockChargingStations)!!.stop!!
        world.setBattery(25f)
        assertTrue(trip.navigateTo(spot))
        val directKm = CampingEnergyPlanner.roadKm(from, spot.location)
        val direct = withTimeout(2_000) { trip.route.filterNotNull().first() }
        assertEquals(CampingEnergyPlanner.drivingMinutes(directKm), direct.remainingMin)
        assertTrue(trip.navigateTo(spot, stop))
        assertEquals(spot.id, world.destination.value?.spotId)
        val detour = withTimeout(2_000) { trip.route.filterNotNull().first { it.remainingMin == null } }
        val finalLegKm = CampingEnergyPlanner.roadKm(stop.station.location, spot.location)
        assertEquals(CampingEnergyPlanner.roadKm(from, stop.station.location) + finalLegKm, detour.remainingKm, 0.001)
        world.setAutoDrive(true)
        var ticks = 0
        while (world.chargingStop.value != null && ticks++ < 100) world.tick(1_000)
        assertEquals(stop.station.location, world.location.value)
        assertEquals(spot.id, world.destination.value?.spotId)
        assertEquals(stop, world.arrivedStop.value)
        assertEquals(Gear.PARK, world.snapshot.value.gear)
        val arrivalPct = world.snapshot.value.batteryPct!!
        world.tick(1_000)
        assertEquals(arrivalPct, world.snapshot.value.batteryPct!!)
        world.setCharging(true)
        world.tick(1_000)
        assertTrue(world.snapshot.value.batteryPct!! in (arrivalPct + 9f)..(arrivalPct + 11f))
        world.setCharging(false)
        val paused = world.snapshot.value.batteryPct!!
        world.tick(1_000)
        assertEquals(paused, world.snapshot.value.batteryPct!!)
        world.setCharging(true)
        repeat(3) { world.tick(1_000) }
        assertTrue(world.snapshot.value.batteryPct!! >= stop.targetPct!!)
        assertEquals(stop.station.location, world.location.value)
        trip.leaveChargingStop()
        assertNull(world.arrivedStop.value)
        val finalLeg = withTimeout(2_000) {
            trip.route.filterNotNull().first { it.remainingMin != null && it.remainingKm == finalLegKm }
        }
        assertEquals(CampingEnergyPlanner.drivingMinutes(finalLegKm), finalLeg.remainingMin)
        repeat(40) { world.tick(1_000) }
        assertTrue(Geo.distanceM(world.location.value!!, spot.location) < spot.radiusM)
        assertTrue(trip.navigateTo(spot, stop))
        trip.cancelRoute()
        assertNull(world.chargingStop.value)
        assertNull(world.destination.value)
    }

    @Test
    fun leavingEarlyAndCancellingAtStationClearCharging() = runBlocking {
        val spot = repos.spots.get("s-003")!!
        val stop = com.example.meomunjari.core.navi.ChargingStop(mockChargingStations.first(), 15, 70)
        repeat(2) { attempt ->
            world.setLocation(stop.station.location)
            trip.navigateTo(spot, stop)
            world.setAutoDrive(true)
            world.tick(1_000)
            assertEquals(stop, trip.arrivedStop.value)
            world.setCharging(true)
            if (attempt == 0) {
                trip.leaveChargingStop()
                assertEquals(spot.id, world.destination.value?.spotId)
            } else {
                trip.cancelRoute()
                assertNull(world.destination.value)
            }
            assertNull(trip.arrivedStop.value)
            assertEquals(com.example.meomunjari.core.vehicle.ChargeState.IDLE, world.snapshot.value.chargeState)
        }
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

        // 5. 스팟 근처로 이동 + P단 → 5초 유지 후 도착 인증
        world.setLocation(Geo.offsetNorth(spot.location, 60.0))
        world.setGear(Gear.PARK)
        trip.evaluateArrival()
        assertNull(trip.certifiedVisit.value)
        clock += 5_500
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
        world.setBattery(62f)
        val started = trip.startCamping(master, includeDoor = false, loadedPresetId = master.id, devices = listOf("전기장판"))
        assertTrue(started is StartCampingResult.Started)
        val results = (started as StartCampingResult.Started).state.applyResults.associate { it.item to it.status }
        assertEquals(ApplyStatus.APPLIED, results[ControlItem.HVAC])
        assertEquals(ApplyStatus.UNSUPPORTED, results[ControlItem.WINDOW])
        assertEquals(22f, world.snapshot.value.hvacTargetC)
        assertEquals(38, repos.presets.forSpot("s-003").first { it.id == master.id }.loadCount)

        // 7. 밤새 기록: 1초 = 10분 가속, 8시간 = 48샘플. 중간에 비.
        repeat(48) { i ->
            clock += 1_000
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
        val comparison = finished.batteryComparison!!
        assertEquals(480L, comparison.stayMinutes)
        assertEquals(62 - 5.8 / 72.6 * 100, comparison.predictedMorningPct!!, 0.01)
        assertEquals(world.snapshot.value.batteryPct!!.toDouble(), comparison.endPct!!, 0.001)
        val report = finished.report!!
        assertEquals(comparison, report.batteryComparison)
        assertEquals(8.0, report.durationHours, 0.01)
        assertTrue("공조 8시간이면 배터리가 10~20% 줄어야 한다: ${report.energyUsedPct}", report.energyUsedPct in 10.0..20.0)
        assertTrue(report.rained)
        assertTrue(report.minCabinTempC < 21.0)
        // 외기 7°C에서 밤사이 내려간 최저·최고, 고른 외부 기기, 작성자
        assertEquals(7.0, report.outsideMaxC!!, 0.6)
        assertTrue("밤사이 외기가 내려가야 한다: ${report.outsideMinC}", report.outsideMinC!! < 5.0)
        assertEquals(listOf("전기장판"), report.devices)
        assertEquals("주성", report.author)
        assertTrue(report.summaryLine().startsWith("11월 3일 · 외부 "))
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
        world.setLocation(Geo.offsetNorth(spot.location, 2_500.0))
        trip.evaluateArrival()
        world.setLocation(Geo.offsetNorth(spot.location, 60.0))
        trip.evaluateArrival()
        clock += 5_500
        trip.evaluateArrival()
        assertNotNull("다시 찾아오면 인증돼야 한다", trip.certifiedVisit.value)
    }

    @Test
    fun earlyFinishUsesLatestBatteryAndPreservesOriginalForecastAcrossSpeedChanges() = runBlocking {
        startNight()
        clock += 3_000 // 30 simulated minutes
        trip.setTimeScale(60)
        clock += 5_000 // 5 more minutes, without a new sample
        world.setBattery(55f)
        val finished = trip.endCamping()!!
        val comparison = finished.batteryComparison!!
        assertEquals(62 - 5.8 / 72.6 * 100, comparison.predictedMorningPct!!, 0.01)
        assertEquals(55.0, comparison.endPct!!, 0.0)
        assertEquals(35L, comparison.stayMinutes)
        assertNull(finished.report)
    }

    @Test
    fun guardRetriesAfterRefusalAndConfirmsPowerOff() = runBlocking {
        startNight()
        world.setBattery(10f)
        world.setCapability(ControlItem.HVAC, false)
        clock += 1_000
        trip.recordSample(trip.camping.value!!)
        assertTrue(world.snapshot.value.hvacOn)
        assertTrue(!trip.camping.value!!.protectionTriggered)
        assertTrue(trip.camping.value!!.notice!!.contains("실패"))
        world.setCapability(ControlItem.HVAC, true)
        clock += 1_000
        trip.recordSample(trip.camping.value!!)
        assertTrue(!world.snapshot.value.hvacOn)
        assertTrue(trip.camping.value!!.protectionTriggered)
    }

    @Test
    fun guardDoesNotClaimSuccessWithoutPowerStateConfirmation() = runBlocking {
        startNight()
        world.setBattery(10f)
        ignorePowerCommand = true
        clock += 1_000
        trip.recordSample(trip.camping.value!!)
        assertTrue(world.snapshot.value.hvacOn)
        assertTrue(!trip.camping.value!!.protectionTriggered)
        assertTrue(trip.camping.value!!.notice!!.contains("확인하지 못했어요"))
    }

    @Test
    fun reviewKeepsWakeTimeChosenBeforeCamping() = runBlocking {
        val start = java.time.LocalDateTime.of(2026, 11, 3, 23, 0)
        clock = start.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        trip.selectWakeTime(java.time.LocalTime.of(6, 30))
        startNight()
        assertEquals(450, trip.camping.value!!.plannedMinutes)
        val comparison = trip.endCamping()!!.batteryComparison!!
        assertEquals("06:30", comparison.plannedWakeTime)
        assertEquals(450, comparison.plannedMinutes)
        assertEquals(62 - 5.45 / 72.6 * 100, comparison.predictedMorningPct!!, 0.001)
        assertNull(trip.wakeTime.value)
    }

    @Test
    fun differentCabinSetpointKeepsWakeEstimateUnknownDespiteSpotReports() = runBlocking {
        val setting = repos.user.user.value.mySetting.copy(targetTempC = 19.0)
        startNight(setting)
        assertEquals(19f, world.snapshot.value.hvacTargetC)
        assertNull(trip.camping.value!!.initialMorningPct)
        assertNull(trip.endCamping()!!.batteryComparison!!.predictedMorningPct)
    }

    private suspend fun startNight(setting: SettingPreset? = null) {
        val spot = repos.spots.get("s-003")!!
        world.setLocation(spot.location)
        world.setGear(Gear.PARK)
        trip.evaluateArrival()
        clock += 5_500
        trip.evaluateArrival()
        trip.setConsent(false)
        world.setBattery(62f)
        val preset = setting ?: repos.presets.forSpot(spot.id).first { it.hvacOn }
        assertTrue(trip.startCamping(preset, false) is StartCampingResult.Started)
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
