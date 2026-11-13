package com.example.meomunjari.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.meomunjari.core.model.Spot
import com.example.meomunjari.core.navi.ChargingStop
import com.example.meomunjari.core.vehicle.ChargeState
import com.example.meomunjari.domain.CampingEnergyPlanner
import com.example.meomunjari.domain.EnergyPredictor
import com.example.meomunjari.domain.WakeSchedule
import com.example.meomunjari.trip.RouteProgress
import com.example.meomunjari.ui.LocalAppContainer
import com.example.meomunjari.ui.components.*
import com.example.meomunjari.ui.theme.MColors
import java.time.LocalDateTime
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun ChargingScreen(stop: ChargingStop, spot: Spot) {
    val c = LocalAppContainer.current
    val vehicle by c.trip.vehicle.collectAsStateWithLifecycle()
    val wake by c.trip.wakeTime.collectAsStateWithLifecycle()
    val user by c.repos.user.user.collectAsStateWithLifecycle()
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay((60 - value.second) * 1000L)
        }
    }
    val from = stop.station.location
    val arrival = now.plusMinutes(RouteProgress.estimate(from, spot).remainingMin.toLong())
    val setting = user.mySetting
    val night = CampingEnergyPlanner.estimateNight(spot, arrival.toLocalTime(), user.car, setting.targetTempC, setting.hvacOn,
        stayMinutes = WakeSchedule.stayMinutes(arrival.toLocalTime(), wake))
    val pct = vehicle.batteryPct?.takeIf { vehicle.connected && it in 0f..100f }
    val plan = pct?.let {
        CampingEnergyPlanner.plan(it.toDouble(), from, spot, user.car, vehicle.efficiencyKmPerKWh?.toDouble(),
            night, emptyList())
    }
    val target = stop.targetPct
    val ready = pct != null && target != null && pct >= target
    val charging = vehicle.chargeState == ChargeState.CHARGING
    val status = when (vehicle.chargeState) {
        ChargeState.CHARGING -> "충전 중"
        ChargeState.IDLE -> "충전 대기"
        ChargeState.FULL -> "차량 충전 완료"
        ChargeState.ERROR -> "충전 오류"
        ChargeState.UNKNOWN -> "충전 상태 확인 불가"
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenHeader(stop.station.name, "최종 목적지 · ${spot.name}", trailing = { Pill(status, MColors.Blue) })
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            SectionCard(Modifier.weight(1f), title = "배터리") {
                Text(pct?.let { "${it.roundToInt()}%" } ?: "잔량 확인 중", style = MaterialTheme.typography.displayMedium)
                pct?.let { ProgressBar(it / 100f, MColors.Blue) }
                KeyValue("경유 계획 목표 잔량", target?.let { "$it%" } ?: "확인 중")
                if (ready) {
                    Text("경유 계획의 목표 잔량에 도달했어요.", color = MColors.Teal)
                } else if (target != null && pct != null && charging) {
                    val minutes = ceil((target - pct) / EnergyPredictor.FAST_CHARGE_PCT_PER_MIN).toInt()
                    KeyValue("목표 잔량까지 · 계획 기준", "약 ${minutes}분")
                }
            }
            SectionCard(Modifier.weight(1f), title = "지금 출발하면") {
                KeyValue("차박지 도착 잔량", plan?.let { "${it.arrivalPct.roundToInt()}%" } ?: "확인 중")
                KeyValue(wake?.let { "$it 기상 예상 잔량" } ?: "8시간 뒤 예상 잔량",
                    plan?.morningPct?.let { "${it.roundToInt()}%" } ?: if (pct == null) "잔량 확인 중" else "예측 자료 부족")
                if (plan?.morningPct != null) Text(
                    if (plan.requiresCharge) "기상 후 인근 충전소까지 갈 여유가 부족해요."
                    else "기상 후 인근 충전소까지 갈 여유가 있어요.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(energyBasisText(setting.hvacOn), style = MaterialTheme.typography.bodySmall)
            }
        }
        c.gateways.console?.let { console ->
            if (!ready || charging) SecondaryButton(if (charging) "모의 충전 멈추기" else "모의 충전 시작",
                onClick = { console.setCharging(!charging) })
        }
        PrimaryButton(if (c.gateways.console == null) "차박지 안내로 돌아가기" else "차박지로 출발",
            onClick = c.trip::leaveChargingStop)
        Spacer(Modifier.height(72.dp))
    }
}
