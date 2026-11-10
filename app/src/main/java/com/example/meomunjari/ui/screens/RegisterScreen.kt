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
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.meomunjari.core.model.Amenities
import com.example.meomunjari.core.model.VehicleClass
import com.example.meomunjari.trip.RegisterResult
import com.example.meomunjari.ui.LocalAppContainer
import com.example.meomunjari.ui.components.ChipFlow
import com.example.meomunjari.ui.components.KeyValue
import com.example.meomunjari.ui.components.Pill
import com.example.meomunjari.ui.components.PrimaryButton
import com.example.meomunjari.ui.components.ScreenHeader
import com.example.meomunjari.ui.components.SectionCard
import com.example.meomunjari.ui.components.TagChip
import com.example.meomunjari.ui.components.Toggle
import com.example.meomunjari.ui.components.SecondaryButton
import com.example.meomunjari.ui.theme.MColors

private val GROUNDS = listOf("아스팔트", "자갈 평지", "마사토", "흙 평지", "비포장")
private val NOISES = listOf("조용함", "물소리", "파도 소리", "약간 소음")

/** S8 신규 스팟 등록: 현재 위치 + 편의 정보 + 태그 + 금지구역 대조. */
@Composable
fun RegisterScreen(onBack: () -> Unit, onRegistered: (String) -> Unit) {
    val c = LocalAppContainer.current
    val location by c.trip.location.collectAsStateWithLifecycle()
    val spots by c.repos.spots.spots.collectAsStateWithLifecycle()

    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var toilet by remember { mutableStateOf(false) }
    var store by remember { mutableStateOf(false) }
    var ground by remember { mutableStateOf(GROUNDS[1]) }
    var noise by remember { mutableStateOf(NOISES[0]) }
    var maxClass by remember { mutableStateOf(VehicleClass.LARGE) }
    val tags = remember { mutableStateListOf<String>() }
    var tagInput by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }

    val zone = c.trip.restrictedZoneAt(location)
    val canSubmit = name.isNotBlank() && location != null && zone == null

    Column {
        ScreenHeader(title = "새 스팟 등록", subtitle = "지금 차가 있는 곳을 등록해요", onBack = onBack)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionCard(title = "위치") {
                    KeyValue("좌표", location?.let { "%.5f, %.5f".format(it.lat, it.lng) } ?: "확인 중")
                    if (zone != null) {
                        Pill("등록 불가", MColors.Danger)
                        Text(zone, style = MaterialTheme.typography.bodyLarge, color = MColors.Danger)
                    } else {
                        Pill("금지 구역 아님", MColors.Good)
                    }
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("스팟 이름") }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge,
                )
                OutlinedTextField(
                    value = description, onValueChange = { description = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("어떤 곳인가요?") }, minLines = 3, textStyle = MaterialTheme.typography.bodyLarge,
                )
                SectionCard(title = "들어올 수 있는 가장 큰 차") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        VehicleClass.entries.forEachIndexed { i, vc ->
                            SegmentedButton(
                                selected = maxClass == vc,
                                onClick = { maxClass = vc },
                                shape = SegmentedButtonDefaults.itemShape(i, VehicleClass.entries.size),
                            ) { Text(vc.label, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionCard(title = "편의 정보") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("화장실 있음", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Toggle(checked = toilet, onCheckedChange = { toilet = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("편의점 1km 이내", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Toggle(checked = store, onCheckedChange = { store = it })
                    }
                    Text("바닥", style = MaterialTheme.typography.labelLarge)
                    ChipFlow { GROUNDS.forEach { g -> TagChip(g, ground == g, onClick = { ground = g }) } }
                    Text("소음", style = MaterialTheme.typography.labelLarge)
                    ChipFlow { NOISES.forEach { n -> TagChip(n, noise == n, onClick = { noise = n }) } }
                }
                SectionCard(title = "해시태그") {
                    ChipFlow {
                        (c.tags.popular(spots, 10) + tags).distinct().forEach { t ->
                            TagChip(t, t in tags, onClick = { if (t in tags) tags.remove(t) else tags.add(t) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = tagInput, onValueChange = { tagInput = it }, modifier = Modifier.weight(1f),
                            placeholder = { Text("#오션뷰 → #바다뷰로 묶여요") }, singleLine = true,
                        )
                        Spacer(Modifier.size(8.dp))
                        SecondaryButton("추가", onClick = {
                            val t = c.tags.normalize(tagInput)
                            if (t.length > 1 && t !in tags) tags.add(t)
                            tagInput = ""
                        })
                    }
                }
                PrimaryButton(
                    "등록하기",
                    icon = Icons.Filled.AddLocationAlt,
                    enabled = canSubmit,
                    onClick = {
                        val r = c.trip.registerSpot(
                            name = name,
                            description = description,
                            amenities = Amenities(
                                toilet = toilet,
                                convenienceStoreM = if (store) 800 else null,
                                ground = ground,
                                noise = noise,
                            ),
                            rawTags = tags.toList() + if (toilet) listOf("#화장실있음") else emptyList(),
                            maxVehicleClass = maxClass,
                        )
                        when (r) {
                            is RegisterResult.Registered -> onRegistered(r.spot.id)
                            is RegisterResult.Restricted -> result = "${r.zoneName}: ${r.reason}"
                            RegisterResult.Duplicate -> result = "이미 등록된 스팟 반경 안이에요. 기존 스팟에 리뷰와 태그를 남겨 주세요."
                            RegisterResult.NoLocation -> result = "현재 위치를 확인할 수 없어요"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(96.dp))
            }
        }
    }

    result?.let {
        AlertDialog(
            onDismissRequest = { result = null },
            text = { Text(it, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { TextButton(onClick = { result = null }) { Text("확인") } },
        )
    }
}
