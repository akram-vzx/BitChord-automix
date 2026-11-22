package com.music.bitchord.mixed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The key badge of the playlist rows: a Camelot code on a colour that moves round the wheel with it. */
@Composable
fun CamelotBadge(key: CamelotKey, modifier: Modifier = Modifier) {
    val color = Color.hsv(Camelot.hue(key), if (key.minor) 0.42f else 0.62f, 0.96f)
    Box(
        modifier = modifier
            .background(color, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text = key.label,
            color = Color(0xFF101010),
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

internal fun formatDuration(text: String?): String = text?.takeIf { it.isNotBlank() } ?: "--:--"

internal fun Double.asClock(): String {
    val total = this.coerceAtLeast(0.0).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}
