package dev.davplayer.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlin.math.abs

private sealed interface PlayerGesture {
    data object Tap : PlayerGesture
    data object Hold : PlayerGesture
    data object Cancel : PlayerGesture
    data class Seek(val change: PointerInputChange) : PlayerGesture
}

/** Lock each touch sequence to a tap, hold or horizontal seek; never switch mid-gesture. */
internal suspend fun PointerInputScope.detectPlayerGestures(
    canBoost: Boolean,
    canSeek: Boolean,
    onTap: () -> Unit,
    onHold: () -> Unit,
    onRelease: () -> Unit,
    onSeekStart: (Float) -> Unit,
    onSeekDrag: (Float) -> Unit,
    onSeekFinish: () -> Unit,
    onSeekCancel: () -> Unit,
) {
    try {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            try {
                val intent = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var result: PlayerGesture? = null
                    while (result == null) {
                        val event = awaitPointerEvent()
                        val change = event.changes.singleOrNull()?.takeIf { it.id == down.id }
                        result = when {
                            change == null || change.isConsumed -> PlayerGesture.Cancel
                            abs(change.position.x - down.position.x) > viewConfiguration.touchSlop ||
                                abs(change.position.y - down.position.y) > viewConfiguration.touchSlop -> {
                                val delta = change.position - down.position
                                if (canSeek && abs(delta.x) > abs(delta.y)) PlayerGesture.Seek(change)
                                else PlayerGesture.Cancel
                            }
                            !change.pressed -> { change.consume(); PlayerGesture.Tap }
                            else -> null
                        }
                    }
                    result
                } ?: PlayerGesture.Hold

                when (intent) {
                    PlayerGesture.Tap -> onTap()
                    PlayerGesture.Hold -> {
                        if (canBoost) onHold()
                        // Movement after a long press stays a hold, even past the seek threshold.
                        while (true) {
                            val change = awaitPointerEvent().changes.singleOrNull()?.takeIf { it.id == down.id }
                            if (change == null || change.isConsumed) break
                            change.consume()
                            if (!change.pressed) break
                        }
                    }
                    is PlayerGesture.Seek -> {
                        onSeekStart(size.width.toFloat())
                        var change = intent.change
                        while (true) {
                            change.consume()
                            onSeekDrag(change.position.x - down.position.x)
                            if (!change.pressed) { onSeekFinish(); break }
                            change = awaitPointerEvent().changes.singleOrNull()
                                ?.takeIf { it.id == down.id && !it.isConsumed } ?: break
                        }
                    }
                    PlayerGesture.Cancel -> Unit
                }
            } finally {
                onRelease()
                onSeekCancel()
            }
        }
    } finally {
        onRelease()
        onSeekCancel()
    }
}
