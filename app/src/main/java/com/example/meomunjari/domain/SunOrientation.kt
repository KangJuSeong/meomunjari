package com.example.meomunjari.domain

import com.example.meomunjari.core.Geo
import com.example.meomunjari.core.model.LatLng
import java.time.LocalDate
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

data class SunEvent(
    val time: ZonedDateTime,
    /** 일출 방위각. 북=0°, 동=90°, 시계방향. */
    val azimuthDeg: Double,
) {
    val localDateTimeLabel: String get() = time.format(localFormat)
    private companion object {
        val localFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("M월 d일 HH:mm")
    }
}

/**
 * NOAA 태양 위치 근사식으로 일출 시각과 방위를 계산한다.
 * 지형(산·건물)에 가려지는 것은 고려하지 않는 '평지 지평선' 기준.
 */
object SunCalculator {
    /** 대기 굴절 + 태양 반지름 보정. */
    private const val SUNRISE_ZENITH = 90.833

    val koreaZone: ZoneId = ZoneId.of("Asia/Seoul")

    /** 국내 스팟에서 오늘 또는 내일 관측 가능한 다음 일출. */
    fun nextSunrise(location: LatLng, now: Instant): SunEvent? {
        val date = now.atZone(koreaZone).toLocalDate()
        val today = sunrise(location, date)
        return today?.takeIf { it.time.toInstant() > now } ?: sunrise(location, date.plusDays(1))
    }

    fun sunrise(location: LatLng, date: LocalDate, zone: ZoneId = koreaZone): SunEvent? {
        if (location.lat !in -90.0..90.0 || location.lng !in -180.0..180.0) return null
        val lat = Math.toRadians(location.lat)
        val gamma = 2 * Math.PI / date.lengthOfYear() * (date.dayOfYear - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
            0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) - 0.006758 * cos(2 * gamma) +
            0.000907 * sin(2 * gamma) - 0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)

        val zenith = Math.toRadians(SUNRISE_ZENITH)
        val cosHa = cos(zenith) / (cos(lat) * cos(decl)) - tan(lat) * tan(decl)

        if (cosHa !in -1.0..1.0) return null

        // 해가 지평선에 걸린 순간의 방위각: cos(Az) = (sin δ − sin φ · cos z) / (cos φ · sin z)
        val cosAz = ((sin(decl) - sin(lat) * cos(zenith)) / (cos(lat) * sin(zenith))).coerceIn(-1.0, 1.0)
        val azimuth = Math.toDegrees(acos(cosAz))

        val haDeg = Math.toDegrees(acos(cosHa))
        val utcMinutes = 720 - 4 * (location.lng + haDeg) - eqTime
        val utc = date.atStartOfDay(ZoneId.of("UTC")).plusSeconds((utcMinutes * 60).roundToInt().toLong())
        return SunEvent(utc.withZoneSameInstant(zone), azimuth)
    }
}

enum class SunriseMode(val label: String, val description: String) {
    WINDSHIELD("앞유리로 보기", "누운 채 앞유리 너머로 해가 떠요"),
    TAILGATE("트렁크 열고 보기", "트렁크를 열면 정면에서 해가 떠요"),
    SLEEP_IN("늦잠 자기", "앞유리가 해 뜨는 반대쪽을 향해요"),
}

data class ParkingAdvice(
    val sunrise: SunEvent,
    val mode: SunriseMode,
    /** 차 앞머리(헤드)가 향해야 할 방위. */
    val targetHeadingDeg: Double,
    /** 지금 차 앞머리 방위. 모르면 null. */
    val currentHeadingDeg: Double?,
    val headingFlipped: Boolean = false,
) {
    /** 양수 = 오른쪽(시계방향)으로 더 돌리기. -180~180. */
    val turnDeg: Double? get() = currentHeadingDeg?.let { normalize(targetHeadingDeg - it) }

    val aligned: Boolean get() = turnDeg?.let { abs(it) <= ALIGNED_TOLERANCE } ?: false

    fun instruction(): String {
        val turn = turnDeg ?: return "차 앞머리를 ${compass(targetHeadingDeg)}(${targetHeadingDeg.roundToInt()}°)으로 두세요"
        return when {
            aligned -> "좋아요, 딱 맞게 세웠어요"
            turn > 0 -> "오른쪽으로 ${turn.roundToInt()}° 더 돌려 세우세요"
            else -> "왼쪽으로 ${(-turn).roundToInt()}° 더 돌려 세우세요"
        }
    }

    companion object {
        const val ALIGNED_TOLERANCE = 15.0

        fun normalize(deg: Double): Double {
            var d = (deg + 540.0) % 360.0 - 180.0
            if (d == -180.0) d = 180.0
            return d
        }

        fun compass(deg: Double): String {
            val names = listOf("북", "북북동", "북동", "동북동", "동", "동남동", "남동", "남남동", "남", "남남서", "남서", "서남서", "서", "서북서", "북서", "북북서")
            return names[(((deg % 360) + 360) % 360 / 22.5 + 0.5).toInt() % 16]
        }
    }
}

object ParkingAdvisor {
    fun advise(sun: SunEvent, mode: SunriseMode, currentHeadingDeg: Double?, headingFlipped: Boolean = false): ParkingAdvice {
        val target = when (mode) {
            SunriseMode.WINDSHIELD -> sun.azimuthDeg
            SunriseMode.TAILGATE, SunriseMode.SLEEP_IN -> (sun.azimuthDeg + 180.0) % 360.0
        }
        return ParkingAdvice(sun, mode, target, currentHeadingDeg, headingFlipped)
    }
}

/**
 * 차 앞머리 방향 추정. 정차 직전 주행 궤적(마지막 30m 이상 이동)의 방위를 쓴다.
 * 차량·위치 SDK가 방위(bearing)를 직접 주면 그 값을 우선한다.
 */
class HeadingTracker(private val minSegmentM: Double = 30.0) {
    private var anchor: LatLng? = null
    private var reverseSegment: Boolean? = null
    private var frontHeading: Double? = null
    var measuredAt: LatLng? = null
        private set
    var flipped = false
        private set
    val headingDeg: Double? get() = frontHeading?.let { if (flipped) (it + 180.0) % 360.0 else it }

    fun flip() {
        if (frontHeading != null) flipped = !flipped
    }

    fun reset() {
        anchor = null
        reverseSegment = null
        frontHeading = null
        measuredAt = null
        flipped = false
    }

    fun onLocation(location: LatLng, moving: Boolean, sdkBearingDeg: Double? = null, reversing: Boolean = false) {
        if (!moving) {
            // 정차 후에는 마지막 추정치를 보여주고 다음 주행 때 새 궤적을 시작한다.
            reverseSegment = null
            return
        }
        if (reverseSegment != reversing) {
            reset()
            reverseSegment = reversing
            anchor = location
        }
        val a = anchor ?: return
        val course = when {
            sdkBearingDeg != null && sdkBearingDeg >= 0.0 && sdkBearingDeg < 360.0 -> sdkBearingDeg
            Geo.distanceM(a, location) >= minSegmentM -> Geo.bearingDeg(a, location)
            else -> return
        }
        frontHeading = if (reversing) (course + 180.0) % 360.0 else course
        measuredAt = location
        anchor = location
        flipped = false
    }
}
