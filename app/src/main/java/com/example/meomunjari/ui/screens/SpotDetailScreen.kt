package com.example.meomunjari.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.meomunjari.core.Geo
import com.example.meomunjari.core.model.MeasurementReport
import com.example.meomunjari.core.model.Review
import com.example.meomunjari.core.model.SettingPreset
import com.example.meomunjari.core.model.Spot
import com.example.meomunjari.core.model.UserData
import com.example.meomunjari.di.AppContainer
import com.example.meomunjari.domain.BadgeEngine
import com.example.meomunjari.domain.EnergyForecast
import com.example.meomunjari.core.model.PowerType
import com.example.meomunjari.domain.RouteEstimate
import com.example.meomunjari.domain.CampingEnergyPlanner
import com.example.meomunjari.domain.EnergyPredictor
import com.example.meomunjari.domain.Grade
import com.example.meomunjari.domain.ReportAggregator
import com.example.meomunjari.domain.ReportSummary
import com.example.meomunjari.domain.SettingCompatibility
import com.example.meomunjari.domain.SpotSearch
import com.example.meomunjari.ui.LocalAppContainer
import com.example.meomunjari.ui.components.ChipFlow
import com.example.meomunjari.ui.components.ListRow
import com.example.meomunjari.ui.components.StatTile
import com.example.meomunjari.ui.components.ThinDivider
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.roundToInt
import com.example.meomunjari.ui.components.EmptyState
import com.example.meomunjari.ui.components.EnergyFlow
import com.example.meomunjari.ui.components.Pill
import com.example.meomunjari.ui.components.PrimaryButton
import com.example.meomunjari.ui.components.ScreenHeader
import com.example.meomunjari.ui.components.SecondaryButton
import com.example.meomunjari.ui.components.SectionCard
import com.example.meomunjari.ui.components.SpotPhoto
import com.example.meomunjari.ui.components.TagChip
import com.example.meomunjari.ui.theme.MColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.example.meomunjari.ui.components.WakeTimeButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import java.time.LocalDateTime
import java.time.LocalDate

data class SpotDetailUi(
    val spot: Spot? = null,
    val reviews: List<Review> = emptyList(),
    val summary: ReportSummary? = null,
    val forecast: EnergyForecast? = null,
    val routeEstimate: RouteEstimate? = null,
    val master: SettingPreset? = null,
    val distanceM: Double? = null,
    val user: UserData? = null,
    val grade: Grade = Grade.NEWBIE,
    val isDestination: Boolean = false,
)

class SpotDetailViewModel(c: AppContainer, private val spotId: String) : ViewModel() {
    private val base = combine(c.repos.spots.spots, c.repos.reviews.reviews, c.repos.reports.reports, c.repos.presets.presets, c.repos.user.user) { spots, reviews, reports, presets, user ->
        Base(spots.firstOrNull { it.id == spotId }, spots, reviews, reports, presets, user)
    }

    private val localMinute = flow {
        while (true) {
            val now = LocalDateTime.now()
            emit(now)
            delay((60 - now.second) * 1000L)
        }
    }
    private val schedule = combine(c.trip.wakeTime, localMinute) { wake, now -> wake to now }

