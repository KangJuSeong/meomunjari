package com.example.meomunjari.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.meomunjari.domain.ParkingAdvice
import com.example.meomunjari.domain.SunriseMode
import com.example.meomunjari.ui.LocalAppContainer
import com.example.meomunjari.ui.components.Callout
import com.example.meomunjari.ui.components.EmptyState
import com.example.meomunjari.ui.components.ListRow
import com.example.meomunjari.ui.components.ScreenHeader
import com.example.meomunjari.ui.components.SecondaryButton
import com.example.meomunjari.ui.components.SectionCard
import com.example.meomunjari.ui.components.SegmentedPicker
import com.example.meomunjari.ui.components.StatTile
import com.example.meomunjari.ui.theme.MColors
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** S5-1 해 뜨는 방향에 맞춘 주차 안내. 일출 방위·차 앞머리 방향을 나침반으로 보여준다. */
@Composable
fun SunriseScreen(onBack: () -> Unit) {
    val trip = LocalAppContainer.current.trip
    val guidance by trip.parkingAdvice.collectAsStateWithLifecycle()
    val advice = guidance
    if (advice == null) {
        Column {
            ScreenHeader(title = "해 뜨는 방향 주차 안내", onBack = onBack)
            EmptyState("지금은 주차 방향을 확인할 수 없어요", "스팟 반경 안에 주차하고 위치가 확인되면 다음 일출을 안내해요.")
        }
        return
    }
    val heading = advice.currentHeadingDeg
    val mode = advice.mode

    Column {
        ScreenHeader(
            title = "해 뜨는 쪽으로 세워볼까요?",
            subtitle = "${advice.sunrise.localDateTimeLabel} 일출 예정 · 평지 지평선 기준",
            onBack = onBack,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            SectionCard(modifier = Modifier.weight(1.05f)) {
                Compass(advice, Modifier.weight(1f, fill = false).fillMaxWidth().aspectRatio(1f))
                Text(
                    "주황색 원은 일출, 파란 점선은 차 앞머리를 둘 방향이에요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MColors.Grey500,
                )
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SectionCard(title = "어떻게 보고 싶으세요?") {
                    SegmentedPicker(SunriseMode.entries, mode, { it.label }, trip::setSunriseMode)
                    Text(mode.description, style = MaterialTheme.typography.bodyMedium, color = MColors.Grey500)
                }
                SectionCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatTile(
                            "해 뜨는 방향",
                            "${ParkingAdvice.compass(advice.sunrise.azimuthDeg)} ${advice.sunrise.azimuthDeg.roundToInt()}°",
                            Modifier.weight(1f),
                            accent = MColors.Orange,
                        )
                        StatTile(
                            "권장 앞머리",
                            "${ParkingAdvice.compass(advice.targetHeadingDeg)} ${advice.targetHeadingDeg.roundToInt()}°",
                            Modifier.weight(1f),
                            accent = MColors.Blue,
                        )
                    }
                    val current = heading?.let { "차 앞머리 지금 ${ParkingAdvice.compass(it)} ${it.roundToInt()}°" }
                        ?: "아직 주차 직전의 차량 방향을 알 수 없어요"
                    Text(current, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MColors.Orange)
                    Callout(advice.instruction(), if (advice.aligned) MColors.Green else MColors.Orange)
                    Text(
                        "주차 직전 30m 이상 이동한 궤적으로 앞머리를 추정해요. 권장 방향과 15° 이내면 정렬된 것으로 봐요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MColors.Grey500,
                    )
                }
                SectionCard {
                    ListRow(
                        "⛰️",
                        "산이나 건물에 가리면 늦게 떠요",
                        "스팟의 지형과 주변 장애물에 따라 햇빛이 보이는 시각은 달라져요.",
                    )
                }
                SecondaryButton(
                    if (advice.headingFlipped) "차량 방향 보정 취소" else "차량 방향을 180° 반대로 보정",
                    grey = true,
                    enabled = heading != null,
                    onClick = trip::flipHeading,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(96.dp))
            }
        }
    }
}

