package com.example.meomunjari.domain

import com.example.meomunjari.core.Geo
import com.example.meomunjari.core.model.LatLng
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

data class SunEvent(
    /** 일출 시각(현지 시간). 극지방처럼 해가 안 뜨거나 지지 않으면 null. */
    val time: ZonedDateTime?,
    /** 일출 방위각. 북=0°, 동=90°, 시계방향. */
    val azimuthDeg: Double,
)

/**
 * NOAA 태양 위치 근사식으로 일출 시각과 방위를 계산한다(오차 1~2분, 1° 내외).
 * 지형(산·건물)에 가려지는 것은 고려하지 않는 '평지 지평선' 기준.
 */
object SunCalculator {
    /** 대기 굴절 + 태양 반지름 보정. */
    private const val SUNRISE_ZENITH = 90.833

    fun sunrise(location: LatLng, date: LocalDate, zone: ZoneId = ZoneId.of("Asia/Seoul")): SunEvent {
        val lat = Math.toRadians(location.lat)
        val gamma = 2 * Math.PI / 365.0 * (date.dayOfYear - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
            0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) - 0.006758 * cos(2 * gamma) +
            0.000907 * sin(2 * gamma) - 0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)

        val zenith = Math.toRadians(SUNRISE_ZENITH)
        val cosHa = cos(zenith) / (cos(lat) * cos(decl)) - tan(lat) * tan(decl)

        // 해가 지평선에 걸린 순간의 방위각: cos(Az) = (sin δ − sin φ · cos z) / (cos φ · sin z)
        val cosAz = ((sin(decl) - sin(lat) * cos(zenith)) / (cos(lat) * sin(zenith))).coerceIn(-1.0, 1.0)
        val azimuth = Math.toDegrees(acos(cosAz))

        val time = if (cosHa in -1.0..1.0) {
            val haDeg = Math.toDegrees(acos(cosHa))
            val utcMinutes = 720 - 4 * (location.lng + haDeg) - eqTime
            val utc = date.atStartOfDay(ZoneId.of("UTC")).plusSeconds((utcMinutes * 60).roundToInt().toLong())
            utc.withZoneSameInstant(zone)
        } else {
            null
        }
        return SunEvent(time, azimuth)
    }
}

enum class SunriseMode(val label: String, val description: String) {
    WINDSHIELD("앞유리로 보기", "누운 채 앞유리 너머로 해가 떠요"),
    TAILGATE("트렁크 열고 보기", "트렁크를 열면 정면에서 해가 떠요"),
    SLEEP_IN("늦잠 자기", "아침 햇살이 얼굴에 바로 들지 않아요"),
}

data class ParkingAdvice(
    val sunrise: SunEvent,
    val mode: SunriseMode,
    /** 차 앞머리(헤드)가 향해야 할 방위. */
    val targetHeadingDeg: Double,
    /** 지금 차 앞머리 방위. 모르면 null. */
    val currentHeadingDeg: Double?,
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
    fun advise(location: LatLng, date: LocalDate, mode: SunriseMode, currentHeadingDeg: Double?): ParkingAdvice {
        val sun = SunCalculator.sunrise(location, date)
        val target = when (mode) {
            SunriseMode.WINDSHIELD -> sun.azimuthDeg
            SunriseMode.TAILGATE, SunriseMode.SLEEP_IN -> (sun.azimuthDeg + 180.0) % 360.0
        }
        return ParkingAdvice(sun, mode, target, currentHeadingDeg)
    }
}

/**
 * 차 앞머리 방향 추정. 정차 직전 주행 궤적(마지막 30m 이상 이동)의 방위를 쓴다.
 * 차량·위치 SDK가 방위(bearing)를 직접 주면 그 값을 우선한다.
 */
class HeadingTracker(private val minSegmentM: Double = 30.0) {
    private var anchor: LatLng? = null
    var headingDeg: Double? = null
        private set

    /** 후진으로 들어와 주차한 경우: 추정 방향을 반대로 뒤집는다. */
    fun flip() {
        headingDeg = headingDeg?.let { (it + 180.0) % 360.0 }
    }

    fun onLocation(location: LatLng, moving: Boolean, sdkBearingDeg: Double? = null) {
        if (sdkBearingDeg != null && moving) {
            headingDeg = sdkBearingDeg
            anchor = location
            return
        }
        val a = anchor
        if (a == null) {
            anchor = location
            return
        }
        if (!moving) return
        if (Geo.distanceM(a, location) >= minSegmentM) {
            headingDeg = Geo.bearingDeg(a, location)
            anchor = location
        }
    }
}