    val ui: StateFlow<SpotDetailUi> = combine(base, c.trip.vehicle, c.trip.location, schedule, c.trip.destination) { b, vehicle, loc, (wake, now), dest ->
        val spot = b.spot ?: return@combine SpotDetailUi()
        val today = LocalDate.now()
        val m = today.monthValue
        val summary = ReportAggregator.summarizeNear(b.reports, spot.id, today, b.user.car.powerType)
        val distance = loc?.let { Geo.distanceM(it, spot.location) }
        val isEv = b.user.car.powerType == PowerType.EV
        val energyPct = vehicle.energyPct(b.user.car.powerType)
        val routeEstimate = if (isEv && loc != null && energyPct != null) {
            CampingEnergyPlanner.estimateDeparture(energyPct.toDouble(), loc, spot, b.user.car,
                vehicle.efficiencyKmPerKWh?.toDouble(), now, wake, b.user.mySetting.targetTempC,
                b.user.mySetting.hvacOn)
        } else null
        val forecast = energyPct?.takeIf { !isEv && distance != null }?.let { pct ->
            EnergyPredictor.fuelForecast(
                nowPct = pct.toDouble(),
                routeKm = (distance ?: 0.0) / 1000.0 * Geo.ROAD_FACTOR,
                car = b.user.car,
                summary = summary.takeIf { it.canShowSharedEnergy },
                month = m,
            )
        }
        SpotDetailUi(
            spot = spot,
            reviews = b.reviews.filter { it.spotId == spot.id }.sortedByDescending { it.createdAt },
            summary = summary,
            forecast = forecast,
            routeEstimate = routeEstimate,
            master = SettingCompatibility.master(b.presets.filter { it.spotId == spot.id }, b.user.nickname),
            distanceM = distance,
            user = b.user,
            grade = BadgeEngine.evaluate(b.user, b.spots).grade,
            isDestination = dest?.id == spot.id,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpotDetailUi())

    private data class Base(
        val spot: Spot?,
        val spots: List<Spot>,
        val reviews: List<Review>,
        val reports: List<MeasurementReport>,
        val presets: List<SettingPreset>,
        val user: UserData,
    )
}

@Composable
fun SpotDetailScreen(spotId: String, onBack: () -> Unit, onTag: (String) -> Unit) {
    val container = LocalAppContainer.current
    val vm: SpotDetailViewModel = viewModel(key = "spot-$spotId") { SpotDetailViewModel(container, spotId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val wake by container.trip.wakeTime.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var reporting by rememberSaveable(spotId) { mutableStateOf(false) }
    var reportNote by rememberSaveable(spotId) { mutableStateOf("") }
    val placeReports by container.repos.spots.restrictedPlaceReports.collectAsStateWithLifecycle()
    val reported = placeReports.any { it.spotId == spotId && it.reporter == ui.user?.nickname }

    var sending by remember(spotId) { mutableStateOf(false) }
    val spot = ui.spot ?: run {
        EmptyState("스팟을 찾을 수 없어요", "목록에서 다시 골라 주세요.")
        return
    }
    val exactLocation = ui.grade.minPoints >= Grade.CAMPER.minPoints || spot.registeredBy == ui.user?.nickname
    val fits = ui.user?.let { spot.fits(it.car.vehicleClass) } ?: true

    Column {
        ScreenHeader(
            title = spot.name,
            subtitle = listOfNotNull(
                spot.region.takeIf { it.isNotBlank() }?.let { if (exactLocation) it else "$it 일대" },
                ui.distanceM?.let { Geo.formatDistance(it) },
                "★ ${Math.round(spot.rating * 10) / 10.0} (${spot.ratingCount})",
                "${spot.registeredBy}님이 등록",
            ).joinToString(" · "),
            onBack = onBack,
            trailing = { Pill(if (fits) "내 차 진입 가능" else "내 차엔 좁아요", if (fits) MColors.Green else MColors.Red) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    val s = ui.summary
                    val today = LocalDate.now()
                    SectionCard(
                        title = "차가 직접 기록한 같은 시기의 밤",
                        trailing = {
                            Pill(
                                "${today.monthValue}월 ${today.dayOfMonth}일 앞뒤 2주 · ${s?.sampleCount ?: 0}건",
                                if (s != null && s.reliable) MColors.Blue else MColors.Orange,
                            )
                        },
                    ) {
                        if (s == null || s.isEmpty) {
                            Text("앞뒤 2주 안에 남겨진 리포트가 아직 없어요. 첫 기록을 남기면 기록왕 2배!", style = MaterialTheme.typography.bodyLarge, color = MColors.Grey500)
                        } else if (!s.canShowSharedMeasurements) {
                            Text("기록이 2건 모이면 이 시기의 통계를 보여줘요.", style = MaterialTheme.typography.bodyLarge)
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                StatTile("외부 최저", s.sharedMinOutsideC?.let { "${it.roundToInt()}°C" } ?: "-", Modifier.weight(1f))
                                StatTile(
                                    "${s.powerType?.energyLabel ?: "배터리"} 평균 소모",
                                    s.avgEnergyUsedPct?.takeIf { s.canShowSharedEnergy }?.let { "−${it.roundToInt()}%" } ?: "-",
                                    Modifier.weight(1f),
                                    accent = MColors.Blue,
                                )
                                StatTile("평균 차박", s.avgDurationHours?.let { "${it.roundToInt()}시간" } ?: "-", Modifier.weight(1f))
                            }
                            s.minCabinTempC?.let { Text("실내 최저 ${it.roundToInt()}°C · 비 온 밤 ${s.rainyCount}회") }
                        }
                        Text("기록 공유에 동의한 차량의 측정값을 모아 보여줘요.", style = MaterialTheme.typography.bodySmall, color = MColors.Grey500)
                    }
                }
                item {
                    SectionCard {
                        ListRow(
                            "🚐",
                            if (spot.liveCampers > 0) "지금 ${spot.liveCampers}대가 머물고 있어요" else "지금은 비어 있어요",
                            spot.typicalWeekendCampers,
                        )
                        ThinDivider()
                        ListRow(
                            "🚻",
                            listOf(
                                if (spot.amenities.toilet) "화장실 있음" else "화장실 없음",
                                spot.amenities.convenienceStoreM?.let { "편의점 ${Geo.formatDistance(it.toDouble())}" } ?: "편의점 멀어요",
                            ).joinToString(" · "),
                            listOf(spot.amenities.ground, spot.amenities.noise).filter { it.isNotBlank() }.joinToString(" · "),
                        )
                        ThinDivider()
                        ListRow("🚙", "최대 ${spot.maxVehicleClass.label}까지", ui.user?.let { SpotSearch.vehicleMix(spot, it.car.vehicleClass) })
                    }
                }
                ui.master?.let { master ->
                    item {
                        SectionCard(title = "고수의 세팅") {
                            ListRow(
                                "🏆",
                                "${master.ownerGrade} ${master.ownerNickname}님",
                                SettingCompatibility.summary(master),
                                iconBackground = MColors.Orange50,
                                trailing = { Text("${master.loadCount}명 사용", style = MaterialTheme.typography.bodySmall, color = MColors.Grey500) },
                            )
                        }
                    }
                }
                item {
                    SectionCard(title = "다녀온 사람들의 이야기 ${ui.reviews.size}") {
                        Text(spot.description, style = MaterialTheme.typography.bodyLarge)
                        ChipFlow {
                            spot.sortedTags.forEach { tag ->
                                TagChip(tag.tag, selected = false, onClick = { onTag(tag.tag) }, count = tag.count)
                            }
                        }
                    }
                }
                items(ui.reviews, key = { it.id }) { ReviewCard(it) }
                item { Spacer(Modifier.height(96.dp)) }
            }

            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SpotPhoto(spot.photoColors, height = 180.dp)
                SectionCard(title = "내 차로 하룻밤 괜찮을까요?") {
                    if (ui.user?.car?.powerType == PowerType.EV) {
                        WakeTimeButton(wake, container.trip::selectWakeTime)
                        val estimate = ui.routeEstimate
                        if (estimate == null) Text("예측에 필요한 위치와 차량 정보를 확인하고 있어요")
                        else ui.user?.let { DepartureEstimate(estimate, wake, it.mySetting) }
                    } else ui.forecast?.let { EnergyFlow(it) } ?: Text("위치와 차량 잔량을 확인하고 있어요")
                }
                PrimaryButton(
                    text = if (ui.isDestination) "안내 중이에요" else "차박지로 길안내",
                    enabled = !ui.isDestination && !sending,
                    onClick = {
                        sending = true
                        scope.launch {
                            try {
                                message = if (container.trip.navigateTo(spot)) "'${spot.name}'(으)로 길안내를 시작했어요"
                                    else "내비 목적지를 설정하지 못했어요"
                            } finally { sending = false }
                        }
                    }, modifier = Modifier.fillMaxWidth(),
                )
                SecondaryButton(
                    if (reported) "금지 장소 신고 기록됨" else "금지 장소 신고",
                    grey = true,
                    enabled = !reported,
                    onClick = { reporting = true },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(96.dp))
            }
        }
    }

