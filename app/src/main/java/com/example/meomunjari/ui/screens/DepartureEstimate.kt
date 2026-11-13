package com.example.meomunjari.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.example.meomunjari.core.model.SettingPreset
import com.example.meomunjari.domain.RouteEstimate
import com.example.meomunjari.ui.components.Callout
import com.example.meomunjari.ui.components.KeyValue
import com.example.meomunjari.ui.theme.MColors
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

@Composable
internal fun DepartureEstimate(estimate: RouteEstimate, wake: LocalTime?, setting: SettingPreset) {
    if (!estimate.reachable) {
        Callout("현재 배터리로는 이 차박지까지 도착하기 어려워요.", MColors.Orange)
        return
    }

    val days = ChronoUnit.DAYS.between(estimate.departure.toLocalDate(), estimate.arrival.toLocalDate())
    val dayLabel = when (days) { 0L -> ""; 1L -> "내일 "; else -> "${days}일 뒤 " }
    val climate = if (setting.hvacOn) "공조 ${setting.targetTempC.roundToInt()}°C" else "공조 끔"
    Text("${estimate.departure.toLocalTime()} 출발 · $climate", style = MaterialTheme.typography.bodySmall)
    KeyValue("차박지 도착 예상", "$dayLabel${estimate.arrival.toLocalTime()}")
    KeyValue("도착 예상 잔량", "${estimate.arrivalPct.roundToInt()}%")
    if (wake != null) {
        KeyValue("예상 체류", "${estimate.stayMinutes / 60}시간 ${estimate.stayMinutes % 60}분")
    }
    val morning = estimate.morningPct
    KeyValue(
        wake?.let { "$it 기상 예상 잔량" } ?: "도착 8시간 뒤 예상 잔량",
        morning?.let { "${it.roundToInt()}%" } ?: "예측 자료 부족",
        valueColor = if (morning == null) MColors.Grey600 else MColors.Blue,
    )
    if (morning == null) {
        Text("차종·공조 설정·예상 기온에 맞는 야간 소비전력 자료가 부족해요.", style = MaterialTheme.typography.bodySmall)
    } else {
        if (morning == 0.0) {
            Callout("예정한 기상 시각까지 배터리가 부족할 수 있어요.", MColors.Orange)
        }
        Text(energyBasisText(setting.hvacOn), style = MaterialTheme.typography.bodySmall)
    }
}

internal fun energyBasisText(heating: Boolean): String = if (heating) {
    "난방 계산은 아이오닉 5 AWD 72.6 kWh의 외기 0°C·AUTO 22°C 시험 전력을 써요. 목업은 초기 난방 20분, 대기전력 0.2 kW를 가정해요."
} else {
    "아이오닉 5 AWD 72.6 kWh · 공조를 끈 동안 대기전력 0.2 kW를 가정해요."
}
