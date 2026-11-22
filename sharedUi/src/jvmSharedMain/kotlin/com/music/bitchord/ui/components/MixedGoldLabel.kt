package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Mixed", in gold, over a mixed playlist's artwork — the one thing that tells
 * it apart from an ordinary playlist at a glance.
 *
 * On a dark scrim so it reads on any cover, light or dark.
 */
@Composable
fun MixedGoldLabel(modifier: Modifier = Modifier, fontSize: TextUnit = 12.sp) {
    val gold = Brush.linearGradient(
        listOf(Color(0xFFFFE9A8), Color(0xFFFFC93D), Color(0xFFD99A1E), Color(0xFFFFE08A)),
    )
    Box(
        modifier = modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        androidx.compose.material3.Text(
            text = "Mixed",
            style = TextStyle(
                brush = gold,
                fontSize = fontSize,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.6.sp,
            ),
        )
    }
}
