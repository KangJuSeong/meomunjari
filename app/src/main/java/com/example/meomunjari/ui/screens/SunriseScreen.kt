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
    // 아래 두 값이 바뀌면 다시 계산한다.
    val heading by trip.heading.collectAsStateWithLifecycle()
    val mode by trip.sunriseMode.collectAsStateWithLifecycle()
    val advice = trip.parkingAdvice()

    if (advice == null) {
        EmptyState("스팟에 도착하면 알려드릴게요", "도착 인증이 되면 내일 일출 방향을 계산해요.")
        return
    }
    val time = advice.sunrise.time?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "-"

    Column {
        ScreenHeader(
            title = "해 뜨는 쪽으로 세워볼까요?",
            subtitle = "내일 아침 $time 일출 · 평지 지평선 기준",
            onBack = onBack,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            SectionCard(modifier = Modifier.weight(1.05f)) {
                Compass(advice, Modifier.fillMaxWidth().aspectRatio(1f))
                Text(
                    "🌅 해 뜨는 방향   ━ 세울 방향   ■ 지금 차",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
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
                        StatTile("일출", time, Modifier.weight(1f))
                        StatTile(
                            "해 뜨는 방향",
                            "${ParkingAdvice.compass(advice.sunrise.azimuthDeg)} ${advice.sunrise.azimuthDeg.roundToInt()}°",
                            Modifier.weight(1f),
                            accent = MColors.Orange,
                        )
                    }
                    val current = heading?.let { "차 앞머리 지금 ${ParkingAdvice.compass(it)} ${it.roundToInt()}°" }
                        ?: "차 앞머리 방향을 아직 몰라요 (조금 움직이면 알 수 있어요)"
                    Text(current, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MColors.Orange)
                    Callout(advice.instruction(), if (advice.aligned) MColors.Green else MColors.Orange)
                    Text(
                        "15° 안으로 맞추면 '딱 맞게 세웠어요'로 바뀌어요 · 방향은 주차 직전 주행 궤적으로 계산해요",
                        style = MaterialTheme.typography.bodySmall,
                        color = MColors.Grey500,
                    )
                }
                SectionCard {
                    ListRow(
                        "⛰️",
                        "산이나 건물에 가리면 늦게 떠요",
                        "실제로 해가 보인 시각을 남기면 다음 사람에게 도움이 돼요",
                    )
                }
                SecondaryButton(
                    "후진으로 들어와 주차했어요",
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

    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2 - 40f
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
        // 북쪽 표시
        drawCircle(MColors.Red, radius = 7f, center = pt(0f, r - 40f))

        // 세울 방향
        drawLine(
            MColors.Blue,
            c,
            pt(target, r - 50f),
            strokeWidth = 6f,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f)),
        )
        // 해
        drawCircle(MColors.Orange50, radius = 34f, center = pt(sunAz, r + 4f))
        drawCircle(MColors.Orange, radius = 18f, center = pt(sunAz, r + 4f))

        // 지금 차 (위쪽이 앞머리)
        val carDeg = heading ?: target
        val carColor = if (heading == null) MColors.Grey400 else MColors.Text
        rotate(carDeg, pivot = c) {
            val w = r * 0.22f
            val h = r * 0.48f
            drawRoundRect(carColor, topLeft = Offset(c.x - w / 2, c.y - h / 2), size = Size(w, h), cornerRadius = CornerRadius(w * 0.38f))
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
        if (heading != null && turn != null && !advice.aligned) {
            val start = heading - 90f
            drawArc(
                MColors.Orange,
                startAngle = start,
                sweepAngle = turn.toFloat(),
                useCenter = false,
                topLeft = Offset(c.x - (r - 80f), c.y - (r - 80f)),
                size = Size((r - 80f) * 2, (r - 80f) * 2),
                style = Stroke(width = 8f, cap = StrokeCap.Round),
            )
        }
    }
}
