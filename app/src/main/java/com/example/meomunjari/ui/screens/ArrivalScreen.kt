package com.example.meomunjari.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.meomunjari.core.Geo
import com.example.meomunjari.core.model.SettingPreset
import com.example.meomunjari.core.vehicle.ControlItem
import com.example.meomunjari.domain.DriveGuard
import com.example.meomunjari.domain.PresetOption
import com.example.meomunjari.domain.PresetSource
import com.example.meomunjari.domain.SettingCompatibility
import com.example.meomunjari.domain.camping.ExternalDevices
import com.example.meomunjari.trip.StartCampingResult
import com.example.meomunjari.core.model.PowerType
import com.example.meomunjari.ui.components.WakeTimeButton
import com.example.meomunjari.ui.LocalAppContainer
import com.example.meomunjari.ui.components.Pill
import com.example.meomunjari.ui.components.ChipFlow
import com.example.meomunjari.ui.components.KeyValue
import com.example.meomunjari.ui.components.ListRow
import com.example.meomunjari.ui.components.TagChip
import com.example.meomunjari.ui.components.ThinDivider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.meomunjari.ui.components.PrimaryButton
import com.example.meomunjari.ui.components.ScreenHeader
import com.example.meomunjari.ui.components.SectionCard
import com.example.meomunjari.ui.theme.MColors
import kotlinx.coroutines.launch
import java.time.LocalDate

