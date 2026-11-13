package com.example.meomunjari.domain

import com.example.meomunjari.core.Geo
import com.example.meomunjari.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt

class SunOrientationTest {
    private val inje = LatLng(37.8960, 128.3480)

    @Test
    fun sunriseNovemberInGangwonIsEastSoutheast() {
        val sun = SunCalculator.sunrise(inje, LocalDate.of(2026, 11, 6))!!
        // 서울 일출(약 07:01)보다 동쪽인 인제는 5~6분 빠르다. 방위는 동남동(약 110°)
        val t = sun.time
        val minutes = t.hour * 60 + t.minute
        assertTrue("일출 $t", minutes in (6 * 60 + 50)..(7 * 60))
        assertTrue("방위 ${sun.azimuthDeg}", sun.azimuthDeg in 105.0..115.0)
    }

    @Test
    fun equinoxSunriseIsDueEast() {
        val sun = SunCalculator.sunrise(inje, LocalDate.of(2026, 3, 20))!!
        assertEquals(90.0, sun.azimuthDeg, 2.0)
    }

    @Test
    fun summerSunriseIsNorthOfEast() {
        val sun = SunCalculator.sunrise(inje, LocalDate.of(2026, 6, 21))!!
        assertTrue(sun.azimuthDeg in 55.0..65.0)
        assertTrue(sun.time.hour == 5)
    }

    @Test
    fun adviceTellsHowMuchToTurn() {
        val date = LocalDate.of(2026, 11, 6)
        val sun = SunCalculator.sunrise(inje, date)!!
        val az = sun.azimuthDeg

        val windshield = ParkingAdvisor.advise(sun, SunriseMode.WINDSHIELD, currentHeadingDeg = az - 40)
        assertEquals(40.0, windshield.turnDeg!!, 0.01)
        assertTrue(windshield.instruction().startsWith("오른쪽으로 40°"))

        val tailgate = ParkingAdvisor.advise(sun, SunriseMode.TAILGATE, currentHeadingDeg = (az + 180 + 10) % 360)
        assertTrue(tailgate.aligned)

        val unknown = ParkingAdvisor.advise(sun, SunriseMode.WINDSHIELD, null)
        assertEquals("차 앞머리를 동남동(${az.roundToInt()}°)으로 두세요", unknown.instruction())
    }

    @Test
    fun nextSunriseUsesTheSpotDateEvenBeforeLocalDawn() {
        val beforeDawn = Instant.parse("2026-11-05T18:00:00Z") // 인제는 11월 6일 03시
        val today = SunCalculator.nextSunrise(inje, beforeDawn)!!
        assertEquals(LocalDate.of(2026, 11, 6), today.time.toLocalDate())
        assertEquals(today, SunCalculator.nextSunrise(inje, today.time.minusSeconds(1).toInstant()))
        val tomorrow = SunCalculator.nextSunrise(inje, today.time.toInstant())!!
        assertEquals(today.time.toLocalDate().plusDays(1), tomorrow.time.toLocalDate())
        assertTrue(tomorrow.localDateTimeLabel.startsWith("11월 7일 "))
        assertNull(SunCalculator.nextSunrise(LatLng(90.0, 128.0), beforeDawn))
    }

    @Test
    fun sleepInAndTailgatePointAwayFromSunAndUseShortestTurnAcrossNorth() {
        val sun = SunCalculator.sunrise(inje, LocalDate.of(2026, 11, 6))!!
        for (mode in listOf(SunriseMode.SLEEP_IN, SunriseMode.TAILGATE)) {
            val advice = ParkingAdvisor.advise(sun, mode, currentHeadingDeg = 10.0)
            assertEquals(sun.azimuthDeg + 180.0, advice.targetHeadingDeg, 0.001)
            assertTrue(advice.instruction().startsWith("왼쪽으로"))
        }
        val aligned = ParkingAdvisor.advise(sun, SunriseMode.WINDSHIELD, sun.azimuthDeg + 15)
        assertTrue(aligned.aligned)
    }

    @Test
    fun reversingNeedsAFreshSegmentAndRotatesCourseIntoFrontHeading() {
        val tracker = HeadingTracker()
        tracker.onLocation(inje, moving = true, sdkBearingDeg = 90.0)
        val east = LatLng(inje.lat, inje.lng + 0.001)
        tracker.onLocation(east, moving = true, reversing = true)
        tracker.onLocation(Geo.offsetNorth(east, 5.0), moving = true, reversing = true)
        assertNull(tracker.headingDeg)
        tracker.onLocation(Geo.offsetNorth(east, 40.0), moving = true, reversing = true)
        assertEquals(180.0, tracker.headingDeg!!, 1.0)
        tracker.flip()
        assertEquals(0.0, tracker.headingDeg!!, 1.0)
        tracker.reset()
        assertNull(tracker.headingDeg)
    }

    @Test
    fun headingFromLastDrivingSegment() {
        val tracker = HeadingTracker()
        val start = LatLng(37.8900, 128.3400)
        val east = LatLng(37.8900, 128.3410) // 동쪽으로 약 88m
        tracker.onLocation(start, moving = true)
        tracker.onLocation(east, moving = true)
        assertEquals(90.0, tracker.headingDeg!!, 1.0)
        // 정차 후 GPS가 튀어도 방향을 바꾸지 않는다
        tracker.onLocation(Geo.offsetNorth(east, 50.0), moving = false)
        assertEquals(90.0, tracker.headingDeg!!, 1.0)
    }
}
