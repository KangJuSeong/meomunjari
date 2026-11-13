package com.example.meomunjari.core.model

import kotlin.math.abs

/** One polygon has an outer ring followed by zero or more holes. Coordinates are WGS84. */
data class RestrictedZone(
    val name: String,
    val reason: String,
    val polygons: List<List<List<LatLng>>>,
) {
    fun contains(point: LatLng): Boolean = polygons.any { rings ->
        val outer = ringPosition(point, rings.first())
        outer == Position.EDGE || (outer == Position.INSIDE && rings.drop(1).none {
            ringPosition(point, it) == Position.INSIDE
        })
    }

    private enum class Position { OUTSIDE, INSIDE, EDGE }

    private fun ringPosition(p: LatLng, ring: List<LatLng>): Position {
        var inside = false
        for (i in 1 until ring.size) {
            val a = ring[i - 1]
            val b = ring[i]
            val cross = (p.lng - a.lng) * (b.lat - a.lat) - (p.lat - a.lat) * (b.lng - a.lng)
            if (abs(cross) < 1e-15 && p.lng in minOf(a.lng, b.lng)..maxOf(a.lng, b.lng) &&
                p.lat in minOf(a.lat, b.lat)..maxOf(a.lat, b.lat)) return Position.EDGE
            if ((a.lat > p.lat) != (b.lat > p.lat)) {
                val crossingLng = a.lng + (p.lat - a.lat) * (b.lng - a.lng) / (b.lat - a.lat)
                if (p.lng < crossingLng) inside = !inside
            }
        }
        return if (inside) Position.INSIDE else Position.OUTSIDE
    }
}
