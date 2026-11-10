package com.example.meomunjari.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.meomunjari.ui.theme.MColors

/** 차량 화면 최소 터치 높이. */
val TouchTarget = 64.dp

/** 회색 배경 위 흰 카드. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MColors.Card,
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (title != null || trailing != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (title != null) {
                        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    }
                    trailing?.invoke(this)
                }
            }
            content()
        }
    }
}

/** 화면당 하나뿐인 파란 주 버튼. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = TouchTarget),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MColors.Blue,
            contentColor = Color.White,
            disabledContainerColor = MColors.Grey200,
            disabledContentColor = MColors.Grey500,
        ),
        elevation = null,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

/** 보조 버튼: 연한 파랑. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    grey: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (grey) MColors.Grey100 else MColors.Blue50,
            contentColor = if (grey) MColors.Grey700 else MColors.Blue,
            disabledContainerColor = MColors.Grey100,
            disabledContentColor = MColors.Grey400,
        ),
        elevation = null,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** 회색 알약 칩. 선택하면 연한 파랑, strong이면 검정(선택한 조건). */
@Composable
fun TagChip(tag: String, selected: Boolean, onClick: () -> Unit, count: Int? = null, strong: Boolean = false) {
    val bg = when {
        selected && strong -> MColors.Text
        selected -> MColors.Blue50
        else -> MColors.Grey100
    }
    val fg = when {
        selected && strong -> Color.White
        selected -> MColors.Blue
        else -> MColors.Grey700
    }
    Box(
        Modifier
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tag, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = fg)
            if (count != null) {
                Spacer(Modifier.width(6.dp))
                Text("$count", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (selected) fg else MColors.Grey500)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipFlow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
    }
}

/** 숫자를 크게 보여주는 칸. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    icon: ImageVector? = null,
    accent: Color = MColors.Text,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MColors.Grey50)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = MColors.Grey500, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MColors.Grey500)
        }
        Text(value, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp, color = accent, maxLines = 1)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MColors.Grey500)
    }
}

/** 작은 상태 배지: 색 글자 + 연한 같은 색 배경. */
@Composable
fun Pill(text: String, color: Color = MColors.Blue, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
fun RatingText(rating: Double, count: Int? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Star, null, tint = MColors.Star, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(4.dp))
        val r = Math.round(rating * 10) / 10.0
        Text(if (count != null) "$r ($count)" else "$r", fontSize = 19.sp, fontWeight = FontWeight.Bold)
    }
}

/** 이모지 아이콘이 들어간 둥근 사각형. */
@Composable
fun EmojiIcon(emoji: String, background: Color = MColors.Grey100, size: Dp = 52.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = (size.value * 0.5f).sp)
    }
}

/** 토스식 목록 한 줄: 아이콘 · 제목/설명 · 오른쪽 값. */
@Composable
fun ListRow(
    emoji: String,
    title: String,
    subtitle: String? = null,
    iconBackground: Color = MColors.Grey100,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EmojiIcon(emoji, iconBackground)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, fontSize = 16.sp, color = MColors.Grey500, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        } else if (onClick != null) {
            Text("›", fontSize = 28.sp, color = MColors.Grey400)
        }
    }
}

@Composable
fun ThinDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MColors.Grey100))
}

/** 토스식 토글. */
@Composable
fun Toggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = MColors.Blue,
            checkedBorderColor = MColors.Blue,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = MColors.Grey200,
            uncheckedBorderColor = MColors.Grey200,
        ),
    )
}

