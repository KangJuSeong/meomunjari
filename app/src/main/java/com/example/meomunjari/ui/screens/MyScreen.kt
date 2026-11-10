package com.example.meomunjari.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

    fun updateSetting(transform: (SettingPreset) -> SettingPreset) =
        c.repos.user.update { it.copy(mySetting = transform(it.mySetting)) }

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
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val slots = listOf<Int?>(null, 1, 2, 3)
                        slots.forEachIndexed { i, slot ->
                            SegmentedButton(
                                selected = s.seatMemorySlot == slot,
                                onClick = { updateSetting { it.copy(seatMemorySlot = slot) } },
                                shape = SegmentedButtonDefaults.itemShape(i, slots.size),
                            ) { Text(slot?.let { "${it}번" } ?: "안 씀") }
                        }
                    }
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
