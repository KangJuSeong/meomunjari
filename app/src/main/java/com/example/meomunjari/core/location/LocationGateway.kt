package com.example.meomunjari.core.location

import com.example.meomunjari.core.model.LatLng
import kotlinx.coroutines.flow.StateFlow

interface LocationGateway {
    /** 차량 현재 위치. 아직 모르면 null. */
    val location: StateFlow<LatLng?>

    fun start()
    fun stop()
}
