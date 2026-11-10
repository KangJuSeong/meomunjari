package com.example.meomunjari.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 토스 느낌의 밝은 팔레트: 회색 배경 위 흰 카드, 파란 강조 하나, 상태색은 초록·주황·빨강.
 */
object MColors {
    val Background = Color(0xFFF2F4F6)
    val Card = Color(0xFFFFFFFF)
    val Grey50 = Color(0xFFF9FAFB)
    val Grey100 = Color(0xFFF2F4F6)
    val Grey200 = Color(0xFFE5E8EB)
    val Grey400 = Color(0xFFB0B8C1)
    val Grey500 = Color(0xFF8B95A1)
    val Grey600 = Color(0xFF6B7684)
    val Grey700 = Color(0xFF4E5968)
    val Text = Color(0xFF191F28)
    val TextDim = Grey500

    val Blue = Color(0xFF3182F6)
    val Blue50 = Color(0xFFE8F3FF)
    val Green = Color(0xFF03B26C)
    val Green50 = Color(0xFFE5F8EF)
    val Orange = Color(0xFFFE9800)
    val Orange50 = Color(0xFFFFF3E0)
    val Red = Color(0xFFF04452)
    val Red50 = Color(0xFFFFEEEE)
    val Purple = Color(0xFF5B5FE3)
    val Purple50 = Color(0xFFEEF0FF)
    val Star = Color(0xFFFFC342)

    // 기존 이름 호환 (화면 코드에서 의미로 쓰는 이름)
    val Amber = Blue
    val Teal = Blue
    val Good = Green
    val Warn = Orange
    val Danger = Red
    val Night = Purple
    val Line = Grey200
}

private val scheme = lightColorScheme(
    primary = MColors.Blue,
    onPrimary = Color.White,
    primaryContainer = MColors.Blue50,
    onPrimaryContainer = MColors.Blue,
    secondary = MColors.Blue,
    onSecondary = Color.White,
    secondaryContainer = MColors.Blue50,
    onSecondaryContainer = MColors.Blue,
    background = MColors.Background,
    onBackground = MColors.Text,
    surface = MColors.Card,
    onSurface = MColors.Text,
    surfaceVariant = MColors.Grey100,
    onSurfaceVariant = MColors.Grey500,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = MColors.Grey50,
    surfaceContainer = Color.White,
    surfaceContainerHigh = MColors.Grey50,
    surfaceContainerHighest = MColors.Grey100,
    outline = MColors.Grey200,
    outlineVariant = MColors.Grey100,
    error = MColors.Red,
)

/** 큰 굵은 제목 + 차량 화면에서 읽히는 본문 크기. */
private val typography = Typography(
    displaySmall = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 52.sp, letterSpacing = (-1.2).sp),
    headlineLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 46.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 40.sp, letterSpacing = (-0.8).sp),
    headlineSmall = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 36.sp, letterSpacing = (-0.8).sp),
    titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp, letterSpacing = (-0.5).sp),
    titleMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp, letterSpacing = (-0.3).sp),
    bodyLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Medium, lineHeight = 28.sp, letterSpacing = (-0.3).sp),
    bodyMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, lineHeight = 25.sp, letterSpacing = (-0.2).sp),
    bodySmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun MeomunjariTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
