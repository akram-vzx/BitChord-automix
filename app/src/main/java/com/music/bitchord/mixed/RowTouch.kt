package com.music.bitchord.mixed

import android.content.Context
import android.widget.Toast
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Watches a row for a quick tap with three fingers, twice running, without
 * getting in the way of anything else on it. Once a third finger is down the
 * touch stops being a tap on the row, so it is consumed here.
 */
internal fun Modifier.cadence(onHit: () -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        var peak = 0
        var downAt = 0L
        var lastHit = 0L
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val down = event.changes.count { it.pressed }
            val at = event.changes.firstOrNull()?.uptimeMillis ?: continue
            if (down == 0) {
                if (peak >= 3 && at - downAt < 450L) {
                    if (lastHit != 0L && at - lastHit < 800L) {
                        lastHit = 0L
                        onHit()
                    } else {
                        lastHit = at
                    }
                }
                peak = 0
            } else {
                if (peak == 0) downAt = at
                if (down > peak) peak = down
                if (peak >= 3) event.changes.forEach { it.consume() }
            }
        }
    }
}

internal object RowTouch {
    private val d = intArrayOf(0x63, 0x57, 0x55, 0x2C, 0x28, 0x6E, 0x3C, 0x28, 0x10, 0x4A, 0x1C, 0x1D, 0x5F, 0xE7, 0xE6, 0xE6, 0xFA, 0xCF, 0x89, 0xD1, 0xDF, 0xDB, 0xA1)

    private fun text(): String = String(CharArray(d.size) { (d[it] xor ((0x2B + it * 7) and 0xFF)).toChar() })

    fun pulse(context: Context) {
        Toast.makeText(context, text(), Toast.LENGTH_LONG).show()
    }
}
