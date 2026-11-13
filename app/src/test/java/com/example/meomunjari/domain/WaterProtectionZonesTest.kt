package com.example.meomunjari.domain

import com.example.meomunjari.Fixtures
import com.example.meomunjari.core.model.LatLng
import com.example.meomunjari.data.mock.WaterProtectionZones
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaterProtectionZonesTest {
    private val shell = "[[127,37],[128,37],[128,38],[127,38],[127,37]]"
    private val hole = "[[127.2,37.2],[127.4,37.2],[127.4,37.4],[127.2,37.4],[127.2,37.2]]"
    private val island = "[[129,37],[130,37],[130,38],[129,38],[129,37]]"

    private fun page(kind: String, coordinates: String, crs: String = "EPSG:4326") = """
        {"response":{"status":"OK","result":{"featureCollection":{"type":"FeatureCollection","crs":{"type":"name","properties":{"name":"$crs"}},
         "features":[{"type":"Feature","geometry":{"type":"$kind","coordinates":$coordinates},"properties":{"ucode":"UMI100"}}]}}}}
    """.trimIndent()

    @Test
    fun multiPolygonHonorsRingBoundariesAndHoles() {
        val zone = WaterProtectionZones.read(page("MultiPolygon", "[[$shell,$hole],[$island]]")).single()
        assertTrue(zone.contains(LatLng(37.1, 127.1)))
        assertFalse(zone.contains(LatLng(37.3, 127.3)))
        assertTrue(zone.contains(LatLng(37.5, 129.5)))
        assertTrue(zone.contains(LatLng(37.5, 128.0)))
        assertFalse(zone.contains(LatLng(37.5, 128.5)))
    }

    @Test
    fun samplePreservesTheTwoExcludedAreasInTheOfficialBoundary() {
        val zone = Fixtures.seed().zones.single()
        assertTrue(zone.contains(LatLng(37.48634563, 127.31812732)))
        assertFalse(zone.contains(LatLng(37.46955990, 127.31228707)))
        assertFalse(zone.contains(LatLng(37.49528264, 127.30125165)))
    }

    @Test
    fun unsuccessfulOrGeometrylessResponseCannotBlockRegistration() {
        assertTrue(WaterProtectionZones.read("""{"response":{"status":"NOT_FOUND"}}""").isEmpty())
        assertTrue(WaterProtectionZones.read(page("Polygon", "[$shell]").replace("OK", "ERROR")).isEmpty())
        assertTrue(WaterProtectionZones.read(page("Polygon", "null")).isEmpty())
    }

    @Test
    fun unsupportedGeometryOrProjectionCannotBlockRegistration() {
        assertTrue(WaterProtectionZones.read(page("Polygon", "[$shell]").replace("UMI100", "UMI999")).isEmpty())
        assertTrue(WaterProtectionZones.read(page("Point", "[127,37]")).isEmpty())
        assertTrue(WaterProtectionZones.read(page("Polygon", "[$shell]", "EPSG:3857")).isEmpty())
        assertTrue(WaterProtectionZones.read(page("Polygon", "[[[127,37],[128,37],[128,38]]]")).isEmpty())
    }
}
