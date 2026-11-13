package com.example.meomunjari.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.meomunjari.domain.BadgeProgress
import com.example.meomunjari.domain.MemberStatus
import com.example.meomunjari.domain.SettingCompatibility
import com.example.meomunjari.ui.LocalAppContainer
import com.example.meomunjari.ui.components.ChipFlow
import com.example.meomunjari.ui.components.EmptyState
import com.example.meomunjari.ui.components.KeyValue
import com.example.meomunjari.ui.components.Pill
import com.example.meomunjari.ui.components.PrimaryButton
import com.example.meomunjari.ui.components.ProgressRow
import com.example.meomunjari.ui.components.ScreenHeader
import com.example.meomunjari.ui.components.SectionCard
import com.example.meomunjari.ui.components.TagChip
import com.example.meomunjari.ui.components.Toggle
import com.example.meomunjari.ui.components.SecondaryButton
import com.example.meomunjari.ui.theme.MColors

import kotlin.math.abs
import kotlin.math.roundToInt

private val QUICK_PHRASES = listOf("조용해요", "화장실 깨끗해요", "별이 잘 보여요", "바람이 강해요", "평지라 편해요", "밤에 쌀쌀해요")

/** S7 실측 리포트 확인 + 인증 리뷰 작성 + 뱃지 진행도. */
@Composable
fun ReviewScreen(onDone: () -> Unit) {
    val c = LocalAppContainer.current
    val finished by c.trip.finished.collectAsStateWithLifecycle()
    val user by c.repos.user.user.collectAsStateWithLifecycle()
    val spots by c.repos.spots.spots.collectAsStateWithLifecycle()

    val f = finished ?: run {
        EmptyState("작성할 리뷰가 없어요", "차박을 마치면 실측 리포트와 함께 리뷰를 남길 수 있어요.")
        return
    }

    var rating by remember { mutableStateOf(5) }
    var text by remember { mutableStateOf("") }
    val phrases = remember { mutableStateListOf<String>() }
    val tags = remember { mutableStateListOf<String>() }
    var tagInput by remember { mutableStateOf("") }
    var share by remember { mutableStateOf(true) }

    val spotNow = spots.firstOrNull { it.id == f.spot.id } ?: f.spot
    val tagCandidates = (spotNow.sortedTags.map { it.tag } + c.tags.popular(spots)).distinct().take(12)

    Column {
        ScreenHeader(title = "좋은 아침이에요 ☀️", subtitle = "${f.spot.name}에서 차박을 마쳤어요")
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionCard(title = "어젯밤 리포트") {
                    val r = f.report
                    if (r == null) {
                        Text("기록에 동의하지 않았거나 샘플이 부족해 리포트를 만들지 않았어요.", style = MaterialTheme.typography.bodyLarge)
                    } else {
                        KeyValue("방문 일자", r.visitDate?.let { "${it.monthValue}월 ${it.dayOfMonth}일" } ?: r.createdAt)
                        KeyValue(
                            "외부 최저·최고",
                            if (r.outsideMinC != null && r.outsideMaxC != null) "${r.outsideMinC}~${r.outsideMaxC}°C" else "측정 안 됨",
                        )
                        KeyValue("실내 온도", r.avgCabinTempC?.let { "$it°C" } ?: "-")
                        KeyValue("${r.powerType.energyLabel} 소모", "${r.energyUsedPct}%")
                        KeyValue("차박 시간", "${r.durationHours}시간")
                        KeyValue("외부 기기", r.devices.ifEmpty { listOf("없음") }.joinToString(", "))
                        Text(
                            "리뷰 옆에 자동으로 붙고, 같은 시기에 이곳을 찾는 사람들의 리포트 목록에 보여요." +
                                if (f.creditsEarned == 2) " 이 계절 첫 기록이라 기록왕 2배!" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MColors.Teal,
                        )
                    }
                }
                f.batteryComparison?.let { comparison ->
                    SectionCard(title = "예상과 실제 배터리") {
                        val forecast = comparison.predictedMorningPct?.roundToInt()
                        val actual = comparison.endPct?.roundToInt()
                        val minutes = comparison.stayMinutes
                        val target = comparison.plannedWakeTime?.let { "$it 기상" } ?: "8시간 뒤"
                        KeyValue("시작 때 예상한 ${target} 잔량", forecast?.let { "$it%" } ?: "예측 자료 부족")
                        KeyValue("실제 종료 잔량", actual?.let { "$it%" } ?: "차량 잔량 확인 불가")
                        KeyValue("실제 체류 시간", "${minutes / 60}시간 ${minutes % 60}분")
                        if (forecast != null && actual != null) {
                            val delta = actual - forecast
                            Text(when {
                                delta == 0 -> "종료 잔량이 예상과 같아요"
                                delta > 0 -> "종료 잔량이 예상보다 ${abs(delta)}%p 높아요"
                                else -> "종료 잔량이 예상보다 ${abs(delta)}%p 낮아요"
                            })
                            if (minutes != comparison.plannedMinutes.toLong()) Text("예정한 체류 시간과 실제 체류 시간이 달라요.")
                        }
                    }
                }
                BadgeChanges(f.before, f.after)
            }

            Column(Modifier.weight(1.1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (f.reviewSubmitted) {
                    SectionCard(title = "리뷰를 남겼어요") {
                        Text("방문 인증 리뷰로 표시돼요. 고마워요!", style = MaterialTheme.typography.bodyLarge)
                    }
                    PrimaryButton("내 뱃지 보러 가기", icon = Icons.Filled.EmojiEvents, onClick = onDone, modifier = Modifier.fillMaxWidth())
                } else {
                    SectionCard(title = "어떠셨나요?") {
                        Row {
                            (1..5).forEach { i ->
                                IconButton(onClick = { rating = i }, modifier = Modifier.size(64.dp)) {
                                    Icon(
                                        if (i <= rating) Icons.Filled.Star else Icons.Filled.StarBorder,
                                        contentDescription = "${i}점",
                                        tint = MColors.Star,
                                        modifier = Modifier.size(44.dp),
                                    )
                                }
                            }
                        }
                    }
                    SectionCard(title = "한마디 남기기") {
                        ChipFlow {
                            QUICK_PHRASES.forEach { p ->
                                TagChip(p, selected = p in phrases, onClick = { if (p in phrases) phrases.remove(p) else phrases.add(p) })
                            }
                        }
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("자세한 후기 (선택)") },
                            textStyle = MaterialTheme.typography.bodyLarge,
                            minLines = 2,
                        )
                    }
                    SectionCard(title = "태그 더하기") {
                        ChipFlow {
                            tagCandidates.forEach { t ->
                                TagChip(t, selected = t in tags, onClick = { if (t in tags) tags.remove(t) else tags.add(t) })
                            }
                            tags.filterNot { it in tagCandidates }.forEach { t ->
                                TagChip(t, selected = true, onClick = { tags.remove(t) })
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = tagInput,
                                onValueChange = { tagInput = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("#일출맛집") },
                                singleLine = true,
                            )
                            Spacer(Modifier.size(8.dp))
                            SecondaryButton("추가", onClick = {
                                val t = c.tags.normalize(tagInput)
                                if (t.length > 1 && t !in tags) tags.add(t)
                                tagInput = ""
                            })
                        }
                        val suggestions = c.tags.suggest(tagInput, spots)
                        if (suggestions.isNotEmpty()) {
                            Text("비슷한 태그: ${suggestions.joinToString(" ")}", style = MaterialTheme.typography.bodySmall, color = MColors.Grey500)
                        }
                    }
                    SectionCard(title = "내 세팅 공개") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(SettingCompatibility.summary(SettingCompatibility.forOthers(user.mySetting)), style = MaterialTheme.typography.bodyMedium)
                                Text("다른 사람이 불러오면 '멘토' 뱃지에 쌓여요", style = MaterialTheme.typography.bodySmall, color = MColors.Grey500)
                            }
                            Toggle(checked = share, onCheckedChange = { share = it })
                        }
                    }
                    PrimaryButton(
                        "리뷰 남기기",
                        onClick = {
                            val body = (phrases + text.trim()).filter { it.isNotBlank() }.joinToString(". ")
                            c.trip.submitReview(rating, body, tags.toList(), share)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(96.dp))
            }
        }
    }
}

