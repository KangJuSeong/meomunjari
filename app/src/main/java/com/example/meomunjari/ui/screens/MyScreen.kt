package com.example.meomunjari.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.meomunjari.core.model.PowerType
import com.example.meomunjari.core.model.SettingPreset
import com.example.meomunjari.core.model.VehicleClass
import com.example.meomunjari.domain.BadgeEngine
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import com.example.meomunjari.core.vehicle.ApplyStatus
import com.example.meomunjari.domain.SeatPosition
import com.example.meomunjari.ui.LocalAppContainer
import com.example.meomunjari.ui.components.KeyValue
import com.example.meomunjari.ui.components.Pill
import com.example.meomunjari.ui.components.ProgressRow
import com.example.meomunjari.ui.components.ScreenHeader
import com.example.meomunjari.ui.components.SectionCard
import com.example.meomunjari.ui.components.Toggle
import com.example.meomunjari.ui.theme.MColors
import kotlin.math.roundToInt

/** S9 마이: 등급·뱃지, 내 차 프로필, 내 세팅, 방문 기록. */
@Composable
fun MyScreen() {
    val c = LocalAppContainer.current
    val user by c.repos.user.user.collectAsStateWithLifecycle()
    val spots by c.repos.spots.spots.collectAsStateWithLifecycle()
    val status = BadgeEngine.evaluate(user, spots)
    val memorySlots by c.gateways.vehicle.seatMemorySlots.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    var seatNotice by remember { mutableStateOf<String?>(null) }

    /** 차박 중이면 바뀐 시트를 차에도 바로 적용하고 결과를 알려준다. */
    fun pushSeatToCar() {
        scope.launch {
            val r = c.trip.applyMySeatToCamping() ?: return@launch
            seatNotice = when (r.status) {
                ApplyStatus.APPLIED -> "지금 차박 중인 차에 적용했어요"
                ApplyStatus.PARTIAL -> "일부만 적용했어요 · ${r.detail}"
                ApplyStatus.UNSUPPORTED -> "이 차는 시트 조절을 지원하지 않아요"
                else -> "시트를 적용하지 못했어요. 다시 눌러 주세요"
            }
        }
    }

    fun updateSetting(transform: (SettingPreset) -> SettingPreset) =
        c.repos.user.update { it.copy(mySetting = transform(it.mySetting)) }

    /** 등받이 각도를 고치면 지금 슬롯의 저장분도 같이 갱신한다. */
    fun updateBackrests(transform: (Map<String, Int>) -> Map<String, Int>) = c.repos.user.update { u ->
        val next = transform(u.mySetting.backrestPctBySeat)
        u.copy(
            mySetting = u.mySetting.copy(backrestPctBySeat = next),
            seatSlotBackrests = u.seatSlotBackrests + ((u.mySetting.seatMemorySlot ?: 0) to next),
        )
    }

    /** 슬롯을 바꾸면 지금 슬롯의 각도를 저장해 두고, 고른 슬롯에 저장된 각도를 불러온다. */
    fun selectSlot(slot: Int?) = c.repos.user.update { u ->
        val saved = u.seatSlotBackrests + ((u.mySetting.seatMemorySlot ?: 0) to u.mySetting.backrestPctBySeat)
        u.copy(
            mySetting = u.mySetting.copy(seatMemorySlot = slot, backrestPctBySeat = saved[slot ?: 0] ?: emptyMap()),
            seatSlotBackrests = saved,
        )
    }

    Column {
        ScreenHeader(title = "${user.nickname}님은 ${status.grade.label}${copula(status.grade.label)}", subtitle = "${status.points}P · 뱃지 ${status.earnedCount}개 획득")
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1.1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionCard(title = "회원 등급") {
                    val next = status.grade.next
                    ProgressRow(
                        label = status.grade.label + (next?.let { " → ${it.label}" } ?: ""),
                        ratio = status.progressToNext(),
                        trailing = next?.let { "${status.points}/${it.minPoints}P" } ?: "최고 등급",
                    )
                    Text(
                        "등록 ${BadgeEngine.POINT_REGISTER} · 방문 ${BadgeEngine.POINT_VISIT} · 실측 ${BadgeEngine.POINT_REPORT} · 리뷰 ${BadgeEngine.POINT_REVIEW} · 뱃지 ${BadgeEngine.POINT_BADGE}P",
                        style = MaterialTheme.typography.bodySmall,
                        color = MColors.Grey500,
                    )
                }
                SectionCard(title = "뱃지") {
                    status.badges.forEach { BadgeLine(it) }
                }
                SectionCard(title = "방문 기록 ${user.visits.size}회") {
                    user.visits.sortedByDescending { it.date }.take(8).forEach { v ->
                        KeyValue(spots.firstOrNull { it.id == v.spotId }?.name ?: v.spotId, v.date)
                    }
                }
                Spacer(Modifier.height(96.dp))
            }

            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionCard(title = "내 차 · ${user.car.model}") {
                    Text("차급 (스팟 진입 필터에 써요)", style = MaterialTheme.typography.labelLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        VehicleClass.entries.forEachIndexed { i, vc ->
                            SegmentedButton(
                                selected = user.car.vehicleClass == vc,
                                onClick = { c.repos.user.update { it.copy(car = it.car.copy(vehicleClass = vc)) } },
                                shape = SegmentedButtonDefaults.itemShape(i, VehicleClass.entries.size),
                            ) { Text(vc.label, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    Text("동력원", style = MaterialTheme.typography.labelLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        PowerType.entries.forEachIndexed { i, pt ->
                            SegmentedButton(
                                selected = user.car.powerType == pt,
                                onClick = { c.repos.user.update { it.copy(car = it.car.copy(powerType = pt)) } },
                                shape = SegmentedButtonDefaults.itemShape(i, PowerType.entries.size),
                            ) { Text(pt.label) }
                        }
                    }
                    if (user.car.powerType == PowerType.EV) {
                        KeyValue("배터리 용량", "${user.car.batteryKWh} kWh")
                        KeyValue("기본 전비", "${user.car.efficiencyKmPerKWh} km/kWh")
                    }
                    Text(
                        "Vehicle SDK CarInfo로는 차 크기를 알 수 없어 직접 정해요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MColors.Grey500,
                    )
                }

                SectionCard(title = "내 차박 세팅") {
                    val s = user.mySetting
                    LabeledSlider("목표 온도", "${s.targetTempC.roundToInt()}°C", s.targetTempC.toFloat(), 16f..28f, 11) { v ->
                        updateSetting { it.copy(targetTempC = v.roundToInt().toDouble()) }
                    }
                    LabeledSlider("창문 열기", "${s.windowOpenPct}%", s.windowOpenPct.toFloat(), 0f..30f, 5) { v ->
                        updateSetting { it.copy(windowOpenPct = v.roundToInt()) }
                    }
                    Text("시트 메모리", style = MaterialTheme.typography.labelLarge)
                    // 차가 알려준 메모리 번호 범위만 보여준다(예: 아이오닉 5 에뮬레이터는 1~2번). 모르면 1~3번.
                    val memoryRange = memorySlots ?: 1..3
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val slots = listOf<Int?>(null) + memoryRange.toList()
                        slots.forEachIndexed { i, slot ->
                            SegmentedButton(
                                selected = s.seatMemorySlot == slot,
                                onClick = { selectSlot(slot); pushSeatToCar() },
                                shape = SegmentedButtonDefaults.itemShape(i, slots.size),
                            ) { Text(slot?.let { "${it}번" } ?: "안 씀") }
                        }
                    }
                    s.seatMemorySlot?.takeIf { it !in memoryRange }?.let { slot ->
                        Text(
                            "저장된 메모리 ${slot}번은 이 차에 없어요. 이 차는 ${memoryRange.first}~${memoryRange.last}번을 지원해요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MColors.Danger,
                        )
                    }
                    Text(
                        "좌석별 등받이 눕힘 · ${s.seatMemorySlot?.let { "메모리 ${it}번" } ?: "메모리 안 씀"}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    SeatPosition.entries.forEach { pos ->
                        val deg = s.backrestPctBySeat[pos.area]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(pos.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text(
                                deg?.let { "${it}%" } ?: "그대로",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (deg == null) MColors.Grey500 else MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.width(12.dp))
                            Toggle(
                                checked = deg != null,
                                onCheckedChange = { on ->
                                    updateBackrests {
                                        if (on) it + (pos.area to SeatPosition.DEFAULT_PCT) else it - pos.area
                                    }
                                    pushSeatToCar()
                                },
                            )
                        }
                        if (deg != null) {
                            val steps = (SeatPosition.MAX_PCT - SeatPosition.MIN_PCT) / SeatPosition.STEP_PCT - 1
                            Slider(
                                value = deg.toFloat(),
                                onValueChange = { v ->
                                    val snapped = (v / SeatPosition.STEP_PCT).roundToInt() * SeatPosition.STEP_PCT
                                    updateBackrests { it + (pos.area to snapped) }
                                },
                                onValueChangeFinished = { pushSeatToCar() },
                                valueRange = SeatPosition.MIN_PCT.toFloat()..SeatPosition.MAX_PCT.toFloat(),
                                steps = steps,
                            )
                        }
                    }
                    seatNotice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MColors.Blue) }
                    Text("메모리 번호마다 따로 저장해요. 번호를 바꾸면 그 번호에 저장한 값이 불러와져요. 0%는 가장 세운 자세, 100%는 가장 눕힌 자세이고 차가 등받이 조절을 지원하는 좌석만 적용돼요.", style = MaterialTheme.typography.bodySmall, color = MColors.Grey500)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("앰비언트 조명", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Toggle(checked = s.ambientOn, onCheckedChange = { on -> updateSetting { it.copy(ambientOn = on) } })
                    }
                    if (s.ambientOn) {
                        LabeledSlider("밝기", "${s.ambientBrightness}%", s.ambientBrightness.toFloat(), 0f..100f, 9) { v ->
                            updateSetting { it.copy(ambientBrightness = v.roundToInt()) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("짐 꺼낼 때 뒷좌석 우측 도어 열기", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Toggle(
                            checked = s.openDoorArea != null,
                            onCheckedChange = { on -> updateSetting { it.copy(openDoorArea = if (on) "ROW_2_RIGHT" else null) } },
                        )
                    }
                }

                SectionCard(title = "데이터") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("차박 중 차량 기록 공유", style = MaterialTheme.typography.bodyLarge)
                            Text("차박 중에 끄면 이번 기록을 지워요. 다시 켜면 다음 차박부터 기록해요.", style = MaterialTheme.typography.bodySmall, color = MColors.Grey500)
                            Text("실내 온도·소모량·와이퍼를 익명 집계로만 공개", style = MaterialTheme.typography.bodySmall, color = MColors.Grey500)
                        }
                        Toggle(checked = user.consentToRecord, onCheckedChange = { on -> c.trip.setConsent(on) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("연동 방식", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Pill(c.gateways.flavorLabel, MColors.Teal)
                    }
                }
                Spacer(Modifier.height(96.dp))
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = current, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

/** 받침 유무에 따라 "예요"/"이에요". */
private fun copula(word: String): String {
    val last = word.lastOrNull() ?: return "예요"
    if (last !in '가'..'힣') return "예요"
    return if ((last - '가') % 28 != 0) "이에요" else "예요"
}
