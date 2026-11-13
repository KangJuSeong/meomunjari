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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.meomunjari.core.Geo
import com.example.meomunjari.core.model.Review
import com.example.meomunjari.core.model.SettingPreset
import com.example.meomunjari.core.model.Spot
import com.example.meomunjari.core.model.UserData
import com.example.meomunjari.di.AppContainer
import com.example.meomunjari.domain.BadgeEngine
import com.example.meomunjari.domain.EnergyForecast
import com.example.meomunjari.core.model.PowerType
import com.example.meomunjari.data.mock.mockChargingStations
import com.example.meomunjari.domain.CampingEnergyPlan
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class SpotDetailUi(
    val spot: Spot? = null,
    val reviews: List<Review> = emptyList(),
    val month: Int = LocalDate.now().monthValue,
    val months: List<Int> = emptyList(),
    val summary: ReportSummary? = null,
    val forecast: EnergyForecast? = null,
    val evPlan: CampingEnergyPlan? = null,
    val evRecords: Int = 0,
    val master: SettingPreset? = null,
    val distanceM: Double? = null,
    val user: UserData? = null,
    val grade: Grade = Grade.NEWBIE,
    val isDestination: Boolean = false,
)

class SpotDetailViewModel(c: AppContainer, private val spotId: String) : ViewModel() {
    private val month = MutableStateFlow(LocalDate.now().monthValue)

    private val base = combine(c.repos.spots.spots, c.repos.reviews.reviews, c.repos.reports.reports, c.repos.presets.presets, c.repos.user.user) { spots, reviews, reports, presets, user ->
        Base(spots.firstOrNull { it.id == spotId }, spots, reviews, reports, presets, user)
    }

