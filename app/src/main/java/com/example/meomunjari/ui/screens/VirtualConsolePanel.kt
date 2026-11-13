package com.example.meomunjari.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.meomunjari.core.VirtualConsole
import com.example.meomunjari.core.vehicle.ControlItem
import com.example.meomunjari.core.vehicle.Gear
import com.example.meomunjari.ui.LocalAppContainer
import com.example.meomunjari.ui.components.Toggle
import java.util.UUID
import kotlin.math.roundToInt

/**
 * D1 가상 차량 콘솔 (mock 플레이버 전용).
 * Pleos 에뮬레이터의 Virtual Console처럼 위치·기어·배터리·온도·와이퍼를 바꿔 시연한다.
 */
@Composable
fun VirtualConsolePanel(console: VirtualConsole, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalAppContainer.current
    val vehicle by c.trip.vehicle.collectAsStateWithLifecycle()
    val outside by console.outsideTempC.collectAsStateWithLifecycle()
    val auto by console.autoDriving.collectAsStateWithLifecycle()
    val destination by c.trip.destination.collectAsStateWithLifecycle()
    val caps by c.gateways.vehicle.capabilities.collectAsStateWithLifecycle()
    val station by c.trip.arrivedStop.collectAsStateWithLifecycle()
    val scale by c.timeScale.collectAsStateWithLifecycle()
    val presets by c.repos.presets.presets.collectAsStateWithLifecycle()
    val member by c.repos.user.user.collectAsStateWithLifecycle()

    Surface(
        modifier = modifier.width(400.dp).fillMaxHeight().padding(12.dp),
        shape = RoundedCornerShape(24.dp),
        color = androidx.compose.ui.graphics.Color.White,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("가상 차량 콘솔", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "닫기") }
            }
            Text("mock 플레이버 전용 · Pleos Virtual Console 대용", style = MaterialTheme.typography.bodySmall, color = com.example.meomunjari.ui.theme.MColors.Grey500)

            HorizontalDivider()
            Text("위치", style = MaterialTheme.typography.titleMedium)
            console.locationPresets().forEach { preset ->
                TextButton(onClick = { console.setLocation(preset.location) }, modifier = Modifier.fillMaxWidth()) {
                    Text(preset.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("목적지까지 자동 주행", style = MaterialTheme.typography.bodyLarge)
                    Text(destination?.name ?: "목적지를 먼저 정하세요", style = MaterialTheme.typography.bodySmall, color = com.example.meomunjari.ui.theme.MColors.Grey500)
                }
                Toggle(checked = auto, enabled = destination != null && station == null, onCheckedChange = console::setAutoDrive)
            }

            HorizontalDivider()
            Text("기어 · 지금 ${vehicle.gear.label}", style = MaterialTheme.typography.titleMedium)
            val gears = listOf(Gear.PARK, Gear.REVERSE, Gear.NEUTRAL, Gear.DRIVE)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                gears.forEachIndexed { i, g ->
                    SegmentedButton(
                        selected = vehicle.gear == g,
                        onClick = { console.setGear(g) },
                        shape = SegmentedButtonDefaults.itemShape(i, gears.size),
                    ) { Text(g.label) }
                }
            }
            if (station != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("모의 충전", modifier = Modifier.weight(1f))
                    Toggle(checked = vehicle.chargeState == com.example.meomunjari.core.vehicle.ChargeState.CHARGING,
                        onCheckedChange = console::setCharging, enabled = vehicle.gear == Gear.PARK)
                }
            }
            ConsoleSlider("속도", "${(vehicle.speedKmh ?: 0f).roundToInt()} km/h", vehicle.speedKmh ?: 0f, 0f..120f, console::setSpeed)
            ConsoleSlider("배터리", "${(vehicle.batteryPct ?: 0f).roundToInt()}%", vehicle.batteryPct ?: 0f, 0f..100f, console::setBattery)
            ConsoleSlider("실내 온도", "${((vehicle.cabinTempC ?: 0f) * 10).roundToInt() / 10.0}°C", vehicle.cabinTempC ?: 0f, -5f..35f, console::setCabinTemp)
            ConsoleSlider("외기 온도", "${outside.roundToInt()}°C", outside, -15f..35f, console::setOutsideTemp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("와이퍼 (비)", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Toggle(checked = vehicle.wiperActive, onCheckedChange = console::setWiper)
            }

            HorizontalDivider()
            Text("시간 흐름", style = MaterialTheme.typography.titleMedium)
            val scales = listOf(1 to "실제", 60 to "1초=1분", 600 to "1초=10분")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                scales.forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = scale == value,
                        onClick = { c.trip.setTimeScale(value) },
                        shape = SegmentedButtonDefaults.itemShape(i, scales.size),
                    ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                }
            }

            HorizontalDivider()
            Text("차종 capability (미지원 시뮬레이션)", style = MaterialTheme.typography.titleMedium)
            ControlItem.entries.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = item in caps, onCheckedChange = { console.setCapability(item, it) })
                    Text(item.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
            HorizontalDivider()
            Text("공개 세팅 이용 시뮬레이션", style = MaterialTheme.typography.titleMedium)
            val myPreset = presets.lastOrNull { it.ownerNickname == member.nickname }
            if (myPreset == null) {
                Text("차박 리뷰에서 세팅을 공개하면 시험할 수 있어요.")
            } else {
                Text("내 세팅 불러온 횟수 · ${member.presetLoadsReceived}회")
                TextButton(onClick = {
                    c.repos.presets.recordLoad(myPreset.id, "가상 방문자", UUID.randomUUID().toString())
                }) { Text("다른 방문자가 ‘${myPreset.title}’ 불러오기") }
            }
        }
    }
}

@Composable
private fun ConsoleSlider(
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = current.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}
