package com.example.meomunjari.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.example.meomunjari.core.model.SettingPreset
import com.example.meomunjari.domain.DepartureComparison
import com.example.meomunjari.ui.components.ThinDivider
import com.example.meomunjari.ui.theme.MColors
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

@Composable
internal fun DepartureTable(comparison: DepartureComparison, wake: LocalTime?, setting: SettingPreset) {
    val direct = comparison.direct
    val stop = comparison.stop
    val viaTime = if (stop == null) "—" else "시각 미확인"
    val climate = if (setting.hvacOn) "공조 ${setting.targetTempC.roundToInt()}°C" else "공조 끔"
    Text("${comparison.departure.toLocalTime()} 출발 · 내 기본 세팅 $climate", style = MaterialTheme.typography.bodySmall)
    ComparisonRow("", "직행", "충전소 경유", emphasis = true)
    ThinDivider()
    val directEta = if (direct.reachable) {
        val days = ChronoUnit.DAYS.between(comparison.departure.toLocalDate(), direct.arrival.toLocalDate())
        val prefix = when (days) { 0L -> ""; 1L -> "내일 "; else -> "${days}일 뒤 " }
        prefix + direct.arrival.toLocalTime()
    } else "도달 어려움"
    val directMorning = when {
        !direct.reachable -> "도달 어려움"
        direct.morningPct != null -> "${direct.morningPct.roundToInt()}%"
        else -> "예측 자료 부족"
    }
    ComparisonRow("차박지 도착 예상", directEta, viaTime)
    ComparisonRow("도착 잔량", if (direct.reachable) "${direct.arrivalPct.roundToInt()}%" else "—",
        comparison.viaArrivalPct?.let { "${it.roundToInt()}%" } ?: "—")
    if (wake != null) {
        val stay = if (direct.reachable) "${direct.stayMinutes / 60}시간 ${direct.stayMinutes % 60}분" else "—"
        ComparisonRow("예상 체류", stay, "—")
    }
    ComparisonRow(wake?.let { "$it 기상 잔량" } ?: "8시간 뒤 잔량", directMorning, "—", emphasis = true)
    ThinDivider()
    if (stop == null) {
        Text("도달 가능한 경로상 충전소가 없어요.")
    } else {
        val label = when {
            comparison.requiresCharge && direct.morningPct == null -> "주행 기준 권장 충전"
            comparison.requiresCharge -> "권장 충전"
            else -> "선택 충전"
        }
        Text("${stop.station.name} · 정차 후 $label 약 ${stop.minutes}분", style = MaterialTheme.typography.bodyMedium)
        Text("충전소까지 걸리는 시간을 알 수 없어 경유 시 차박지 도착 시각과 기상 잔량은 표시하지 않아요.",
            style = MaterialTheme.typography.bodySmall)
    }
    if (direct.reachable && direct.morningPct == null) {
        Text("차종·공조 설정·목업 기온에 맞는 야간 전력 자료가 부족해 직행 시 기상 잔량을 계산할 수 없어요.",
            style = MaterialTheme.typography.bodySmall)
    }
    Text(energyBasisText(setting.hvacOn), style = MaterialTheme.typography.bodySmall)
}

internal fun energyBasisText(heating: Boolean): String = if (heating) {
    "난방 계산은 아이오닉 5 AWD 72.6 kWh의 외기 0°C·AUTO 22°C 시험 전력을 써요. 목업은 초기 난방 20분, 대기전력 0.2 kW를 가정해요."
} else {
    "아이오닉 5 AWD 72.6 kWh · 공조를 끈 동안 대기전력 0.2 kW를 가정해요."
}

@Composable
private fun ComparisonRow(label: String, direct: String, via: String, emphasis: Boolean = false) {
    val weight = if (emphasis) FontWeight.Bold else FontWeight.Normal
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1.2f), style = MaterialTheme.typography.bodyMedium, color = MColors.Grey600)
        Text(direct, Modifier.weight(1f), textAlign = TextAlign.End, fontWeight = weight)
        Text(via, Modifier.weight(1f), textAlign = TextAlign.End, fontWeight = weight,
            color = if (emphasis) MColors.Blue else MColors.Text)
    }
}
