package com.example.meomunjari.data.mock

import com.example.meomunjari.core.model.MeasurementReport
import com.example.meomunjari.core.model.RestrictedZone
import com.example.meomunjari.core.model.Review
import com.example.meomunjari.core.model.SettingPreset
import com.example.meomunjari.core.model.Spot
import com.example.meomunjari.core.model.TagCount
import com.example.meomunjari.core.model.TagDictionary
import com.example.meomunjari.core.model.UserData
import com.example.meomunjari.core.model.VehicleClass
import com.example.meomunjari.data.PresetRepository
import com.example.meomunjari.data.ReferenceRepository
import com.example.meomunjari.data.ReportRepository
import com.example.meomunjari.data.Repositories
import com.example.meomunjari.data.ReviewRepository
import com.example.meomunjari.data.SpotRepository
import com.example.meomunjari.data.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.max

/** JSON에서 읽어 온 목 데이터 전체. 앱 실행 중 변경은 메모리에만 남는다. */
data class MockSeed(
    val spots: List<Spot>,
    val reviews: List<Review>,
    val reports: List<MeasurementReport>,
    val presets: List<SettingPreset>,
    val user: UserData,
    val tags: TagDictionary,
    val zones: List<RestrictedZone>,
)

fun MockSeed.toRepositories(): Repositories {
    val ref = MockReferenceRepository(tags, zones)
    return Repositories(
        spots = MockSpotRepository(spots),
        reviews = MockReviewRepository(reviews),
        reports = MockReportRepository(reports),
        presets = MockPresetRepository(presets),
        user = MockUserRepository(user),
        reference = ref,
    )
}

class MockSpotRepository(seed: List<Spot>) : SpotRepository {
    private val state = MutableStateFlow(seed)
    override val spots: StateFlow<List<Spot>> = state.asStateFlow()

    override fun get(id: String): Spot? = state.value.firstOrNull { it.id == id }

    override fun register(spot: Spot) {
        state.update { list -> list.filterNot { it.id == spot.id } + spot }
    }

    override fun recordVisit(spotId: String, vehicleClass: VehicleClass, date: String) = mutate(spotId) {
        val classes = it.visitedVehicleClasses.toMutableMap()
        classes[vehicleClass] = (classes[vehicleClass] ?: 0) + 1
        it.copy(
            visitCount = it.visitCount + 1,
            lastVisitedAt = date,
            liveCampers = it.liveCampers + 1,
            visitedVehicleClasses = classes,
        )
    }

    override fun leave(spotId: String) = mutate(spotId) { it.copy(liveCampers = max(0, it.liveCampers - 1)) }

    override fun addTags(spotId: String, tags: List<String>) = mutate(spotId) { spot ->
        val counts = spot.tags.associate { it.tag to it.count }.toMutableMap()
        tags.distinct().forEach { tag -> counts[tag] = (counts[tag] ?: 0) + 1 }
        spot.copy(tags = counts.map { (tag, count) -> TagCount(tag, count) }.sortedByDescending { it.count })
    }

    override fun addRating(spotId: String, rating: Int) = mutate(spotId) { spot ->
        val total = spot.rating * spot.ratingCount + rating
        val count = spot.ratingCount + 1
        spot.copy(rating = total / count, ratingCount = count)
    }

    private fun mutate(spotId: String, transform: (Spot) -> Spot) {
        state.update { list -> list.map { if (it.id == spotId) transform(it) else it } }
    }
}

class MockReviewRepository(seed: List<Review>) : ReviewRepository {
    private val state = MutableStateFlow(seed)
    override val reviews: StateFlow<List<Review>> = state.asStateFlow()
    override fun forSpot(spotId: String): List<Review> =
        state.value.filter { it.spotId == spotId }.sortedByDescending { it.createdAt }

    override fun add(review: Review) = state.update { it + review }
}

class MockReportRepository(seed: List<MeasurementReport>) : ReportRepository {
    private val state = MutableStateFlow(seed)
    override val reports: StateFlow<List<MeasurementReport>> = state.asStateFlow()
    override fun forSpot(spotId: String): List<MeasurementReport> = state.value.filter { it.spotId == spotId }
    /** 한 스팟에 사용자당 리포트 1개: 같은 작성자의 이전 리포트를 새 것으로 바꾼다. */
    override fun add(report: MeasurementReport) = state.update { list ->
        list.filterNot { it.spotId == report.spotId && report.author.isNotBlank() && it.author == report.author } + report
    }
}

class MockPresetRepository(seed: List<SettingPreset>) : PresetRepository {
    private val state = MutableStateFlow(seed)
    override val presets: StateFlow<List<SettingPreset>> = state.asStateFlow()
    override fun forSpot(spotId: String): List<SettingPreset> = state.value.filter { it.spotId == spotId }

    override fun recordLoad(presetId: String) = state.update { list ->
        list.map { if (it.id == presetId) it.copy(loadCount = it.loadCount + 1) else it }
    }

    override fun publish(preset: SettingPreset) = state.update { list -> list.filterNot { it.id == preset.id } + preset }
}

class MockUserRepository(seed: UserData) : UserRepository {
    private val state = MutableStateFlow(seed)
    override val user: StateFlow<UserData> = state.asStateFlow()
    override fun update(transform: (UserData) -> UserData) = state.update(transform)
}

class MockReferenceRepository(
    override val tags: TagDictionary,
    override val zones: List<RestrictedZone>,
) : ReferenceRepository
