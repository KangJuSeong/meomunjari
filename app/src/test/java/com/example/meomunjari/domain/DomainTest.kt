package com.example.meomunjari.domain

import com.example.meomunjari.Fixtures
import com.example.meomunjari.core.Geo
import com.example.meomunjari.core.model.LatLng
import com.example.meomunjari.core.model.PowerType
import com.example.meomunjari.core.vehicle.Gear
import com.example.meomunjari.core.vehicle.VehicleSnapshot
import com.example.meomunjari.domain.camping.CampingRecorder
import com.example.meomunjari.domain.camping.CampingSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import com.example.meomunjari.data.mock.toRepositories
import org.junit.Test

class DomainTest {
    private val seed = Fixtures.seed()
    private val tags = TagNormalizer(seed.tags)
    private val home = LatLng(38.2070, 128.5918)

    @Test
    fun tagSynonymsCollapseToCanonical() {
        assertEquals("#바다뷰", tags.normalize("오션뷰"))
        assertEquals("#바다뷰", tags.normalize("#바다 전망"))
        assertEquals("#별보기", tags.normalize("#은하수"))
        assertEquals("#솔캠", tags.normalize("#솔캠"))
    }

    @Test
    fun demoStep1_starsAndDogsFitMyCar() {
        val q = SpotQuery(tags = setOf("#별보기", "#반려견동반"), fitMyCar = true)
        val ids = SpotSearch.search(seed.spots, q, home, seed.user.car.vehicleClass).map { it.spot.id }
        assertTrue("s-003" in ids)
        assertTrue("s-006" in ids)
        assertFalse("소형 SUV까지만 들어가는 s-004는 중형 SUV에서 빠져야 한다", "s-004" in ids)

        val all = SpotSearch.search(seed.spots, q.copy(fitMyCar = false), home, seed.user.car.vehicleClass).map { it.spot.id }
        assertTrue("s-004" in all)
    }

    @Test
    fun demoStep2_reportHeadline() {
        val summary = ReportAggregator.summarize(seed.reports, "s-003", 11, PowerType.EV)
        assertEquals(12, summary.sampleCount)
        assertEquals(9.0, summary.minCabinTempC!!, 0.01)
        assertEquals(8.0, summary.avgHvacHours!!, 0.05)
        assertEquals(14.0, summary.avgEnergyUsedPct!!, 0.05)
        assertEquals(3, summary.rainyCount)
        assertTrue(summary.reliable)
        assertEquals(
            "11월 기준 새벽 실내 최저 9°C · 공조 8시간 사용 시 배터리 14% 소모 · 기록 12회 중 비 온 날 3회",
            summary.headline(),
        )
    }

    @Test
    fun fuelForecastUsesOnlyIceReportsAndRetainsSeasonalFallback() {
        val car = seed.user.car.copy(powerType = PowerType.ICE)
        val records = seed.reports.take(3).map { it.copy(powerType = PowerType.ICE, energyUsedPct = 12.0, durationHours = 8.0, hvacHours = 8.0) }
        val summary = ReportAggregator.summarize(records, "s-003", 11, PowerType.ICE)
        val measured = EnergyPredictor.fuelForecast(62.0, 0.0, car, summary, 11, 10.0)!!
        assertEquals(ForecastBasis.MEASURED, measured.basis)
        assertEquals(50.0, measured.morningPct, 0.0001)
        assertEquals(Verdict.ENOUGH, measured.verdict)
        val sparse = ReportAggregator.summarize(records.take(1), "s-003", 11, PowerType.ICE)
        val low = EnergyPredictor.fuelForecast(20.0, 0.0, car, sparse, 11, 10.0)!!
        assertEquals(ForecastBasis.REFERENCE, low.basis)
        assertEquals(Verdict.REFUEL_ON_ROUTE, low.verdict)

        val evSummary = ReportAggregator.summarize(seed.reports, "s-003", 11, PowerType.EV)
        val fallback = EnergyPredictor.fuelForecast(80.0, 0.0, car, evSummary, 11, 10.0)!!
        assertEquals(ForecastBasis.SEASONAL_DEFAULT, fallback.basis)
        assertEquals(0.8 * 8, fallback.overnightPct, 0.0001)
        org.junit.Assert.assertNull(EnergyPredictor.fuelForecast(62.0, 0.0, seed.user.car, evSummary, 11, 10.0))
    }

    @Test
    fun arrivalNeedsRadiusParkAndHold() {
        val spot = seed.spots.first { it.id == "s-003" }
        val near = Geo.offsetNorth(spot.location, 60.0)
        val v = ArrivalVerifier()
        val parked = VehicleSnapshot(gear = Gear.PARK, speedKmh = 0f)
        val driving = VehicleSnapshot(gear = Gear.DRIVE, speedKmh = 0f)

        assertFalse(v.evaluate(spot, near, driving, 0, false).certified)
        assertFalse(v.evaluate(spot, near, parked, 1_000, false).certified)
        assertFalse(v.evaluate(spot, near, parked, 5_900, false).certified)
        val ok = v.evaluate(spot, near, parked, 6_100, false)
        assertTrue(ok.certified && ok.justCertified)

        val far = Geo.offsetNorth(spot.location, 1_500.0)
        assertFalse(ArrivalVerifier().evaluate(spot, far, parked, 10_000, false).withinRadius)
    }

