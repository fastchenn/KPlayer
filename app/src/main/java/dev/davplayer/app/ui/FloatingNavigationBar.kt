package dev.davplayer.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.davplayer.app.HomeTab

private val glassShape = RoundedCornerShape(32.dp)
private val tabShape = RoundedCornerShape(27.dp)

@Composable
internal fun FloatingNavigationBar(
    selectedTab: HomeTab,
    onSelect: (HomeTab) -> Unit,
    backdrop: GraphicsLayer?,
    backdropOrigin: () -> Offset,
) {
    var barOrigin by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(start = 18.dp, end = 18.dp, top = 5.dp, bottom = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.widthIn(max = 356.dp).fillMaxWidth()
                .onGloballyPositioned { barOrigin = it.positionInRoot() }
                .shadow(10.dp, glassShape)
                .clip(glassShape),
        ) {
            if (backdrop != null) Canvas(
                Modifier.matchParentSize().graphicsLayer {
                    val radius = 14.dp.toPx()
                    renderEffect = BlurEffect(radiusX = radius, radiusY = radius, edgeTreatment = TileMode.Clamp)
                },
            ) {
                val offset = backdropOrigin() - barOrigin
                translate(offset.x, offset.y) { drawLayer(backdrop) }
            }
            val opacity = if (backdrop == null) 1f else .83f
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(
                        Color(0xFF39524A).copy(alpha = opacity),
                        Color(0xFF1B2D27).copy(alpha = opacity),
                        Color(0xFF182822).copy(alpha = opacity),
                    )))
                    .border(1.dp, Brush.verticalGradient(listOf(
                        Color(0xFFBEDACD).copy(alpha = .52f),
                        Color(0xFF98B6AA).copy(alpha = .12f),
                        Color(0xFFB5D5C7).copy(alpha = .29f),
                    )), glassShape)
                    .padding(5.dp)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (tab in HomeTab.entries) {
                    val selected = tab == selectedTab
                    val label = when (tab) {
                        HomeTab.LIBRARY -> "媒体库"
                        HomeTab.RECENT -> "最近"
                        HomeTab.SETTINGS -> "设置"
                    }
                    val icon = when (tab) {
                        HomeTab.LIBRARY -> if (selected) Icons.Filled.VideoLibrary else Icons.Outlined.VideoLibrary
                        HomeTab.RECENT -> if (selected) Icons.Filled.History else Icons.Outlined.History
                        HomeTab.SETTINGS -> if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
                    }
                    val tint by animateColorAsState(if (selected) Color(0xFFDCF6EB) else Color(0xFFB9CAC3), label = "tab content")
                    val light by animateColorAsState(if (selected) MintColor.copy(alpha = .23f) else Color.Transparent, label = "tab highlight")
                    val size by animateDpAsState(if (selected) 25.dp else 23.dp, label = "tab icon size")
                    Column(
                        Modifier.weight(1f).heightIn(min = 54.dp)
                            .clip(tabShape)
                            .background(Brush.verticalGradient(listOf(light, light.copy(alpha = light.alpha * .26f))))
                            .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(tab) })
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(Modifier.size(25.dp), contentAlignment = Alignment.Center) {
                            Icon(icon, contentDescription = null, modifier = Modifier.size(size), tint = tint)
                        }
                        Text(label, style = MaterialTheme.typography.labelSmall, color = tint,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
    }
}