@Composable
private fun BadgeChanges(before: MemberStatus, after: MemberStatus) {
    SectionCard(title = "이번 차박으로", trailing = { Pill("${after.grade.label} · ${after.points}P", MColors.Blue) }) {
        val changed = after.badges.filter { a ->
            val previous = before.badges.first { it.type == a.type }
            previous.ratio != a.ratio
        }
        if (after.points != before.points) {
            Text("+${after.points - before.points}P", style = MaterialTheme.typography.headlineSmall, color = MColors.Blue)
        }
        if (after.grade != before.grade) {
            Text("등급이 올랐어요: ${before.grade.label} → ${after.grade.label}", style = MaterialTheme.typography.titleMedium, color = MColors.Good)
        }
        (changed.ifEmpty { after.badges.filter { !it.earned }.take(2) }).forEach { badge ->
            val old = before.badges.first { it.type == badge.type }
            BadgeLine(badge, old)
        }
    }
}

@Composable
fun BadgeLine(badge: BadgeProgress, old: BadgeProgress? = null) {
    val current = badge.current.coerceAtMost(badge.type.target)
    val increase = old?.let { current - it.current.coerceAtMost(badge.type.target) } ?: 0
    val delta = if (increase > 0) " (+$increase)" else ""
    val newlyEarned = old != null && !old.earned && badge.earned
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ProgressRow(
            label = badge.type.title + if (newlyEarned) " 획득!" else "",
            ratio = badge.ratio,
            trailing = "${current}/${badge.type.target}$delta",
            color = if (badge.earned) MColors.Good else MColors.Amber,
        )
        Text(
            listOf(badge.type.condition, badge.detail).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MColors.Grey500,
        )
    }
}
