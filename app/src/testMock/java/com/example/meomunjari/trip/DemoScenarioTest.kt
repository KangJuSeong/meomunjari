package com.example.meomunjari.trip

import com.example.meomunjari.Fixtures
import com.example.meomunjari.core.Gateways
import com.example.meomunjari.core.Geo
import com.example.meomunjari.core.model.Amenities
import com.example.meomunjari.core.model.LatLng
import com.example.meomunjari.core.model.PowerType
import com.example.meomunjari.core.model.SettingPreset
import com.example.meomunjari.core.model.VehicleClass
import com.example.meomunjari.domain.CampingEnergyPlanner
import com.example.meomunjari.core.vehicle.ApplyStatus
import com.example.meomunjari.core.vehicle.ControlItem
import com.example.meomunjari.core.vehicle.Gear
import com.example.meomunjari.data.mock.toRepositories
import com.example.meomunjari.domain.BadgeType
import com.example.meomunjari.domain.Grade
import com.example.meomunjari.domain.DriveGuard
import com.example.meomunjari.domain.DriveState
import com.example.meomunjari.domain.SettingCompatibility
import com.example.meomunjari.domain.SpotQuery
import com.example.meomunjari.domain.SpotSearch
import com.example.meomunjari.domain.TagNormalizer
import com.example.meomunjari.sim.SimulatedWorld
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
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
    fun spotParkedAtLaunchIsNotCertifiedUntilCarLeavesAndReturns() {
        val spot = repos.spots.get("s-003")!!
        trip.skipSpotParkedAtLaunch = true
        world.setGear(Gear.PARK)
        world.setLocation(spot.location)
        trip.evaluateArrival()
        clock += 5_500
        trip.evaluateArrival()
        assertNull("앱을 켤 때 이미 서 있던 스팟은 자동 인증하지 않는다", trip.certifiedVisit.value)

        // 반경의 2배 밖으로 나갔다가 다시 들어오면 정상 인증
        world.setLocation(Geo.offsetNorth(spot.location, 2_500.0))
        trip.evaluateArrival()
        world.setLocation(Geo.offsetNorth(spot.location, 60.0))
        trip.evaluateArrival()
        clock += 5_500
        trip.evaluateArrival()
        assertNotNull(trip.certifiedVisit.value)
    }

    @Test
    fun presetOpensEachWindowToItsLevel() = runBlocking {
        val preset = repos.user.user.value.mySetting.copy(windowLevelByArea = mapOf("ROW_1_LEFT" to 3, "ROW_2_RIGHT" to 10))
        world.apply(preset, includeDoor = false)
        assertEquals(mapOf("ROW_1_LEFT" to 3, "ROW_1_RIGHT" to 0, "ROW_2_LEFT" to 0, "ROW_2_RIGHT" to 10), world.snapshot.value.windowLevelByArea)
    }

    @Test
    fun presetBackrestAnglesAreAppliedPerSeat() = runBlocking {
        val preset = repos.user.user.value.mySetting.copy(backrestPctBySeat = mapOf("ROW_1_LEFT" to 60, "ROW_1_RIGHT" to 40))
        val results = world.apply(preset, includeDoor = false).associate { it.item to it.status }
        assertEquals(ApplyStatus.APPLIED, results[ControlItem.SEAT])
        assertEquals(mapOf("ROW_1_LEFT" to 60, "ROW_1_RIGHT" to 40), world.snapshot.value.seatBackrestPct)
    }

    @Test
    fun restrictedPlaceReportIsRecordedOncePerReporterAndSpot() {
        val spot = repos.spots.get("s-003")!!
        val status = trip.status()
        assertNull(trip.reportRestrictedPlace("missing", "안내판"))
        val first = requireNotNull(trip.reportRestrictedPlace(spot.id, "  야영 금지 안내판  "))
        assertEquals("야영 금지 안내판", first.note)
        assertEquals(repos.user.user.value.nickname, first.reporter)
        assertEquals("2026-11-03", first.createdAt)
        assertEquals(first, trip.reportRestrictedPlace(spot.id, "같은 장소"))
        assertEquals(listOf(first), repos.spots.restrictedPlaceReports.value)
        assertEquals(spot, repos.spots.get(spot.id))
        assertEquals(status, trip.status())
        repos.user.update { it.copy(nickname = "다른 방문자") }
        val second = requireNotNull(trip.reportRestrictedPlace(spot.id, ""))
        assertEquals(listOf(first, second), repos.spots.restrictedPlaceReports.value)
    }

    @Test
    fun lowBatteryRouteLeadsToTheSelectedCampsite() = runBlocking {
        val spot = repos.spots.get("s-003")!!
        val from = world.location.value!!
        world.setBattery(25f)
        assertTrue(trip.navigateTo(spot))
        val distance = CampingEnergyPlanner.roadKm(from, spot.location)
        val route = withTimeout(2_000) { trip.route.filterNotNull().first() }
        assertEquals(distance, route.remainingKm, 0.001)
        assertEquals(CampingEnergyPlanner.drivingMinutes(distance), route.remainingMin)

        world.setAutoDrive(true)
        repeat(40) { world.tick(1_000) }
        assertTrue(Geo.distanceM(world.location.value!!, spot.location) < spot.radiusM)
        assertTrue(world.snapshot.value.batteryPct!! < 25f)
        assertEquals(spot.id, world.destination.value?.spotId)

        trip.cancelRoute()
        assertNull(world.destination.value)
        assertNull(withTimeout(2_000) { trip.route.first { it == null } })
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
        // 외기 7°C에서 밤사이 내려간 최저·최고, 고른 외부 기기
        assertEquals(7.0, report.outsideMaxC!!, 0.6)
        assertTrue("밤사이 외기가 내려가야 한다: ${report.outsideMinC}", report.outsideMinC!! < 5.0)
        assertEquals(listOf("전기장판"), report.devices)
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

        val presetsAfterReview = repos.presets.presets.value
        assertEquals(reviewed, trip.submitReview(1, "다시 눌렀어요", listOf("#일출"), sharePreset = true))
        assertEquals(updatedSpot, repos.spots.get("s-003"))
        assertEquals(presetsAfterReview, repos.presets.presets.value)
        assertEquals(1, repos.reviews.forSpot("s-003").count { it.author == "주성" })
        assertEquals(reviewed.after, trip.status())

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
        assertTrue(trip.camping.value!!.samples.isEmpty())
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
    fun withdrawingConsentDiscardsThisNightEvenWhileBatteryProtectionIsWaiting() = runBlocking {
        startNight(consent = true)
        val spot = repos.spots.get("s-003")!!
        val reportsBefore = repos.reports.forSpot(spot.id)
        clock += 1_000
        trip.recordSample(trip.camping.value!!)
        assertEquals(2, trip.camping.value!!.samples.size)

        world.setBattery(10f)
        ignorePowerCommand = true
        clock += 1_000
        val sample = launch(start = CoroutineStart.UNDISPATCHED) {
            trip.recordSample(trip.camping.value!!)
        }
        assertTrue(sample.isActive)
        trip.setConsent(false)
        assertTrue(trip.camping.value!!.samples.isEmpty())
        trip.setConsent(true)
        world.setHvacPower(false)
        sample.join()

        val current = trip.camping.value!!
        assertTrue(!current.recording)
        assertTrue(current.samples.isEmpty())
        assertTrue(current.protectionTriggered)
        assertEquals(20 * 60_000L, current.elapsedSimMs)
        assertNull(trip.endCamping()!!.report)
        assertEquals(reportsBefore, repos.reports.forSpot(spot.id))

        world.setLocation(Geo.offsetNorth(spot.location, spot.radiusM * 3.0))
        trip.evaluateArrival()
        world.setLocation(spot.location)
        trip.evaluateArrival()
        clock += 5_500
        trip.evaluateArrival()
        assertTrue(trip.startCamping(repos.user.user.value.mySetting, false) is StartCampingResult.Started)
        assertTrue(trip.camping.value!!.recording)
        assertEquals(1, trip.camping.value!!.samples.size)
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

    @Test
    fun changingProfileDuringCampingDoesNotMixBatteryAndFuelMeasurements() = runBlocking {
        startNight(consent = true)
        repos.user.update { it.copy(car = it.car.copy(powerType = PowerType.ICE)) }
        world.setBattery(58f)
        clock += 1_000
        trip.recordSample(trip.camping.value!!)

        val finished = trip.endCamping()!!
        assertEquals(PowerType.EV, finished.report!!.powerType)
        assertEquals(4.0, finished.report!!.measuredEnergyUsedPct!!, 0.0)
        assertEquals(58.0, finished.batteryComparison!!.endPct!!, 0.0)
    }

    private suspend fun startNight(setting: SettingPreset? = null, consent: Boolean = false) {
        val spot = repos.spots.get("s-003")!!
        world.setLocation(spot.location)
        world.setGear(Gear.PARK)
        trip.evaluateArrival()
        clock += 5_500
        trip.evaluateArrival()
        trip.setConsent(consent)
        world.setBattery(62f)
        val preset = setting ?: repos.presets.forSpot(spot.id).first { it.hvacOn }
        assertTrue(trip.startCamping(preset, false) is StartCampingResult.Started)
    }

    @Test
    fun mySeatChangeDuringCampingIsAppliedToCar() = runBlocking {
        val mine = repos.user.user.value.mySetting
        startNight(mine)
        assertEquals(mine.seatMemorySlot, world.snapshot.value.seatMemorySlot)

        // 마이 화면에서 메모리 번호와 조수석 각도를 바꾼 것과 같다.
        repos.user.update { u ->
            u.copy(mySetting = u.mySetting.copy(seatMemorySlot = 3, backrestPctBySeat = mapOf("ROW_1_LEFT" to 25, "ROW_1_RIGHT" to 20)))
        }
        val result = trip.applyMySeatToCamping()
        assertEquals(ApplyStatus.APPLIED, result?.status)
        assertEquals(3, world.snapshot.value.seatMemorySlot)
        assertEquals(mapOf("ROW_1_LEFT" to 25, "ROW_1_RIGHT" to 20), world.snapshot.value.seatBackrestPct)
        assertEquals(3, trip.camping.value!!.preset.seatMemorySlot)
    }

    @Test
    fun seatChangeIsNotPushedWhenCampingUsesAnotherSetting() = runBlocking {
        startNight()
        repos.user.update { u -> u.copy(mySetting = u.mySetting.copy(seatMemorySlot = 3)) }
        assertNull(trip.applyMySeatToCamping())
        assertNotEquals(3, world.snapshot.value.seatMemorySlot)
    }

    @Test
    fun firstRegistrationShowsPioneerAndGradePromotionOnce() = runBlocking {
        repos.user.update { it.copy(registeredSpotIds = emptyList(), visits = emptyList(),
            reportCount = 0, reportCredits = 0, reviewCount = 8) }
        val before = trip.status()
        assertEquals(40, before.points)
        world.setGear(Gear.PARK)
        world.setLocation(LatLng(38.2482, 128.5668))
        val result = trip.registerSpot("해변 공터", "", Amenities(), listOf("#일출"), VehicleClass.MID)
        assertTrue(result is RegisterResult.Registered)
        val after = trip.status()
        assertEquals(100, after.points)
        assertEquals(Grade.CAMPER, after.grade)
        assertTrue(after.badges.single { it.type == BadgeType.PIONEER }.earned)
        assertTrue(trip.startCamping(repos.user.user.value.mySetting, false) is StartCampingResult.Started)
        val finished = trip.endCamping()!!
        assertEquals(before, finished.before)
        assertEquals(after, finished.after)
    }

    @Test
    fun registerBlockedInRestrictedZoneAndAllowedElsewhere() {
        world.setGear(Gear.PARK)
        val protectedLocation = world.locationPresets().single { it.label.contains("상수원보호구역") }.location
        world.setLocation(protectedLocation)
        assertNotNull(trip.restrictedZoneAt(protectedLocation))
        val blocked = trip.registerSpot("보호구역 안", "", Amenities(), emptyList(), VehicleClass.LARGE)
        assertTrue(blocked is RegisterResult.Restricted)

        world.setLocation(LatLng(38.2482, 128.5668))
        assertTrue(trip.isParkedAtUnknownPlace())
        val ok = trip.registerSpot("봉포 해변 공터", "조용한 공터", Amenities(toilet = true),
            listOf("#오션뷰", "#바다뷰 #해변공터, 일출맛집"), VehicleClass.LARGE, "강원도")
        assertTrue(ok is RegisterResult.Registered)
        val spot = (ok as RegisterResult.Registered).spot
        assertEquals(listOf("#바다뷰", "#해변공터", "#일출맛집"), spot.tags.map { it.tag })
        assertEquals("강원도", spot.region)
        val hits = SpotSearch.search(repos.spots.spots.value, SpotQuery(tags = setOf("#바다뷰", "#해변공터")),
            world.location.value, VehicleClass.LARGE)
        assertEquals(listOf(spot.id), hits.map { it.spot.id })
        assertTrue(trip.status().badges.first { it.type == BadgeType.PIONEER }.current == 2)
        assertNotNull(trip.certifiedVisit.value)
    }

    @Test
    fun rejectedRegistrationDoesNotCreateSpotsOrAwardActivity() {
        world.setLocation(LatLng(38.2482, 128.5668))
        fun register(name: String) = trip.registerSpot(name, "", Amenities(), emptyList(), VehicleClass.MID)
        val initial = repos.user.user.value
        val initialSpots = repos.spots.spots.value.size
        assertEquals(RegisterResult.NameRequired, register("  "))
        world.setGear(Gear.DRIVE)
        assertEquals(RegisterResult.NotParked, register("새 스팟"))
        assertEquals(initial, repos.user.user.value)
        assertEquals(initialSpots, repos.spots.spots.value.size)
        world.setGear(Gear.PARK)
        val registered = register("새 스팟") as RegisterResult.Registered
        assertEquals("", registered.spot.region)
        val afterRegistration = repos.user.user.value
        assertEquals(RegisterResult.Duplicate, register("다른 이름"))
        assertEquals(afterRegistration, repos.user.user.value)
        assertEquals(initialSpots + 1, repos.spots.spots.value.size)
    }
}