    @Test
    fun badgesFromSeedUser() {
        val status = BadgeEngine.evaluate(seed.user, seed.spots)
        val byType = status.badges.associateBy { it.type }
        assertEquals(7, byType.getValue(BadgeType.EXPLORER).current)
        assertEquals(3, byType.getValue(BadgeType.FOUR_SEASONS).current)
        assertEquals(4, byType.getValue(BadgeType.THEME_MASTER).current)
        assertTrue(byType.getValue(BadgeType.THEME_MASTER).detail.startsWith("#별보기"))
        assertEquals(8, byType.getValue(BadgeType.RECORDER).current)
        assertTrue(byType.getValue(BadgeType.REGION).earned)
        assertTrue(byType.getValue(BadgeType.PIONEER).earned)
        assertEquals(310, status.points)
        assertEquals(Grade.EXPERT, status.grade)
    }

    @Test
    fun masterPresetIsTopRatedOther() {
        val master = SettingCompatibility.master(seed.presets.filter { it.spotId == "s-003" }, seed.user.nickname)
        assertNotNull(master)
        assertEquals("별헌터", master!!.ownerNickname)
        assertEquals("22°C, 시트 메모리 2번 · 등받이 150°, 창문 5% 열기, 조명 끄기", SettingCompatibility.summary(master))
    }

    @Test
    fun recorderBuildsReport() {
        val spot = seed.spots.first { it.id == "s-003" }
        val step = CampingRecorder.SAMPLE_INTERVAL_SIM_MS
        val samples = (0..48).map { i ->
            CampingSample(i * step, 21f - (i % 10) * 0.3f, 62f - i * 0.29f, hvacOn = true, wiperActive = i == 20)
        }
        val r = CampingRecorder.buildReport("r", spot, samples, seed.user.car, 11, "2026-11-03")!!
        assertEquals(8.0, r.durationHours, 0.01)
        assertEquals(8.0, r.hvacHours, 0.01)
        assertEquals(13.9, r.energyUsedPct, 0.1)
        assertEquals(18.3, r.minCabinTempC, 0.05)
        assertTrue(r.rained)
    }

    @Test
    fun reportCarriesOutsideRangeDevicesAndLine() {
        val spot = seed.spots.first { it.id == "s-003" }
        val step = CampingRecorder.SAMPLE_INTERVAL_SIM_MS
        val samples = (0..54).map { i ->
            CampingSample(i * step, 21f, 62f - i * 0.25f, hvacOn = true, wiperActive = false, outsideTempC = 6f - i * 0.15f)
        }
        val r = CampingRecorder.buildReport("r", spot, samples, seed.user.car, 11, "2026-11-08", author = "주성", devices = listOf("전기장판"))!!
        assertEquals(6.0, r.outsideMaxC!!, 0.01)
        assertEquals(-2.1, r.outsideMinC!!, 0.01)
        assertEquals(listOf("전기장판"), r.devices)
        assertEquals("11월 8일 · 외부 −2~6°C · 실내 21°C · 배터리 −14% · 9시간 · 전기장판", r.summaryLine())
        assertTrue(r.summaryLine(java.time.LocalDate.of(2027, 11, 13)).startsWith("2026년 11월 8일 · "))
        assertEquals("외부 −2~6°C · 실내 21°C · 배터리 −14% · 9시간", r.valuesLine())
    }

    @Test
    fun nearReportsUseTwoWeeksAroundTodayIgnoringYear() {
        val today = java.time.LocalDate.of(2026, 11, 13)
        val near = ReportAggregator.near(seed.reports, "s-003", today)
        val dates = near.map { it.createdAt }
        // 작년 같은 시기(2025-11-08, -15, -22, -02)도 들어오고, 10월 13~15일(29일 이상 차이)은 빠진다.
        assertTrue("2025-11-15" in dates)
        assertTrue("2025-11-22" in dates)
        assertFalse(dates.any { it.startsWith("2026-10") })
        assertEquals(12, near.size)
        // 오늘과 날짜가 가까운 순
        assertEquals(listOf("2026-11-11", "2025-11-15", "2026-11-10"), dates.take(3))

        // 연말·연초를 넘어가는 경우
        assertEquals(3, ReportAggregator.dayGap(java.time.LocalDate.of(2025, 12, 30), java.time.LocalDate.of(2026, 1, 2)))

        val s = ReportAggregator.summarizeNear(seed.reports, "s-003", today, PowerType.EV)
        assertEquals(12, s.sampleCount)
        assertEquals(near.mapNotNull { it.outsideMinC }.min(), s.minOutsideC!!, 0.001)
    }

    @Test
    fun oneReportPerUserPerSpot() {
        val repos = Fixtures.seed().toRepositories()
        val before = repos.reports.forSpot("s-003").size
        val base = repos.reports.forSpot("s-003").first { it.author == "별헌터" }
        repos.reports.add(base.copy(id = "new", createdAt = "2026-11-12"))
        val mine = repos.reports.forSpot("s-003").filter { it.author == "별헌터" }
        assertEquals(before, repos.reports.forSpot("s-003").size)
        assertEquals(listOf("new"), mine.map { it.id })
    }
}
