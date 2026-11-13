package com.example.meomunjari.domain

import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** 기상 시각은 차박지 현지 시각이며, 도착 후 처음 맞는 해당 시각까지 분 단위로 계산한다. */
object WakeSchedule {
    const val DEFAULT_MINUTES = 8 * 60

    fun stayMinutes(start: LocalTime, wake: LocalTime?): Int {
        if (wake == null) return DEFAULT_MINUTES
        val difference = ChronoUnit.MINUTES.between(start.truncatedTo(ChronoUnit.MINUTES), wake).toInt()
        return if (difference > 0) difference else difference + 24 * 60
    }
}
