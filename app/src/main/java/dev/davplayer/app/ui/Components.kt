package dev.davplayer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
fun SectionLabel(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, fontSize = 12.sp, color = MutedColor)
    }
}

@Composable
fun Tag(text: String, color: Color = MintColor) {
    Text(text, modifier = Modifier.background(color.copy(alpha = .10f), RoundedCornerShape(6.dp))
        .border(1.dp, color.copy(alpha = .18f), RoundedCornerShape(6.dp))
        .padding(horizontal = 8.dp, vertical = 4.dp), color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun EmptyState(icon: ImageVector, title: String, description: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(66.dp).background(PanelColor, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MintColor, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(description, color = MutedColor, style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(20.dp)); action() }
    }
}

fun formatSize(bytes: Long?): String {
    if (bytes == null || bytes < 0) return "大小未知"
    return when {
        bytes >= 1_073_741_824 -> String.format(Locale.ROOT, "%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> String.format(Locale.ROOT, "%.2f MB", bytes / 1_048_576.0)
        bytes >= 1_024 -> String.format(Locale.ROOT, "%.2f KB", bytes / 1_024.0)
        else -> "$bytes B"
    }
}

fun formatTime(milliseconds: Long?): String {
    if (milliseconds == null) return "--:--"
    val total = milliseconds.coerceAtLeast(0) / 1000
    return if (total >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
    else String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60)
}
