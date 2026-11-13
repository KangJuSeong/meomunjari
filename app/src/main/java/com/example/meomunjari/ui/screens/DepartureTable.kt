package com.example.meomunjari.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.example.meomunjari.domain.DepartureComparison
import com.example.meomunjari.domain.RouteEstimate
import com.example.meomunjari.ui.components.ThinDivider
import com.example.meomunjari.ui.theme.MColors
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

@Composable
internal fun DepartureTable(comparison: DepartureComparison, wake: LocalTime?) {
    val direct = comparison.direct
    val via = comparison.via
    val stop = comparison.stop
    Text("${comparison.departure.toLocalTime()} 출발 기준", style = MaterialTheme.typography.bodySmall)
    ComparisonRow("", "직행", "충전소 경유", emphasis = true)
    ThinDivider()
    fun eta(route: RouteEstimate): String = when {
        !route.reachable -> "도달 어려움"
        else -> {
            val day = ChronoUnit.DAYS.between(comparison.departure.toLocalDate(), route.arrival.toLocalDate())
            val prefix = if (day == 0L) "" else if (day == 1L) "내일 " else "${day}일 뒤 "
            prefix + route.arrival.toLocalTime()
        }
    }
    fun morning(route: RouteEstimate): String = when {
        !route.reachable -> "도달 어려움"
        route.stayMinutes < 0 -> "기상 후 도착"
        route.morningPct != null -> "${route.morningPct.roundToInt()}%"
        route.sampleCount < 2 -> "측정중"
        else -> "기온 자료 부족"
    }
    ComparisonRow("차박지 도착", eta(direct), via?.let(::eta) ?: "—")
    ComparisonRow("도착 잔량", if (direct.reachable) "${direct.arrivalPct.roundToInt()}%" else "—",
        via?.let { "${it.arrivalPct.roundToInt()}%" } ?: "—")
    if (wake != null) {
        fun stay(route: RouteEstimate): String = if (!route.reachable || route.stayMinutes < 0) "—"
            else "${route.stayMinutes / 60}시간 ${route.stayMinutes % 60}분"
        ComparisonRow("예상 체류", stay(direct), via?.let(::stay) ?: "—")
    }
    ComparisonRow(wake?.let { "$it 기상 잔량" } ?: "8시간 뒤 잔량", morning(direct), via?.let(::morning) ?: "—", emphasis = true)
    ThinDivider()
    if (stop == null) {
        Text("도달 가능한 경로상 충전소가 없어요.")
    } else {
        val label = if (comparison.requiresCharge) "권장 충전" else "선택 충전"
        Text("${stop.station.name} · $label ${stop.minutes}분", style = MaterialTheme.typography.bodyMedium)
    }
    fun evidence(route: RouteEstimate) = "EV 기록 ${route.sampleCount}건" + when (route.sampleCount) {
        0, 1 -> " · 측정중"
        2 -> " · 참고치"
        else -> ""
    }
    val basis = if (via == null || via.sampleCount == direct.sampleCount) evidence(direct)
        else "직행 ${evidence(direct)} / 경유 ${evidence(via)}"
    Text("$basis · 목업 기온 기준", style = MaterialTheme.typography.bodySmall)
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