/** 사진이 없는 1단계용 플레이스홀더: 스팟 색으로 그린 풍경. */
@Composable
fun SpotPhoto(colors: List<String>, modifier: Modifier = Modifier, height: Dp = 200.dp) {
    val top = parseColor(colors.getOrNull(0), Color(0xFF2B3A4F))
    val bottom = parseColor(colors.getOrNull(1), Color(0xFF0B1018))
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(24.dp)),
    ) {
        drawRect(Brush.verticalGradient(listOf(top, bottom)))
        val stars = listOf(0.12f to 0.18f, 0.3f to 0.1f, 0.52f to 0.22f, 0.7f to 0.12f, 0.86f to 0.28f, 0.4f to 0.32f, 0.62f to 0.06f)
        stars.forEach { (x, y) -> drawCircle(Color.White.copy(alpha = 0.7f), radius = 2.2f, center = Offset(size.width * x, size.height * y)) }
        val ridge = Path().apply {
            moveTo(0f, size.height * 0.72f)
            lineTo(size.width * 0.22f, size.height * 0.52f)
            lineTo(size.width * 0.4f, size.height * 0.66f)
            lineTo(size.width * 0.62f, size.height * 0.46f)
            lineTo(size.width * 0.82f, size.height * 0.62f)
            lineTo(size.width, size.height * 0.54f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(ridge, Color.Black.copy(alpha = 0.35f))
        val carW = size.width * 0.16f
        val carH = size.height * 0.09f
        val cx = size.width * 0.58f
        val cy = size.height * 0.86f
        drawRoundRect(
            Color.Black.copy(alpha = 0.6f),
            topLeft = Offset(cx - carW / 2, cy - carH),
            size = Size(carW, carH),
            cornerRadius = CornerRadius(carH / 2, carH / 2),
        )
        drawCircle(Color(0xFFFFE08A), radius = carH * 0.18f, center = Offset(cx + carW * 0.3f, cy - carH * 0.55f))
    }
}

fun parseColor(hex: String?, fallback: Color): Color = runCatching {
    val clean = hex!!.removePrefix("#")
    Color(("FF$clean").toLong(16))
}.getOrDefault(fallback)

/** 선 그래프 + 아래 연한 채움. */
@Composable
fun Sparkline(values: List<Float>, modifier: Modifier = Modifier, color: Color = MColors.Blue) {
    Canvas(modifier.fillMaxWidth().height(110.dp)) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val max = values.max().let { if (it - min < 1f) min + 1f else it }
        val step = size.width / (values.size - 1)
        val line = Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = size.height - 6f - (v - min) / (max - min) * (size.height - 12f)
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0f))))
        drawPath(line, color, style = Stroke(width = 5f, cap = StrokeCap.Round))
    }
}

@Composable
fun ProgressBar(ratio: Float, color: Color = MColors.Blue, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { ratio.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(10.dp).clip(CircleShape),
        color = color,
        trackColor = MColors.Grey100,
    )
}

@Composable
fun ProgressRow(label: String, ratio: Float, trailing: String, modifier: Modifier = Modifier, color: Color = MColors.Blue) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(trailing, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = color)
        }
        ProgressBar(ratio, color)
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(body, style = MaterialTheme.typography.bodyLarge, color = MColors.Grey500)
            action?.invoke()
        }
    }
}

/** 큰 굵은 제목 + 회색 부제. */
@Composable
fun ScreenHeader(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, trailing: @Composable (RowScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalAlignment = Alignment.Top) {
        if (onBack != null) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로", tint = MColors.Grey700, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = MColors.Grey500)
            }
        }
        trailing?.invoke(this)
    }
}

@Composable
fun KeyValue(key: String, value: String, valueWeight: FontWeight = FontWeight.Bold, valueColor: Color = MColors.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(key, fontSize = 18.sp, color = MColors.Grey600, modifier = Modifier.weight(1f))
        Text(value, fontSize = 18.sp, fontWeight = valueWeight, color = valueColor)
    }
}

/** 회색 바탕 위 흰 선택칸 (정렬·차급 선택). */
@Composable
fun <T> SegmentedPicker(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MColors.Grey100)
            .padding(4.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) Color.White else Color.Transparent)
                    .clickable { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    fontSize = 15.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (on) MColors.Text else MColors.Grey500,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 강조 안내 박스 (초록 = 좋음, 주황 = 주의). */
@Composable
fun Callout(text: String, color: Color = MColors.Green, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(text, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = color)
    }
}