@Composable
private fun Compass(advice: ParkingAdvice, modifier: Modifier = Modifier) {
    val sunAz = advice.sunrise.azimuthDeg.toFloat()
    val target = advice.targetHeadingDeg.toFloat()
    val heading = advice.currentHeadingDeg?.toFloat()

    Canvas(modifier.semantics {
        contentDescription = "진북 기준, 일출 ${sunAz.roundToInt()}도, 권장 앞머리 ${target.roundToInt()}도"
    }) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension * 0.41f
        if (r <= 0f) return@Canvas
        fun pt(deg: Float, radius: Float) = Offset(
            c.x + radius * sin(Math.toRadians(deg.toDouble())).toFloat(),
            c.y - radius * cos(Math.toRadians(deg.toDouble())).toFloat(),
        )

        drawCircle(MColors.Grey50, radius = r, center = c)
        for (i in 0 until 72) {
            val deg = i * 5f
            val len = when {
                i % 18 == 0 -> 22f
                i % 2 == 0 -> 12f
                else -> 6f
            }
            drawLine(
                if (i % 18 == 0) MColors.Grey500 else MColors.Grey200,
                pt(deg, r),
                pt(deg, r - len),
                strokeWidth = if (i % 18 == 0) 4f else 2f,
            )
        }
        val labels = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = (r * 0.09f).coerceAtMost(18.sp.toPx())
            isFakeBoldText = true
        }
        drawIntoCanvas { canvas ->
            listOf("북", "동", "남", "서").forEachIndexed { index, label ->
                labels.color = if (index == 0) MColors.Red.toArgb() else MColors.Grey500.toArgb()
                val point = pt(index * 90f, r * 0.82f)
                canvas.nativeCanvas.drawText(label, point.x, point.y - (labels.ascent() + labels.descent()) / 2, labels)
            }
        }

        // 세울 방향
        drawLine(
            MColors.Blue,
            c,
            pt(target, r * 0.73f),
            strokeWidth = 6f,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f)),
        )
        // 해
        drawCircle(MColors.Orange50, radius = r * 0.105f, center = pt(sunAz, r + 4f))
        drawCircle(MColors.Orange, radius = r * 0.057f, center = pt(sunAz, r + 4f))

        // 지금 차 (위쪽이 앞머리)
        if (heading == null) return@Canvas
        rotate(heading, pivot = c) {
            val w = r * 0.22f
            val h = r * 0.48f
            drawRoundRect(MColors.Text, topLeft = Offset(c.x - w / 2, c.y - h / 2), size = Size(w, h), cornerRadius = CornerRadius(w * 0.38f))
            drawRoundRect(
                MColors.Grey500,
                topLeft = Offset(c.x - w * 0.38f, c.y - h * 0.38f),
                size = Size(w * 0.76f, h * 0.2f),
                cornerRadius = CornerRadius(8f),
            )
            drawCircle(Color(0xFFFFE08A), radius = 6f, center = Offset(c.x - w * 0.3f, c.y - h / 2 + 6f))
            drawCircle(Color(0xFFFFE08A), radius = 6f, center = Offset(c.x + w * 0.3f, c.y - h / 2 + 6f))
        }

        // 돌려야 할 호
        val turn = advice.turnDeg
        if (turn != null && !advice.aligned) {
            val start = heading - 90f
            drawArc(
                MColors.Orange,
                startAngle = start,
                sweepAngle = turn.toFloat(),
                useCenter = false,
                topLeft = Offset(c.x - (r * 0.62f), c.y - (r * 0.62f)),
                size = Size((r * 0.62f) * 2, (r * 0.62f) * 2),
                style = Stroke(width = 8f, cap = StrokeCap.Round),
            )
        }
    }
}
