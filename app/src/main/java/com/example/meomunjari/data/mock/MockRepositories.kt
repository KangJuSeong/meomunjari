package com.example.meomunjari.data.mock

import com.example.meomunjari.core.model.MeasurementReport
import com.example.meomunjari.core.model.RestrictedPlaceReport
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
    val publishedLoads = presets.filter { it.ownerNickname == user.nickname }.sumOf { it.loadCount }
    val member = MockUserRepository(user.copy(presetLoadsReceived = maxOf(user.presetLoadsReceived, publishedLoads)))
    return Repositories(
        spots = MockSpotRepository(spots),
        reviews = MockReviewRepository(reviews),
        reports = MockReportRepository(reports),
        presets = MockPresetRepository(presets, member),
        user = member,
        reference = ref,
    )
}

class MockSpotRepository(seed: List<Spot>) : SpotRepository {
    private val state = MutableStateFlow(seed)
    override val spots: StateFlow<List<Spot>> = state.asStateFlow()
    private val reports = MutableStateFlow(emptyList<RestrictedPlaceReport>())
    override val restrictedPlaceReports = reports.asStateFlow()

    override fun get(id: String): Spot? = state.value.firstOrNull { it.id == id }

    @Synchronized
    override fun reportRestrictedPlace(report: RestrictedPlaceReport): RestrictedPlaceReport? {
        if (get(report.spotId) == null) return null
        val existing = reports.value.firstOrNull { it.spotId == report.spotId && it.reporter == report.reporter }
        if (existing != null) return existing
        reports.value = reports.value + report
        return report
    }

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

class MockReportRepository(private val otherCampers: List<MeasurementReport>) : ReportRepository {
    private val myReportsBySpot = mutableMapOf<String, MeasurementReport>()
    private val state = MutableStateFlow(otherCampers)
    override val reports: StateFlow<List<MeasurementReport>> = state.asStateFlow()
    override fun forSpot(spotId: String): List<MeasurementReport> = state.value.filter { it.spotId == spotId }
    @Synchronized
    override fun saveMine(report: MeasurementReport) {
        myReportsBySpot[report.spotId] = report
        state.value = otherCampers + myReportsBySpot.values
    }
}

class MockPresetRepository(seed: List<SettingPreset>, private val member: UserRepository) : PresetRepository {
    private val state = MutableStateFlow(seed)
    private val loads = mutableSetOf<Triple<String, String, String>>()
    override val presets: StateFlow<List<SettingPreset>> = state.asStateFlow()
    override fun forSpot(spotId: String): List<SettingPreset> = state.value.filter { it.spotId == spotId }

    @Synchronized
    override fun recordLoad(presetId: String, visitor: String, visitId: String) {
        val preset = state.value.firstOrNull { it.id == presetId } ?: return
        if (preset.ownerNickname == visitor || !loads.add(Triple(presetId, visitor, visitId))) return
        state.value = state.value.map { if (it.id == presetId) it.copy(loadCount = it.loadCount + 1) else it }
        if (preset.ownerNickname == member.user.value.nickname) {
            member.update { it.copy(presetLoadsReceived = it.presetLoadsReceived + 1) }
        }
    }

    @Synchronized
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
