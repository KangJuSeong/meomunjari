package com.example.meomunjari.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.meomunjari.core.Geo
import com.example.meomunjari.domain.EnergyPredictor
import com.example.meomunjari.ui.LocalAppContainer
import com.example.meomunjari.ui.components.PrimaryButton
import com.example.meomunjari.ui.components.Pill
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.meomunjari.ui.theme.MColors
import kotlin.math.roundToInt

/**
 * S4 주행 중 화면. 루트에서 모든 화면을 덮는다.
 * 검색·리뷰·사진은 보이지 않고, 목적지·남은 거리·도착 예상 잔량과 음성 검색만 남긴다.
 */
@Composable
fun DrivingScreen() {
    val c = LocalAppContainer.current
    val destination by c.trip.destination.collectAsStateWithLifecycle()
    val via by c.trip.chargingStop.collectAsStateWithLifecycle()
    val route by c.trip.route.collectAsStateWithLifecycle()
    val vehicle by c.trip.vehicle.collectAsStateWithLifecycle()
    val pending by c.trip.pendingVoiceTags.collectAsStateWithLifecycle()
    val user by c.repos.user.user.collectAsStateWithLifecycle()
    var voiceOpen by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.White)
            // 아래 화면으로 터치가 새지 않게 막는다.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Pill("주행 중", MColors.Blue)
            Text(destination?.name ?: "목적지 없이 주행 중", fontSize = 50.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp)
            via?.let { Text("${it.station.name} 경유 · 충전 약 ${it.minutes}분") }
            route?.let { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(56.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    BigNumber("남은 거리", "${(r.remainingKm * 10).roundToInt() / 10.0}km")
                    BigNumber("남은 시간", "${r.remainingMin}분")
                    vehicle.energyPct?.takeIf { via == null }?.let { pct ->
                        val arrive = pct - EnergyPredictor.consumptionPct(r.remainingKm, user.car, vehicle.efficiencyKmPerKWh?.toDouble())
                        BigNumber("도착하면", "${arrive.roundToInt().coerceAtLeast(0)}%", MColors.Blue)
                    }
                }
            }
            if (destination != null && route == null) {
                Text("위치를 확인하고 있어요", style = MaterialTheme.typography.bodyLarge, color = MColors.Grey500)
            }
            if (pending.isNotEmpty()) {
                Text("멈추면 ${pending.joinToString(" ")} 결과를 보여드릴게요", style = MaterialTheme.typography.titleMedium, color = MColors.Blue)
            }
            PrimaryButton("🎙️  말로 찾기", onClick = { voiceOpen = true }, modifier = Modifier.width(360.dp).height(76.dp))
            Text("안전을 위해 주행 중에는 검색·리뷰·사진을 숨겼어요", fontSize = 18.sp, color = MColors.Grey500)
            vehicle.speedKmh?.let {
                Text("${it.roundToInt()} km/h · 기어 ${vehicle.gear.label}", style = MaterialTheme.typography.bodySmall, color = MColors.Grey400)
            }
            destination?.let { d ->
                c.trip.location.value?.let { loc ->
                    Text("직선 ${Geo.formatDistance(Geo.distanceM(loc, d.location))}", style = MaterialTheme.typography.bodySmall, color = MColors.Grey400)
                }
            }
        }
    }

    if (voiceOpen) {
        VoiceSearchDialog(onDismiss = { voiceOpen = false }, onTags = { tags ->
            c.trip.queueVoiceTags(tags)
            voiceOpen = false
        })
    }
}

@Composable
private fun BigNumber(label: String, value: String, color: Color = MColors.Text) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MColors.Grey500)
        Text(value, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.2).sp, color = color)
    }
}
