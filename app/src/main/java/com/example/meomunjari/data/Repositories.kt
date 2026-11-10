package com.example.meomunjari.data

import com.example.meomunjari.core.model.MeasurementReport
import com.example.meomunjari.core.model.RestrictedZone
import com.example.meomunjari.core.model.Review
import com.example.meomunjari.core.model.SettingPreset
import com.example.meomunjari.core.model.Spot
import com.example.meomunjari.core.model.TagDictionary
import com.example.meomunjari.core.model.UserData
import com.example.meomunjari.core.model.VehicleClass
import kotlinx.coroutines.flow.StateFlow

/*
 * 1단계는 Mock 구현(로컬 JSON + 메모리)을 쓴다.
 * 2단계 백엔드가 생기면 같은 인터페이스의 Remote 구현으로 교체한다. (설계서 '데이터 모델' 참고)
 */

interface SpotRepository {
    val spots: StateFlow<List<Spot>>
    fun get(id: String): Spot?
    fun register(spot: Spot)
    fun recordVisit(spotId: String, vehicleClass: VehicleClass, date: String)
    fun leave(spotId: String)
    fun addTags(spotId: String, tags: List<String>)
    fun addRating(spotId: String, rating: Int)
}

interface ReviewRepository {
    val reviews: StateFlow<List<Review>>
    fun forSpot(spotId: String): List<Review>
    fun add(review: Review)
}

interface ReportRepository {
    val reports: StateFlow<List<MeasurementReport>>
    fun forSpot(spotId: String): List<MeasurementReport>
    fun add(report: MeasurementReport)
}

interface PresetRepository {
    val presets: StateFlow<List<SettingPreset>>
    fun forSpot(spotId: String): List<SettingPreset>
    fun recordLoad(presetId: String)
    fun publish(preset: SettingPreset)
}

interface UserRepository {
    val user: StateFlow<UserData>
    fun update(transform: (UserData) -> UserData)
}

interface ReferenceRepository {
    val tags: TagDictionary
    val zones: List<RestrictedZone>
}

class Repositories(
    val spots: SpotRepository,
    val reviews: ReviewRepository,
    val reports: ReportRepository,
    val presets: PresetRepository,
    val user: UserRepository,
    val reference: ReferenceRepository,
)
