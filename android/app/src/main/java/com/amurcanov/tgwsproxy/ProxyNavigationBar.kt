package com.amurcanov.tgwsproxy

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.amurcanov.tgwsproxy.ui.Brand
import com.amurcanov.tgwsproxy.ui.BrandIcon
import kotlin.math.abs

internal data class NavItem(val label: String, @param:DrawableRes val icon: Int)

@Composable
internal fun ProxyNavigationBar(
    navItems: List<NavItem>,
    selectedTab: Int,
    dragTargetIndex: Int,
    dragProgress: Float,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedColor = Brand.accent
    val unselectedColor = Brand.muted
    val indicatorIndex = remember { Animatable(selectedTab.toFloat()) }
    val visualIndex = indicatorIndex.value

    LaunchedEffect(selectedTab) {
        if (dragTargetIndex !in navItems.indices) {
            indicatorIndex.animateTo(selectedTab.toFloat(), tween(durationMillis = 220))
        }
    }

    LaunchedEffect(selectedTab, dragTargetIndex, dragProgress) {
        if (dragTargetIndex in navItems.indices) {
            indicatorIndex.snapTo(selectedTab.toFloat() + (dragTargetIndex - selectedTab) * dragProgress)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(Brand.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
    ) {
        val itemWidth = maxWidth / navItems.size
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Brand.line)
        )
        Box(
            modifier = Modifier
                .offset(x = itemWidth * visualIndex)
                .width(itemWidth)
                .height(2.dp)
                .background(selectedColor)
        )
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 64.dp)) {
            navItems.forEachIndexed { index, item ->
                val emphasis = (1f - abs(index - visualIndex)).coerceIn(0f, 1f)
                val tint = lerp(unselectedColor, selectedColor, emphasis)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = index == selectedTab,
                            role = androidx.compose.ui.semantics.Role.Tab,
                            onClick = { onTabSelected(index) }
                        ),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    BrandIcon(item.icon, tint = tint, size = 26.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}
