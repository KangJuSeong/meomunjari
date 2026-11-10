package com.example.meomunjari.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.meomunjari.core.model.LatLng
import com.example.meomunjari.core.model.UserData
import com.example.meomunjari.core.vehicle.VehicleSnapshot
import com.example.meomunjari.di.AppContainer
import com.example.meomunjari.domain.SortOrder
import com.example.meomunjari.domain.SpotHit
import com.example.meomunjari.domain.SpotQuery
import com.example.meomunjari.domain.SpotSearch
import com.example.meomunjari.domain.camping.CampingState
import com.example.meomunjari.ui.LocalAppContainer
import com.example.meomunjari.ui.components.ChipFlow
import com.example.meomunjari.ui.components.EmojiIcon
import com.example.meomunjari.ui.components.ListRow
import com.example.meomunjari.ui.components.ThinDivider
import com.example.meomunjari.ui.components.PrimaryButton
import com.example.meomunjari.ui.components.SecondaryButton
import com.example.meomunjari.ui.components.SectionCard
import com.example.meomunjari.ui.components.SpotRadar
import com.example.meomunjari.ui.components.SpotRow
import com.example.meomunjari.ui.components.StatTile
import com.example.meomunjari.ui.components.TagChip
import com.example.meomunjari.ui.theme.MColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import kotlin.math.roundToInt

data class HomeUi(
    val location: LatLng? = null,
    val nearby: List<SpotHit> = emptyList(),
    val vehicle: VehicleSnapshot = VehicleSnapshot(),
    val user: UserData? = null,
    val seasonalTags: List<String> = emptyList(),
    val camping: CampingState? = null,
)

class HomeViewModel(c: AppContainer) : ViewModel() {
    private val month = LocalDate.now().monthValue

    val ui: StateFlow<HomeUi> = combine(
        c.repos.spots.spots,
        c.trip.location,
        c.trip.vehicle,
        c.repos.user.user,
        c.trip.camping,
    ) { spots, loc, vehicle, user, camping ->
        HomeUi(
            location = loc,
            nearby = SpotSearch.search(spots, SpotQuery(fitMyCar = false, sort = SortOrder.DISTANCE, maxDistanceKm = NEARBY_KM), loc, user.car.vehicleClass),
            vehicle = vehicle,
            user = user,
            seasonalTags = c.tags.seasonal(month),
            camping = camping,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

    companion object {
        const val NEARBY_KM = 60.0
    }
}

@Composable
fun HomeScreen(
    onSpot: (String) -> Unit,
    onTag: (String) -> Unit,
    onRegister: () -> Unit,
    onCamping: () -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: HomeViewModel = viewModel { HomeViewModel(container) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val month = LocalDate.now().monthValue

    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("${ui.user?.nickname ?: ""}님,\n오늘 밤은 어디서 머물까요?", style = MaterialTheme.typography.headlineLarge)

            ui.camping?.let { camping ->
                SectionCard {
                    ListRow(
                        emoji = "🌙",
                        iconBackground = MColors.Purple50,
                        title = "차박 중 · ${camping.spot.name}",
                        subtitle = "실측 기록 ${camping.samples.size}개 · 눌러서 보기",
                        onClick = onCamping,
                    )
                }
            }

            SectionCard(
                title = "내 차",
                trailing = {
                    Text(
                        ui.user?.car?.let { "${it.model} · ${it.vehicleClass.label}" }.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MColors.Grey500,
                    )
                },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        label = ui.user?.car?.powerType?.energyLabel ?: "배터리",
                        value = ui.vehicle.energyPct?.let { "${it.roundToInt()}%" } ?: "-",
                        accent = MColors.Blue,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "실내",
                        value = ui.vehicle.cabinTempC?.let { "${it.roundToInt()}°C" } ?: "-",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(label = "기어", value = ui.vehicle.gear.label, modifier = Modifier.weight(1f))
                }
            }

            SectionCard(title = "${month}월에 어울리는 테마") {
                ChipFlow {
                    ui.seasonalTags.forEach { tag -> TagChip(tag, selected = false, onClick = { onTag(tag) }) }
                }
            }

            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EmojiIcon("📍", MColors.Orange50)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("처음 와 본 곳인가요?", style = MaterialTheme.typography.titleMedium)
                        Text("새 스팟으로 등록하면 '개척자' 뱃지를 드려요", style = MaterialTheme.typography.bodyMedium, color = MColors.Grey500)
                    }
                    SecondaryButton("등록하기", onClick = onRegister)
                }
            }
            Spacer(Modifier.height(96.dp))
        }

        LazyColumn(
            Modifier.weight(1.2f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item {
                SectionCard(title = "내 주변 차박 스팟") {
                    SpotRadar(ui.location, ui.nearby, HomeViewModel.NEARBY_KM)
                    if (ui.nearby.isEmpty()) {
                        Text("주변 ${HomeViewModel.NEARBY_KM.roundToInt()}km 안에 등록된 스팟이 없어요", style = MaterialTheme.typography.bodyLarge, color = MColors.Grey500)
                    }
                    ui.nearby.forEachIndexed { i, hit ->
                        if (i > 0) ThinDivider()
                        SpotRow(hit, onClick = { onSpot(hit.spot.id) })
                    }
                }
            }
            item {
                PrimaryButton(
                    "테마로 찾아보기",
                    onClick = { onTag(ui.seasonalTags.firstOrNull() ?: "#별보기") },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 100.dp),
                )
            }
        }
    }
}
