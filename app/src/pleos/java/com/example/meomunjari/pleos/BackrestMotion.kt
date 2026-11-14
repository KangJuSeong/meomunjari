package com.example.meomunjari.pleos

import ai.pleos.playground.vehicle.constant.control.MoveControl
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

internal enum class BackrestOutcome(val reason: String) {
    REACHED("도달"),
    STALLED("차가 움직이지 않음"),
    TIMEOUT("시간 안에 목표에 닿지 못함"),
    UNSUPPORTED("이 좌석은 조절 미지원"),
    FAILED("위치를 읽거나 움직이지 못함"),
    STOP_UNCONFIRMED("정지 응답을 확인하지 못함"),
}

/** 위치 지정을 지원하지 않는 시트에 이동 명령을 보내고, 움직였을 가능성이 있으면 정지 응답까지 기다린다. */
internal class BackrestMotion(
    private val readPosition: suspend () -> Int?,
    private val sendCommand: suspend (MoveControl) -> Boolean,
    private val onStopUnconfirmed: () -> Unit,
    private val moveTimeoutMs: Long = 30_000L,
    private val stopTimeoutMs: Long = 3_000L,
) {
    suspend fun moveTo(target: Int): BackrestOutcome {
        val start = readPosition() ?: return BackrestOutcome.FAILED
        if (abs(target - start) <= TOLERANCE) return BackrestOutcome.REACHED

        // 첫 이동 콜백을 기다리다 취소된 경우에도 차에는 이미 명령이 도착했을 수 있다.
        var stopConfirmed: Boolean
        val movement = try {
            withTimeoutOrNull(moveTimeoutMs) { approach(start, target) } ?: BackrestOutcome.TIMEOUT
        } finally {
            stopConfirmed = withContext(NonCancellable) {
                withTimeoutOrNull(stopTimeoutMs) { sendCommand(MoveControl.STOP) } == true
            }
            if (!stopConfirmed) onStopUnconfirmed()
        }
        return if (stopConfirmed) movement else BackrestOutcome.STOP_UNCONFIRMED
    }

    private suspend fun approach(start: Int, target: Int): BackrestOutcome {
        var current = start
        var forwardIncreasesPosition = true
        var firstStep = true
        var reversedAfterStall = false
        var lastChange = System.nanoTime()
        while (abs(target - current) > TOLERANCE) {
            val forward = (target > current) == forwardIncreasesPosition
            if (!sendCommand(if (forward) MoveControl.FORWARD else MoveControl.BACKWARD)) {
                return BackrestOutcome.FAILED
            }
            delay(POLL_MS)
            val position = readPosition() ?: return BackrestOutcome.FAILED
            val now = System.nanoTime()
            if (position != current) lastChange = now
            if (now - lastChange >= STALL_NANOS) {
                if (reversedAfterStall) return BackrestOutcome.STALLED
                reversedAfterStall = true
                forwardIncreasesPosition = !forwardIncreasesPosition
                lastChange = now
            } else if (firstStep && abs(target - position) > abs(target - current)) {
                forwardIncreasesPosition = !forwardIncreasesPosition
            }
            firstStep = false
            current = position
        }
        return BackrestOutcome.REACHED
    }

    private companion object {
        const val POLL_MS = 300L
        const val TOLERANCE = 2
        const val STALL_NANOS = 2_000_000_000L
    }
}
