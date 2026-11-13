package com.example.meomunjari.data.mock

import com.example.meomunjari.core.model.LatLng
import com.example.meomunjari.core.model.RestrictedZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** 브이월드 GetFeature 응답에서 WGS84 상수원보호구역 면만 읽는다. */
object WaterProtectionZones {
    const val ASSET = "vworld_water_protection_mock.json"

    fun read(text: String): List<RestrictedZone> {
        val page = Json.parseToJsonElement(text) as? JsonObject ?: return emptyList()
        val response = page["response"] as? JsonObject ?: return emptyList()
        if (response.string("status") != "OK") return emptyList()
        val result = response["result"] as? JsonObject ?: return emptyList()
        val collection = result["featureCollection"] as? JsonObject ?: return emptyList()
        if (collection.string("type") != "FeatureCollection") return emptyList()
        val crs = (collection["crs"] as? JsonObject)?.get("properties") as? JsonObject
        val crsName = crs?.string("name")
        if (crsName != null && crsName !in setOf("EPSG:4326", "urn:ogc:def:crs:EPSG::4326",
                "urn:ogc:def:crs:OGC:1.3:CRS84", "CRS:84")) return emptyList()

        val features = collection["features"] as? JsonArray ?: return emptyList()
        return features.mapNotNull { element ->
            val feature = element as? JsonObject ?: return@mapNotNull null
            if (feature.string("type") != "Feature") return@mapNotNull null
            val properties = feature["properties"] as? JsonObject ?: return@mapNotNull null
            val useCode = properties.string("ucode")
            if (useCode != "UMI100") return@mapNotNull null
            val geometry = feature["geometry"] as? JsonObject ?: return@mapNotNull null
            val coords = geometry["coordinates"] as? JsonArray ?: return@mapNotNull null
            val parts = when (geometry.string("type")) {
                "Polygon" -> listOf(coords)
                "MultiPolygon" -> coords
                else -> return@mapNotNull null
            }
            val polygons = parts.map { polygon(it) ?: return@mapNotNull null }
            if (polygons.isEmpty()) return@mapNotNull null
            RestrictedZone(properties.string("uname") ?: "상수원보호구역", "야영 금지 · 수도법 시행령 제12조", polygons)
        }
    }

    private fun polygon(element: JsonElement): List<List<LatLng>>? {
        val rings = element as? JsonArray ?: return null
        if (rings.isEmpty()) return null
        return rings.map { elementRing ->
            val raw = elementRing as? JsonArray ?: return null
            val ring = raw.map { position ->
                val pair = position as? JsonArray ?: return null
                val lng = (pair.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return null
                val lat = (pair.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
                if (lng !in -180.0..180.0 || lat !in -90.0..90.0) return null
                LatLng(lat, lng)
            }
            if (ring.size < 4 || ring.first() != ring.last()) return null
            ring
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content
}
