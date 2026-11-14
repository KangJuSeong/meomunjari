package com.example.meomunjari.pleos

import ai.pleos.playground.vehicle.constant.control.MoveControl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class BackrestMotionTest {
    private class Seat {
        var position: Int? = 0
        val commands = mutableListOf<MoveControl>()
        var stopWarnings = 0
        var handle: suspend (MoveControl) -> Boolean = { true }

        fun motion(moveTimeoutMs: Long = 5_000, stopTimeoutMs: Long = 1_000) = BackrestMotion(
            readPosition = { position },
            sendCommand = { command -> commands += command; handle(command) },
            onStopUnconfirmed = { stopWarnings++ },
            moveTimeoutMs = moveTimeoutMs,
            stopTimeoutMs = stopTimeoutMs,
        )
    }

    @Test
    fun cancellationWhileFirstCommandIsPendingWaitsForStopAcknowledgement() = runBlocking {
        val seat = Seat()
        val moveSent = CompletableDeferred<Unit>()
        val stopSent = CompletableDeferred<Unit>()
        val acknowledge = CompletableDeferred<Boolean>()
        seat.handle = { command ->
            if (command == MoveControl.STOP) {
                stopSent.complete(Unit)
                acknowledge.await()
            } else {
                moveSent.complete(Unit)
                awaitCancellation()
            }
        }

        withTimeout(3_000) {
            val screen = launch { seat.motion().moveTo(50) }
            moveSent.await()
            screen.cancel()
            stopSent.await()
            assertFalse(screen.isCompleted)
            acknowledge.complete(true)
            screen.join()
            assertTrue(screen.isCancelled)
        }
        assertEquals(listOf(MoveControl.FORWARD, MoveControl.STOP), seat.commands)
        assertEquals(0, seat.stopWarnings)
    }

    @Test
    fun stalledMoveAcknowledgementTimesOutAndStops() = runBlocking {
        val seat = Seat()
        seat.handle = { if (it == MoveControl.STOP) true else awaitCancellation() }
        assertEquals(BackrestOutcome.TIMEOUT, seat.motion(moveTimeoutMs = 100).moveTo(50))
        assertEquals(listOf(MoveControl.FORWARD, MoveControl.STOP), seat.commands)
    }

    @Test
    fun positionReadFailureAfterMoveStillStops() = runBlocking {
        val seat = Seat()
        seat.handle = { seat.position = null; true }
        assertEquals(BackrestOutcome.FAILED, seat.motion().moveTo(50))
        assertEquals(listOf(MoveControl.FORWARD, MoveControl.STOP), seat.commands)
    }

    @Test
    fun reachingTargetWaitsForStopAndRequiresItsAcknowledgement() = runBlocking {
        for (ack in listOf(true, false)) {
            val seat = Seat()
            val stopSent = CompletableDeferred<Unit>()
            val acknowledge = CompletableDeferred<Boolean>()
            seat.handle = { command ->
                if (command == MoveControl.STOP) {
                    stopSent.complete(Unit)
                    acknowledge.await()
                } else {
                    seat.position = 50
                    true
                }
            }
            withTimeout(3_000) {
                val result = async { seat.motion().moveTo(50) }
                stopSent.await()
                assertFalse(result.isCompleted)
                acknowledge.complete(ack)
                assertEquals(if (ack) BackrestOutcome.REACHED else BackrestOutcome.STOP_UNCONFIRMED, result.await())
            }
            assertEquals(listOf(MoveControl.FORWARD, MoveControl.STOP), seat.commands)
            assertEquals(if (ack) 0 else 1, seat.stopWarnings)
        }
    }

    @Test
    fun unresponsiveStopFinishesWithinDeadline() = runBlocking {
        val seat = Seat()
        seat.handle = { if (it == MoveControl.STOP) awaitCancellation() else false }
        withTimeout(3_000) {
            assertEquals(BackrestOutcome.STOP_UNCONFIRMED, seat.motion(stopTimeoutMs = 100).moveTo(50))
        }
        assertEquals(listOf(MoveControl.FORWARD, MoveControl.STOP), seat.commands)
        assertEquals(1, seat.stopWarnings)
    }

    @Test
    fun noStopBeforeMovementIsNeeded() = runBlocking {
        val seat = Seat()
        seat.position = null
        assertEquals(BackrestOutcome.FAILED, seat.motion().moveTo(50))
        seat.position = 49
        assertEquals(BackrestOutcome.REACHED, seat.motion().moveTo(50))
        assertTrue(seat.commands.isEmpty())
    }
}
