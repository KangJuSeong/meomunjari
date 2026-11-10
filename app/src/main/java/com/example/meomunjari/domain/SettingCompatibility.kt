package com.example.meomunjari.domain

import com.example.meomunjari.core.model.SettingPreset
import com.example.meomunjari.core.vehicle.ControlItem
import kotlin.math.roundToInt

enum class PresetSource(val label: String) {
    MINE("내 저장 세팅"),
    SEASONAL("계절 기본값"),
    MASTER("이 스팟 고수의 세팅"),
}

data class PresetOption(val source: PresetSource, val preset: SettingPreset)

data class PresetItemView(val item: ControlItem, val valueLabel: String, val supported: Boolean)

object SettingCompatibility {

    /** 세팅에 담긴 제어 항목과 표시 문구. */
    fun items(preset: SettingPreset): List<Pair<ControlItem, String>> = buildList {
        add(ControlItem.HVAC to if (preset.hvacOn) "${preset.targetTempC.roundOne()}°C" else "공조 끔")
        val seat = when {
            preset.seatMemorySlot != null && preset.backrestAngleDeg != null ->
                "메모리 ${preset.seatMemorySlot}번 · 등받이 ${preset.backrestAngleDeg}°"
            preset.seatMemorySlot != null -> "메모리 ${preset.seatMemorySlot}번"
            else -> null
        }
        seat?.let { add(ControlItem.SEAT to it) }
        add(ControlItem.WINDOW to if (preset.windowOpenPct > 0) "${preset.windowOpenPct}% 열기" else "닫기")
        preset.openDoorArea?.let { add(ControlItem.DOOR to "${doorLabel(it)} 열기") }
        add(ControlItem.LIGHT to if (preset.ambientOn) "앰비언트 ${preset.ambientBrightness}%" else "끄기")
    }

    fun evaluate(preset: SettingPreset, capabilities: Set<ControlItem>): List<PresetItemView> =
        items(preset).map { (item, label) -> PresetItemView(item, label, item in capabilities) }

    /** "21°C, 창문 2cm, 조명 OFF" 같은 한 줄 요약. */
    fun summary(preset: SettingPreset): String = items(preset).joinToString(", ") { (item, label) ->
        when (item) {
            ControlItem.HVAC -> label
            ControlItem.LIGHT -> "조명 $label"
            else -> "${item.label} $label"
        }
    }

    /** 여름은 냉방 24°C, 겨울은 난방 21°C, 간절기는 22°C + 환기. */
    fun seasonalDefault(month: Int, nickname: String = "머문자리"): SettingPreset {
        val season = ReportAggregator.season(month)
        return SettingPreset(
            id = "seasonal-${season.name.lowercase()}",
            ownerNickname = nickname,
            title = "${season.label} 기본값",
            targetTempC = when (season) {
                Season.SUMMER -> 24.0
                Season.WINTER -> 21.0
                else -> 22.0
            },
            hvacOn = true,
            windowOpenPct = when (season) {
                Season.SUMMER -> 10
                Season.WINTER -> 0
                else -> 5
            },
            ambientOn = true,
            ambientBrightness = 20,
        )
    }

    /** 그 스팟에서 평점이 가장 높은 다른 사용자의 공개 세팅. */
    fun master(presets: List<SettingPreset>, myNickname: String): SettingPreset? =
        presets
            .filter { it.ownerNickname != myNickname }
            .maxWithOrNull(compareBy<SettingPreset> { it.ownerRating }.thenBy { it.loadCount })

    fun doorLabel(area: String): String = when (area) {
        "ROW_1_LEFT" -> "운전석 도어"
        "ROW_1_RIGHT" -> "조수석 도어"
        "ROW_2_LEFT" -> "뒷좌석 좌측 도어"
        "ROW_2_RIGHT" -> "뒷좌석 우측 도어"
        else -> "도어"
    }

    private fun Double.roundOne(): String {
        val r = (this * 10).roundToInt() / 10.0
        return if (r % 1.0 == 0.0) r.toInt().toString() else r.toString()
    }
}
