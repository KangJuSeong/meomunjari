package com.example.meomunjari.domain

import com.example.meomunjari.core.model.SettingPreset
import com.example.meomunjari.core.vehicle.ControlItem
import kotlin.math.roundToInt

enum class PresetSource(val label: String) {
    MINE("내 저장 세팅"),
    MASTER("이 스팟 고수의 세팅"),
}

data class PresetOption(val source: PresetSource, val preset: SettingPreset)

data class PresetItemView(val item: ControlItem, val valueLabel: String, val supported: Boolean)

/** 따로 여닫을 수 있는 창문. area는 Vehicle SDK WindowValue 이름. */
enum class WindowPosition(val label: String, val area: String) {
    FRONT_LEFT("운전석", "ROW_1_LEFT"),
    FRONT_RIGHT("조수석", "ROW_1_RIGHT"),
    REAR_LEFT("뒷좌석 좌측", "ROW_2_LEFT"),
    REAR_RIGHT("뒷좌석 우측", "ROW_2_RIGHT"),
    ;

    companion object {
        /** 창문 여는 단계: 0 = 닫힘, 10 = 활짝. Pleos 차량 범위(0~10)와 같다. */
        const val MAX_LEVEL = 10
    }
}

/** 등받이를 따로 정할 수 있는 좌석. area는 Vehicle SDK SeatValue 이름. */
enum class SeatPosition(val label: String, val area: String) {
    DRIVER("운전석", "ROW_1_LEFT"),
    PASSENGER("조수석", "ROW_1_RIGHT"),
    REAR_LEFT("뒷좌석 좌측", "ROW_2_LEFT"),
    REAR_RIGHT("뒷좌석 우측", "ROW_2_RIGHT"),
    ;

    companion object {
        /** 등받이 눕힘 정도 선택 범위(%)와 간격. 0 = 가장 세움, 100 = 가장 눕힘. */
        const val MIN_PCT = 0
        const val MAX_PCT = 100
        const val STEP_PCT = 5
        const val DEFAULT_PCT = 50
    }
}

object SettingCompatibility {

    /** 세팅에 담긴 제어 항목과 표시 문구. */
    fun items(preset: SettingPreset): List<Pair<ControlItem, String>> = buildList {
        add(ControlItem.HVAC to if (preset.hvacOn) "${preset.targetTempC.roundOne()}°C" else "공조 끔")
        val seatParts = listOfNotNull(
            preset.seatMemorySlot?.let { "메모리 ${it}번" },
            *SeatPosition.entries.mapNotNull { pos ->
                preset.backrestPctBySeat[pos.area]?.let { "${pos.label} 등받이 ${it}%" }
            }.toTypedArray(),
        )
        if (seatParts.isNotEmpty()) add(ControlItem.SEAT to seatParts.joinToString(" · "))
        add(ControlItem.WINDOW to windowLabel(preset))
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

    /** 그 스팟에서 평점이 가장 높은 다른 사용자의 공개 세팅. */
    fun master(presets: List<SettingPreset>, myNickname: String): SettingPreset? =
        presets
            .filter { it.ownerNickname != myNickname }
            .maxWithOrNull(compareBy<SettingPreset> { it.ownerRating }.thenBy { it.loadCount })
            ?.let(::forOthers)

    /**
     * 다른 사람에게 보여주고 적용할 세팅. 시트 메모리 번호는 그 사람 차에 저장된 위치라
     * 내 차에서는 뜻이 없으므로 빼고, 좌석별 등받이 각도만 남긴다.
     */
    fun forOthers(preset: SettingPreset): SettingPreset = preset.copy(seatMemorySlot = null)

    /** "운전석 3단 · 조수석 1단 · 나머지 닫기" / "모두 닫기" */
    fun windowLabel(preset: SettingPreset): String {
        val open = WindowPosition.entries.mapNotNull { w ->
            preset.windowLevelByArea[w.area]?.takeIf { it > 0 }?.let { "${w.label} ${it}단" }
        }
        return when {
            open.isEmpty() -> "모두 닫기"
            open.size == WindowPosition.entries.size -> open.joinToString(" · ")
            else -> (open + "나머지 닫기").joinToString(" · ")
        }
    }

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