/** S5 도착 인증 + 차박 모드 세팅 선택. */
@Composable
fun ArrivalScreen(onStarted: () -> Unit, onRegister: () -> Unit, onSunrise: () -> Unit) {
    val c = LocalAppContainer.current
    val trip = c.trip
    val arrival by trip.arrival.collectAsStateWithLifecycle()
    val wake by trip.wakeTime.collectAsStateWithLifecycle()
    val visit by trip.certifiedVisit.collectAsStateWithLifecycle()
    val vehicle by trip.vehicle.collectAsStateWithLifecycle()
    val caps by c.gateways.vehicle.capabilities.collectAsStateWithLifecycle()
    val user by c.repos.user.user.collectAsStateWithLifecycle()
    val presets by c.repos.presets.presets.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val spot = visit?.let { c.repos.spots.get(it.spotId) } ?: arrival?.spot
    val month = LocalDate.now().monthValue
    val options = buildList {
        add(PresetOption(PresetSource.MINE, user.mySetting))
        add(PresetOption(PresetSource.SEASONAL, SettingCompatibility.seasonalDefault(month)))
        spot?.let { s ->
            SettingCompatibility.master(presets.filter { it.spotId == s.id }, user.nickname)
                ?.let { add(PresetOption(PresetSource.MASTER, it)) }
        }
    }
    var selected by remember(options.size) { mutableStateOf(options.lastIndex) }
    var askConsent by remember { mutableStateOf(false) }
    var askDoor by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val devices = remember { mutableStateListOf<String>() }

    fun start(preset: SettingPreset, includeDoor: Boolean, source: PresetSource) {
        busy = true
        scope.launch {
            val result = trip.startCamping(
                preset,
                includeDoor,
                loadedPresetId = preset.id.takeIf { source == PresetSource.MASTER },
                devices = devices.toList(),
            )
            busy = false
            when (result) {
                is StartCampingResult.Started -> onStarted()
                StartCampingResult.NotParked -> error = "P단으로 주차한 뒤에 차량을 제어할 수 있어요"
                StartCampingResult.NoSpot -> error = "먼저 스팟 도착 인증이 필요해요"
            }
        }
    }

    fun proceed(consentChecked: Boolean = false) {
        val option = options.getOrNull(selected) ?: return
        when {
            !user.consentToRecord && !consentChecked -> askConsent = true
            option.preset.openDoorArea != null && ControlItem.DOOR in caps -> askDoor = true
            else -> start(option.preset, includeDoor = false, source = option.source)
        }
    }

    val advice by trip.parkingAdvice.collectAsStateWithLifecycle()

    Column {
        ScreenHeader(
            title = if (visit != null) "도착했어요! 🎉" else "도착을 확인하고 있어요",
            subtitle = when {
                visit != null && spot != null -> "${spot.name} 방문이 인증됐어요"
                spot != null -> spot.name
                else -> "목적지를 정하거나 스팟 근처에 주차해 주세요"
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(0.85f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionCard {
                    val a = arrival
                    val inRadius = visit != null || a?.withinRadius == true
                    val parked = visit != null || DriveGuard.isParked(vehicle)
                    CheckRow("📍", "스팟 반경 안이에요", "${Geo.formatDistance((spot?.radiusM ?: 1000).toDouble())} 안" + (a?.distanceM?.let { " · 지금 ${Geo.formatDistance(it)}" } ?: ""), inRadius)
                    ThinDivider()
                    CheckRow("🅿️", "P단 주차", "지금 ${vehicle.gear.label}단 · 5초 유지", parked)
                    ThinDivider()
                    CheckRow("✅", if (visit != null) "방문 인증 완료" else "인증을 기다리는 중", if (visit != null) "이제 리뷰를 쓸 수 있어요" else "조건을 모두 채우면 자동으로 인증돼요", visit != null)
                }
                if (visit == null && trip.isParkedAtUnknownPlace()) {
                    SectionCard {
                        ListRow("📍", "등록되지 않은 장소예요", "이곳을 새 스팟으로 등록할 수 있어요", iconBackground = MColors.Orange50, onClick = onRegister)
                    }
                }
                advice?.let { ad ->
                    SectionCard {
                        ListRow(
                            emoji = "🌅",
                            iconBackground = MColors.Orange50,
                            title = "해 뜨는 쪽으로 세워볼까요?",
                            subtitle = "${ad.sunrise.localDateTimeLabel} 일출 · ${ad.instruction()}",
                            onClick = onSunrise,
                        )
                    }
                }
                SectionCard(title = "이 차가 지원하는 제어") {
                    ChipFlow {
                        ControlItem.entries.forEach { item ->
                            TagChip(if (item in caps) item.label else "${item.label} 미지원", selected = item in caps, onClick = {})
                        }
                    }
                    Text("지원하지 않는 항목은 건너뛰고 나머지만 적용해요", style = MaterialTheme.typography.bodySmall, color = MColors.Grey500)
                }
            }

            Column(Modifier.weight(1.15f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (user.car.powerType == PowerType.EV) {
                    WakeTimeButton(wake, trip::selectWakeTime, enabled = !busy)
                }
                Text("어떤 세팅으로 잘까요?", style = MaterialTheme.typography.titleLarge)
                options.forEachIndexed { i, option ->
                    PresetCard(option, selected = i == selected, caps = caps, onClick = { selected = i })
                }
                SectionCard(title = "오늘 밤 쓸 외부 기기") {
                    ChipFlow {
                        ExternalDevices.ALL.forEach { d ->
                            TagChip(d, selected = d in devices, onClick = { if (d in devices) devices.remove(d) else devices.add(d) })
                        }
                    }
                    Text("실측 리포트에 함께 남아 다른 사람이 배터리 소모를 비교할 때 참고해요", style = MaterialTheme.typography.bodySmall, color = MColors.Grey500)
                }
                PrimaryButton(
                    text = if (busy) "차에 적용하고 있어요…" else "🌙  차박 모드 시작하기",
                    enabled = visit != null && !busy,
                    onClick = { proceed() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(96.dp))
            }
        }
    }

    if (askConsent) {
        AlertDialog(
            onDismissRequest = { askConsent = false },
            title = { Text("밤사이 차량 기록을 공유할까요?") },
            text = {
                Text(
                    "실내 온도, 배터리·연료 소모량, 와이퍼 작동 여부를 기록해 이 스팟의 실측 리포트에 보태요. " +
                        "리포트에는 누가 기록했는지 알 수 없는 집계값만 보여요.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    trip.setConsent(true)
                    askConsent = false
                    proceed(consentChecked = true)
                }) { Text("동의하고 기록") }
            },
            dismissButton = {
                TextButton(onClick = {
                    askConsent = false
                    options.getOrNull(selected)?.let { start(it.preset, includeDoor = false, source = it.source) }
                }) { Text("기록 없이 제어만") }
            },
        )
    }

    if (askDoor) {
        val option = options.getOrNull(selected)
        AlertDialog(
            onDismissRequest = { askDoor = false },
            title = { Text("도어를 열까요?") },
            text = {
                Text(
                    "${SettingCompatibility.doorLabel(option?.preset?.openDoorArea.orEmpty())}를 엽니다. 주변에 사람이나 장애물이 없는지 확인하셨나요?",
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askDoor = false
                    option?.let { start(it.preset, includeDoor = true, source = it.source) }
                }) { Text("확인했어요, 열기") }
            },
            dismissButton = {
                TextButton(onClick = {
                    askDoor = false
                    option?.let { start(it.preset, includeDoor = false, source = it.source) }
                }) { Text("도어는 빼고 적용") }
            },
        )
    }

    error?.let {
        AlertDialog(
            onDismissRequest = { error = null },
            text = { Text(it, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("확인") } },
        )
    }
}

@Composable
private fun CheckRow(emoji: String, title: String, subtitle: String, ok: Boolean) {
    ListRow(
        emoji = emoji,
        title = title,
        subtitle = subtitle,
        iconBackground = if (ok) MColors.Green50 else MColors.Grey100,
        trailing = { Text(if (ok) "✓" else "", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MColors.Green) },
    )
}

@Composable
private fun PresetCard(
    option: PresetOption,
    selected: Boolean,
    caps: Set<ControlItem>,
    onClick: () -> Unit,
) {
    val p = option.preset
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = MColors.Card,
        border = if (selected) BorderStroke(2.dp, MColors.Blue) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (option.source == PresetSource.MASTER) "고수의 세팅" else option.source.label, style = MaterialTheme.typography.titleMedium)
                        if (option.source == PresetSource.MASTER) Pill("${p.ownerGrade} ${p.ownerNickname}", MColors.Orange)
                    }
                    Text(
                        if (option.source == PresetSource.MASTER) "${p.loadCount}명이 이 세팅으로 잤어요" else SettingCompatibility.summary(p),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MColors.Grey500,
                    )
                }
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (selected) MColors.Blue else Color.Transparent)
                        .border(2.dp, if (selected) MColors.Blue else MColors.Grey200, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Text("✓", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (selected) {
                Spacer(Modifier.height(6.dp))
                SettingCompatibility.evaluate(p, caps).forEach { item ->
                    KeyValue(
                        item.item.label,
                        if (item.supported) item.valueLabel else "이 차는 지원하지 않아요",
                        valueColor = if (item.supported) MColors.Text else MColors.Grey400,
                    )
                }
            }
        }
    }
}
