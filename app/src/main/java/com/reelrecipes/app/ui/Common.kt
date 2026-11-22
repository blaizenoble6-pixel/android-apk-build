package com.reelrecipes.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SectionHead(title: String, aside: String) {
    Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, fontFamily = Display, fontSize = 21.sp, modifier = Modifier.weight(1f))
        Text(aside, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** − 4 people + stepper, 1 to 16. */
@Composable
internal fun ServingsStepper(value: Int, onChange: (Int) -> Unit, label: String = "people") {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 4.dp),
    ) {
        IconButton(onClick = { onChange((value - 1).coerceAtLeast(1)) }, enabled = value > 1) { Text("−", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        Text("$value $label", fontFamily = Mono, fontWeight = FontWeight.SemiBold, modifier = Modifier.widthIn(min = 76.dp))
        IconButton(onClick = { onChange((value + 1).coerceAtMost(16)) }, enabled = value < 16) { Icon(Icons.Default.Add, "More") }
    }
}

@Composable
internal fun Pill(text: String, strong: Boolean = false) {
    Text(
        text, fontSize = 12.sp,
        color = if (strong) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.clip(CircleShape)
            .background(if (strong) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
internal fun Muted(text: String, size: Int = 13, modifier: Modifier = Modifier) {
    Text(text, fontSize = size.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}
