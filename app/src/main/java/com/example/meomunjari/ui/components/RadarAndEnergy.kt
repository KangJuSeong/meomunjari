package com.example.meomunjari.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.meomunjari.core.Geo
import com.example.meomunjari.core.model.LatLng
import com.example.meomunjari.core.model.Spot
import com.example.meomunjari.domain.EnergyForecast
import com.example.meomunjari.domain.ForecastBasis
import com.example.meomunjari.domain.SpotHit
import com.example.meomunjari.domain.Verdict
import com.example.meomunjari.ui.theme.MColors
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 지도 SDK 대신 쓰는 '주변 레이더'. 현재 위치가 중심, 위쪽이 북쪽.
 * 거리는 제곱근 스케일이라 가까운 스팟이 덜 겹친다.
 */
@Composable
fun SpotRadar(origin: LatLng?, hits: List<SpotHit>, maxKm: Double, highlightId: String? = null, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MColors.Grey50)
            .padding(12.dp),
    ) {
        Canvas(Modifier.fillMaxWidth().height(220.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = minOf(size.width, size.height) / 2 - 8f
            listOf(0.33f, 0.66f, 1f).forEach { r ->
                drawCircle(MColors.Grey200, radius = radius * r, center = center, style = Stroke(width = 3f))
            }
            drawCircle(MColors.Blue.copy(alpha = 0.2f), radius = 22f, center = center)
            drawCircle(MColors.Blue, radius = 11f, center = center)
            if (origin == null) return@Canvas
            hits.forEach { hit ->
                val d = (hit.distanceM ?: return@forEach) / 1000.0
                if (d > maxKm) return@forEach
                val bearing = Math.toRadians(Geo.bearingDeg(origin, hit.spot.location))
                val r = radius * sqrt(d / maxKm).toFloat()
                val p = Offset(center.x + (r * sin(bearing)).toFloat(), center.y - (r * cos(bearing)).toFloat())
                val hot = hit.spot.id == highlightId
                drawCircle(if (hot) MColors.Blue else MColors.Grey400, radius = if (hot) 15f else 11f, center = p)
            }
        }
        Text(
            "반경 ${maxKm.roundToInt()}km · 위쪽이 북쪽",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MColors.Grey500,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

/** 스팟 대표 이모지와 아이콘 배경색 (사진 대용). */
fun spotEmoji(spot: Spot): Pair<String, androidx.compose.ui.graphics.Color> = when {
    spot.hasTag("#별보기") -> "🌌" to MColors.Purple50
    spot.hasTag("#바다뷰") -> "🌊" to MColors.Blue50
    spot.hasTag("#호수") -> "🏞️" to MColors.Green50
    spot.hasTag("#산속") -> "⛰️" to MColors.Grey100
    else -> "🏕️" to MColors.Orange50
}

/** 스팟 목록 한 줄. */
@Composable
fun SpotRow(hit: SpotHit, onClick: () -> Unit, extra: String? = null) {
    val (emoji, bg) = spotEmoji(hit.spot)
    ListRow(
        emoji = emoji,
        iconBackground = bg,
        title = hit.spot.name,
        subtitle = extra ?: listOfNotNull(
            hit.distanceM?.let { Geo.formatDistance(it) },
            "★ ${Math.round(hit.spot.rating * 10) / 10.0}",
            if (hit.spot.liveCampers > 0) "지금 ${hit.spot.liveCampers}대" else "방문 ${hit.spot.visitCount}",
            hit.spot.sortedTags.take(2).joinToString(" ") { it.tag },
        ).joinToString(" · "),
        onClick = onClick,
    )
}

/** "지금 → 도착 → 내일 아침" 에너지 흐름. 숫자를 크게. */
@Composable
fun EnergyFlow(forecast: EnergyForecast) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            EnergyStep("지금", forecast.nowPct)
            Arrow()
            EnergyStep("도착", forecast.arrivePct)
            Arrow()
            EnergyStep("내일 아침", forecast.morningPct, highlight = true)
        }
        ProgressBar((forecast.morningPct / 100.0).toFloat())
        val ok = forecast.verdict == Verdict.ENOUGH
        Callout((if (ok) "✓ " else "! ") + forecast.message(), if (ok) MColors.Green else MColors.Orange)
        val basis = when (forecast.basis) {
            ForecastBasis.MEASURED -> "이 스팟 실측 ${forecast.sampleCount}회 기반"
            ForecastBasis.REFERENCE -> "실측 ${forecast.sampleCount}회뿐이라 참고치예요"
            ForecastBasis.SEASONAL_DEFAULT -> "실측이 없어 계절 기본값으로 계산했어요"
        }
        Text(
            "$basis · 밤새 공조 ${forecast.stayHours.roundToInt()}시간 예상 −${forecast.overnightPct.roundToInt()}%",
            fontSize = 16.sp,
            color = MColors.Grey500,
        )
    }
}

@Composable
private fun EnergyStep(title: String, pct: Double, highlight: Boolean = false) {
    Column {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MColors.Grey500)
        Text(
            "${pct.roundToInt()}%",
            fontSize = 44.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-1.2).sp,
            color = if (highlight) MColors.Blue else MColors.Text,
        )
    }
}

@Composable
private fun Arrow() {
    Text("→", fontSize = 28.sp, color = MColors.Grey400, modifier = Modifier.padding(bottom = 10.dp))
}
