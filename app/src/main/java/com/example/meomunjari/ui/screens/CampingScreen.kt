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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.meomunjari.core.vehicle.ApplyStatus
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.example.meomunjari.core.vehicle.ApplyItemResult
import com.example.meomunjari.ui.components.ThinDivider
import com.example.meomunjari.ui.LocalAppContainer
import com.example.meomunjari.ui.components.EmptyState
import com.example.meomunjari.ui.components.Pill
import com.example.meomunjari.ui.components.PrimaryButton
import com.example.meomunjari.ui.components.ScreenHeader
import com.example.meomunjari.core.model.PowerType
import com.example.meomunjari.domain.CampingEnergyPlanner
import com.example.meomunjari.ui.components.SectionCard
import com.example.meomunjari.ui.components.Sparkline
import com.example.meomunjari.ui.components.StatTile
import com.example.meomunjari.ui.components.Toggle
import com.example.meomunjari.ui.theme.MColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** S6 차박 중 대시보드: 실측 기록 진행 + 배터리 보호. */
@Composable
fun CampingScreen(onFinished: () -> Unit) {
    val c = LocalAppContainer.current
    val trip = c.trip
    val camping by trip.camping.collectAsStateWithLifecycle()
    val vehicle by trip.vehicle.collectAsStateWithLifecycle()
    val scale by trip.timeScale.collectAsStateWithLifecycle()
    val user by c.repos.user.user.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val state = camping ?: run {
        EmptyState("진행 중인 차박이 없어요", "스팟에 도착해 P단으로 주차하면 차박 모드를 켤 수 있어요.")
        return
    }

    val hvacKnown = vehicle.connected && vehicle.hvacPowerKnown
    Column {
        ScreenHeader(
            title = "편안한 밤 보내세요",
            subtitle = "${state.spot.name} · ${state.preset.ownerNickname}님 세팅 · " + if (state.recording) "밤사이 기록 중" else "기록 없이 제어만",
            trailing = { if (state.recording) Pill("● 기록 중", MColors.Red) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1.2f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("실내 온도", vehicle.cabinTempC?.let { "${(it * 10).roundToInt() / 10.0}°C" } ?: "-", Modifier.weight(1f), icon = Icons.Filled.Thermostat)
                    StatTile(
                        user.car.powerType.energyLabel,
                        vehicle.energyPct?.let { "${(it * 10).roundToInt() / 10.0}%" } ?: "-",
                        Modifier.weight(1f),
                        icon = Icons.Filled.BatteryStd,
                        accent = if (state.protectionTriggered) MColors.Danger else MaterialTheme.colorScheme.onSurface,
                    )
                    StatTile(
                        "공조",
                        when {
                            !hvacKnown -> "확인 중"
                            vehicle.hvacOn -> vehicle.hvacTargetC?.let { "${it.roundToInt()}°C" } ?: "켜짐"
                            else -> "꺼짐"
                        },
                        Modifier.weight(1f),
                        icon = Icons.Filled.AcUnit,
                    )
                    StatTile("경과", "${(state.elapsedHours * 10).roundToInt() / 10.0}h", Modifier.weight(1f), icon = Icons.Filled.Schedule)
                }

                state.notice?.let {
                    SectionCard { Text(it, style = MaterialTheme.typography.titleMedium, color = MColors.Warn) }
                }

                if (state.recording) {
                    SectionCard(title = "실내 온도", trailing = { Text("10분 간격", style = MaterialTheme.typography.bodySmall, color = MColors.Grey500) }) {
                        val temps = state.samples.mapNotNull { it.cabinTempC }
                        if (temps.size >= 2) {
                            Sparkline(temps)
                            Text(
                                "최저 ${(temps.min() * 10).roundToInt() / 10.0}°C · 최고 ${(temps.max() * 10).roundToInt() / 10.0}°C",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            Text("첫 기록을 기다리는 중이에요", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (state.samples.any { it.wiperActive }) {
                            Text("🌧️ 와이퍼가 움직였어요. 비 온 밤으로 기록할게요", style = MaterialTheme.typography.bodyMedium, color = MColors.Purple)
                        }
                    }
                }

                SectionCard(title = "적용된 세팅") {
                    state.applyResults.forEachIndexed { i, r ->
                        if (i > 0) ThinDivider()
                        AppliedItemRow(r)
                    }
                }
            }

            Column(Modifier.weight(0.8f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (user.car.powerType == PowerType.EV) {
                    SectionCard(title = "아침 예상 잔량") {
                        val night = if (hvacKnown) CampingEnergyPlanner.estimateNight(
                            state.spot, state.startedAt, user.car, vehicle.hvacTargetC?.toDouble(), vehicle.hvacOn,
                            elapsedHours = state.elapsedHours, stayMinutes = state.plannedMinutes,
                        ) else null
                        val pct = vehicle.batteryPct?.takeIf { vehicle.connected && it in 0f..100f }
                        Text(when {
                            state.elapsedSimMs >= state.plannedMinutes * 60_000L -> "예정한 기상 시간이 지났어요"
                            !hvacKnown || pct == null -> "차량 배터리와 공조 상태를 확인하고 있어요"
                            night == null -> "현재 조건의 야간 전력 자료가 부족해요"
                            else -> "${Math.round((pct - night.drainPct).coerceAtLeast(0.0))}% · ${state.wakeAt?.let { "$it 기상 기준" } ?: "차박 시작 8시간 기준"}"
                        })
                        if (hvacKnown && state.elapsedSimMs < state.plannedMinutes * 60_000L)
                            Text(energyBasisText(vehicle.hvacOn), style = MaterialTheme.typography.bodySmall)
                    }
                }
                SectionCard(title = "배터리 지킴이") {
                    Text("잔량이 ${state.protectionThresholdPct}% 이하일 때 공조 끄기를 요청해요.", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = state.protectionThresholdPct.toFloat(),
                        onValueChange = { trip.setProtectionThreshold(it.roundToInt()) },
                        valueRange = 5f..50f,
                        steps = 8,
                    )
                }
                SectionCard(title = "공조") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(when { !hvacKnown -> "상태 확인 중"; vehicle.hvacOn -> "켜짐"; else -> "꺼짐" }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Toggle(checked = vehicle.hvacOn, enabled = hvacKnown, onCheckedChange = { on -> scope.launch { trip.setHvac(on) } })
                    }
                }
                SectionCard(title = "시연 가속") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("1초 = 10분", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Toggle(checked = scale >= 600, onCheckedChange = { trip.setTimeScale(if (it) 600 else 1) })
                    }
                    Text("밤새 기록을 몇 분 만에 보여주는 데모용 설정이에요.", style = MaterialTheme.typography.bodySmall, color = MColors.Grey500)
                }
                PrimaryButton(
                    "☀️  차박 끝내고 리포트 보기",
                    icon = Icons.Filled.WbSunny,
                    onClick = {
                        trip.endCamping()
                        onFinished()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(96.dp))
            }
        }
    }
}

/**
 * 적용된 세팅 한 줄: 왼쪽에 항목 이름, 오른쪽에 결과 배지와 세부 내용을 한 줄씩.
 * 세부 내용은 " · "로 이어진 문구를 나눠 보여주고, "이름: 이유"처럼 안 된 부분은 주황색으로 표시한다.
 */
@Composable
private fun AppliedItemRow(r: ApplyItemResult) {
    val color = when (r.status) {
        ApplyStatus.APPLIED -> MColors.Good
        ApplyStatus.PARTIAL -> MColors.Orange
        ApplyStatus.FAILED -> MColors.Danger
        else -> MColors.Grey500
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Text(
            r.item.label,
            style = MaterialTheme.typography.bodyLarge,
            color = MColors.Grey600,
            modifier = Modifier.width(96.dp).padding(top = 2.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Pill(r.status.label, color)
            r.detail.split(" · ").filter { it.isNotBlank() }.forEach { part ->
                val failed = part.contains(": ")
                Text(
                    if (failed) "✕ $part" else part,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (failed) MColors.Orange else MColors.Text,
                )
            }
        }
    }
}
