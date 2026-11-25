package dev.davplayer.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.davplayer.app.HomeTab

private val NavigationTabs = listOf(
    Triple(HomeTab.LIBRARY, Icons.Outlined.VideoLibrary, "媒体库"),
    Triple(HomeTab.RECENT, Icons.Outlined.History, "最近"),
    Triple(HomeTab.SETTINGS, Icons.Outlined.Tune, "设置"),
)

@Composable
internal fun FloatingNavigationBar(selectedTab: HomeTab, onSelect: (HomeTab) -> Unit) {
    val light = LocalLightTheme.current
    val glass = if (light) Brush.verticalGradient(
        0f to Color(0xF0FCFBF5), .48f to Color(0xD7F5F4EA), 1f to Color(0xEDDFE9DF),
    ) else Brush.verticalGradient(
        0f to Color(0xEC34493F), .48f to Color(0xE322312B), 1f to Color(0xEC1A2722),
    )
    val rim = if (light) Brush.linearGradient(
        0f to Color(0xFFFEFDF9), .56f to Color(0x94ABBEAF), 1f to Color(0xE1FAFCF6),
    ) else Brush.linearGradient(
        0f to Color(0xA1ABCDBE), .52f to Color(0x5C597C6D), 1f to Color(0x9C779A8A),
    )
    Box(
        Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.widthIn(max = 460.dp).fillMaxWidth()
                .shadow(8.dp, CircleShape, clip = false)
                .clip(CircleShape).background(glass).border(1.dp, rim, CircleShape)
                .selectableGroup().padding(5.dp),
        ) {
            NavigationTabs.forEach { (tab, icon, label) ->
                val selected = tab == selectedTab
                val tint by animateColorAsState(if (selected) MintColor else MutedColor, label = "tab text")
                val fill by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (light) .82f else .9f)
                    else Color.Transparent,
                    label = "tab highlight",
                )
                Column(
                    Modifier.weight(1f).heightIn(min = 53.dp).clip(CircleShape).background(fill)
                        .selectable(selected = selected, onClick = { onSelect(tab) }, role = Role.Tab)
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(21.dp), tint = tint)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        label, fontSize = 11.sp, lineHeight = 14.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = tint,
                    )
                }
            }
        }
    }
}
