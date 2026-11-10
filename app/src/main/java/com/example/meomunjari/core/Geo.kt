package com.example.meomunjari.core

import com.example.meomunjari.core.model.LatLng
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** 도로 거리 ≈ 직선거리 × 1.3 (내비 값이 없을 때의 추정치). */
    const val ROAD_FACTOR = 1.3

    fun distanceM(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** a에서 b로의 방위각(북=0°, 시계방향). */
    fun bearingDeg(a: LatLng, b: LatLng): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** a에서 b 방향으로 fraction(0~1)만큼 이동한 지점. 짧은 거리용 선형 보간. */
    fun interpolate(a: LatLng, b: LatLng, fraction: Double): LatLng =
        LatLng(a.lat + (b.lat - a.lat) * fraction, a.lng + (b.lng - a.lng) * fraction)

    /** 북쪽으로 meters만큼 떨어진 지점. */
    fun offsetNorth(p: LatLng, meters: Double): LatLng =
        LatLng(p.lat + meters / 111_320.0, p.lng)

    fun formatDistance(meters: Double): String =
        if (meters < 1000) "${meters.roundToInt()}m" else "${((meters / 100).roundToInt() / 10.0)}km"
}
