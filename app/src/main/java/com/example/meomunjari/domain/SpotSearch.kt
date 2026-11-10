package com.example.meomunjari.domain

import com.example.meomunjari.core.Geo
import com.example.meomunjari.core.model.LatLng
import com.example.meomunjari.core.model.Spot
import com.example.meomunjari.core.model.VehicleClass

enum class SortOrder(val label: String) {
    DISTANCE("가까운 순"),
    RATING("평점 순"),
    VISITS("방문 많은 순"),
    RECENT("최근 등록 순"),
}

data class SpotQuery(
    val tags: Set<String> = emptySet(),
    val fitMyCar: Boolean = true,
    val sort: SortOrder = SortOrder.DISTANCE,
    val maxDistanceKm: Double? = null,
)

data class SpotHit(val spot: Spot, val distanceM: Double?)

object SpotSearch {

    fun search(
        spots: List<Spot>,
        query: SpotQuery,
        origin: LatLng?,
        myClass: VehicleClass,
    ): List<SpotHit> {
        val hits = spots
            .asSequence()
            .filter { spot -> query.tags.all { spot.hasTag(it) } }
            .filter { spot -> !query.fitMyCar || spot.fits(myClass) }
            .map { spot -> SpotHit(spot, origin?.let { Geo.distanceM(it, spot.location) }) }
            .filter { hit ->
                val max = query.maxDistanceKm
                max == null || hit.distanceM == null || hit.distanceM <= max * 1000
            }
            .toList()

        return when (query.sort) {
            SortOrder.DISTANCE -> hits.sortedBy { it.distanceM ?: Double.MAX_VALUE }
            SortOrder.RATING -> hits.sortedByDescending { it.spot.rating }
            SortOrder.VISITS -> hits.sortedByDescending { it.spot.visitCount }
            SortOrder.RECENT -> hits.sortedByDescending { it.spot.createdAt }
        }
    }

    /** 반경 안에 있는 가장 가까운 스팟. */
    fun spotAt(spots: List<Spot>, location: LatLng): Spot? =
        spots
            .map { it to Geo.distanceM(location, it.location) }
            .filter { (spot, d) -> d <= spot.radiusM }
            .minByOrNull { it.second }
            ?.first

    /** "대형 SUV 방문 0회 · 경차·소형 SUV 위주" 같은 다녀간 차종 요약. */
    fun vehicleMix(spot: Spot, myClass: VehicleClass): String {
        val mine = spot.visitedVehicleClasses[myClass] ?: 0
        val top = spot.visitedVehicleClasses.entries
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .take(2)
            .joinToString("·") { it.key.label }
        return if (top.isEmpty()) "아직 방문 기록 없음" else "${myClass.label} 방문 ${mine}회 · $top 위주"
    }
}
