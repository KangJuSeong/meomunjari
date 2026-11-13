package com.example.meomunjari.domain

import com.example.meomunjari.core.vehicle.VehicleRange
import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleRangeTest {
    @Test
    fun windowPercentMapsToSmallVehicleRangeAndOpensAtLeastOneStep() {
        val window = 0..10
        assertEquals(0, VehicleRange.fromPercent(0, window, roundUp = true))
        assertEquals("5%도 실제로 조금 열려야 한다", 1, VehicleRange.fromPercent(5, window, roundUp = true))
        assertEquals(3, VehicleRange.fromPercent(30, window, roundUp = true))
        assertEquals(10, VehicleRange.fromPercent(100, window, roundUp = true))
        assertEquals(30, VehicleRange.toPercent(3, window))
    }

    @Test
    fun backrestPercentMapsToVehicleBounds() {
        assertEquals(50, VehicleRange.fromPercent(50, 0..100))
        assertEquals(30, VehicleRange.fromPercent(50, 10..50))
        assertEquals("범위를 벗어난 값은 끝으로 자른다", 100, VehicleRange.fromPercent(150, 0..100))
        assertEquals(0, VehicleRange.toPercent(0, 5..5))
    }
}
