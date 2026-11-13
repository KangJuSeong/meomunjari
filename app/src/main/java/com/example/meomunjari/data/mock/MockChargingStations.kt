package com.example.meomunjari.data.mock

import com.example.meomunjari.core.model.LatLng
import com.example.meomunjari.core.navi.NaviDestination

/** 시연 좌표. 실제 충전소 위치나 운영 상태를 보증하지 않는다. */
val mockChargingStations = listOf(
    NaviDestination("demo-sokcho", "속초 시내 시연 충전소", LatLng(38.204, 128.582)),
    NaviDestination("demo-inje", "인제 방면 시연 충전소", LatLng(38.06, 128.46)),
    NaviDestination("demo-coast", "동해안 시연 충전소", LatLng(38.06, 128.68)),
    NaviDestination("demo-goseong", "고성 방면 시연 충전소", LatLng(38.29, 128.54)),
)