    val ui: StateFlow<SpotDetailUi> = combine(base, c.trip.vehicle, c.trip.location, month, c.trip.destination) { b, vehicle, loc, m, dest ->
        val spot = b.spot ?: return@combine SpotDetailUi()
        val summary = ReportAggregator.summarize(b.reports, spot.id, m, b.user.car.powerType)
        val distance = loc?.let { Geo.distanceM(it, spot.location) }
        val isEv = b.user.car.powerType == PowerType.EV
        val records = CampingEnergyPlanner.matchingReports(b.reports, spot, LocalDate.now().monthValue)
        val night = CampingEnergyPlanner.estimateNight(spot.overnightTemperaturesC, records)
        val evPlan = if (isEv && loc != null && vehicle.batteryPct != null) {
            CampingEnergyPlanner.plan(vehicle.batteryPct.toDouble(), loc, spot, b.user.car,
                vehicle.efficiencyKmPerKWh?.toDouble(), night, mockChargingStations)
        } else null
        val forecast = vehicle.energyPct?.takeIf { !isEv && distance != null }?.let { pct ->
            EnergyPredictor.forecast(
                nowPct = pct.toDouble(),
                routeKm = (distance ?: 0.0) / 1000.0 * Geo.ROAD_FACTOR,
                car = b.user.car,
                efficiencyKmPerKWh = vehicle.efficiencyKmPerKWh?.toDouble(),
                summary = summary,
                month = m,
                chargerKm = spot.nearestChargerKm,
            )
        }
        SpotDetailUi(
            spot = spot,
            reviews = b.reviews.filter { it.spotId == spot.id }.sortedByDescending { it.createdAt },
            month = m,
            months = (ReportAggregator.monthsWithData(b.reports, spot.id) + LocalDate.now().monthValue).distinct().sorted(),
            summary = summary,
            forecast = forecast,
            evPlan = evPlan,
            evRecords = records.size,
            master = SettingCompatibility.master(b.presets.filter { it.spotId == spot.id }, b.user.nickname),
            distanceM = distance,
            user = b.user,
            grade = BadgeEngine.evaluate(b.user, b.spots).grade,
            isDestination = dest?.id == spot.id,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpotDetailUi())

    fun selectMonth(m: Int) {
        month.value = m
    }

    private data class Base(
        val spot: Spot?,
        val spots: List<Spot>,
        val reviews: List<Review>,
        val reports: List<com.example.meomunjari.core.model.MeasurementReport>,
        val presets: List<SettingPreset>,
        val user: UserData,
    )
}

@Composable
fun SpotDetailScreen(spotId: String, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: SpotDetailViewModel = viewModel(key = "spot-$spotId") { SpotDetailViewModel(container, spotId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }

    var sending by remember(spotId) { mutableStateOf(false) }
    val activeStop by container.trip.chargingStop.collectAsStateWithLifecycle()
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
                if (exactLocation) spot.region else "${spot.region} 일대",
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
                    SectionCard(
                        title = "차가 직접 기록한 ${ui.month}월 밤",
                        trailing = {
                            if (s != null && !s.isEmpty) {
                                Pill(if (s.reliable) "기록 ${s.sampleCount}회" else "참고치 · ${s.sampleCount}회", if (s.reliable) MColors.Blue else MColors.Orange)
                            }
                        },
                    ) {
                        ChipFlow {
                            ui.months.forEach { m -> TagChip("${m}월", selected = m == ui.month, onClick = { vm.selectMonth(m) }) }
                        }
                        if (s == null || s.isEmpty) {
                            Text("${ui.month}월 기록이 아직 없어요. 첫 기록을 남기면 기록왕 2배!", style = MaterialTheme.typography.bodyLarge, color = MColors.Grey500)
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                StatTile("새벽 실내 최저", s.minCabinTempC?.let { "${it.roundToInt()}°C" } ?: "-", Modifier.weight(1f))
                                StatTile(
                                    "공조 ${s.avgHvacHours?.roundToInt() ?: "-"}시간",
                                    s.avgEnergyUsedPct?.let { "−${it.roundToInt()}%" } ?: "-",
                                    Modifier.weight(1f),
                                    accent = MColors.Blue,
                                )
                                StatTile("비 온 날", "${s.rainyCount}회", Modifier.weight(1f))
                            }
                        }
                        Text("사람이 입력한 값이 아니에요. 다녀간 차가 밤새 자동으로 남긴 기록이에요.", style = MaterialTheme.typography.bodySmall, color = MColors.Grey500)
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
                        ChipFlow { spot.sortedTags.forEach { TagChip(it.tag, selected = false, onClick = {}, count = it.count) } }
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
                        val plan = ui.evPlan
                        if (plan == null) Text("현재 위치와 차량 배터리를 확인하고 있어요")
                        else {
                            Text("직행 시 도착 잔량 ${plan.arrivalPct.roundToInt()}%")
                            if (plan.night != null && plan.morningPct != null) {
                                Text("아침 예상 잔량 ${plan.morningPct.roundToInt()}%", style = MaterialTheme.typography.titleLarge)
                                Text("도착 후 8시간 · 목업 기온 기준 난방 ${plan.night.heatingHours.roundToInt()}시간 · 총 소모 ${plan.night.drainPct.roundToInt()}%p")
                                Text("이번 달 EV 기록 ${ui.evRecords}건" + if (ui.evRecords == 2) " · 참고치" else "")
                            } else if (ui.evRecords < 2) Text("측정중 · 이번 달 EV 기록 ${ui.evRecords}/2건")
                            else Text("야간 기온 자료를 기다리고 있어요")
                            if (plan.requiresCharge) {
                                Text("충전 권고 · ${plan.stop?.let { "${it.station.name}에서 약 ${it.minutes}분 충전" } ?: "도달 가능한 경로상 목업 충전소가 없어요"}")
                            } else if (plan.morningPct != null) Text("아침에 인근 충전소까지 이동할 여유가 있어요")
                        }
                    } else ui.forecast?.let { EnergyFlow(it) } ?: Text("위치와 차량 잔량을 확인하고 있어요")
                }
                val stop = ui.evPlan?.stop
                if (stop != null) {
                    val added = ui.isDestination && activeStop?.station == stop.station
                    PrimaryButton(
                        text = if (added) "충전소 경유 안내 중" else "충전소를 경유해 차박지로",
                        enabled = !sending && !added,
                        onClick = {
                            sending = true
                            scope.launch {
                                try {
                                    message = if (container.trip.navigateTo(spot, stop)) "충전소 경유 경로가 설정됐어요"
                                        else "경유지를 설정하지 못했어요. 내비 연결을 확인하고 다시 시도해 주세요."
                                } finally { sending = false }
                            }
                        }, modifier = Modifier.fillMaxWidth(),
                    )
                }
                PrimaryButton(
                    text = if (ui.isDestination) "안내 중이에요" else "여기로 바로 갈래요",
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
                    "금지 장소 신고",
                    grey = true,
                    onClick = { message = "신고가 접수됐어요. 확인 후 노출을 멈출게요." },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(96.dp))
            }
        }
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
            if (review.reportId != null) Text("실측 리포트 첨부", style = MaterialTheme.typography.bodySmall, color = MColors.Purple)
        }
    }
}