    if (reporting) {
        AlertDialog(
            onDismissRequest = { reporting = false },
            title = { Text("금지 장소 신고") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("‘${spot.name}’에서 야영이 금지된 이유를 남길 수 있어요. 신고는 이번 목업 실행 중에 기록돼요.")
                    OutlinedTextField(value = reportNote, onValueChange = { reportNote = it },
                        label = { Text("현장 안내문 등 참고 내용 (선택)") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val saved = container.trip.reportRestrictedPlace(spotId, reportNote)
                    reporting = false
                    if (saved != null) reportNote = ""
                    message = if (saved != null) "금지 장소 신고를 기록했어요." else "스팟을 찾을 수 없어요."
                }) { Text("신고하기") }
            },
            dismissButton = { TextButton(onClick = { reporting = false }) { Text("취소") } },
        )
    }
    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            containerColor = MColors.Card,
            text = { Text(it, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("확인", fontWeight = FontWeight.Bold) } },
        )
    }
}

@Composable
fun ReviewCard(review: Review) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(review.author, style = MaterialTheme.typography.titleMedium)
            if (review.authorGrade.isNotBlank()) Pill(review.authorGrade, MColors.Orange)
            Pill("방문 인증", MColors.Green)
            Spacer(Modifier.weight(1f))
            Text("★ ${review.rating}", style = MaterialTheme.typography.labelLarge, color = MColors.Star)
        }
        Text(review.text, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(review.createdAt, style = MaterialTheme.typography.bodySmall, color = MColors.Grey500)
            if (review.tags.isNotEmpty()) Text(review.tags.joinToString(" "), style = MaterialTheme.typography.bodySmall, color = MColors.Blue)
        }
    }
}
