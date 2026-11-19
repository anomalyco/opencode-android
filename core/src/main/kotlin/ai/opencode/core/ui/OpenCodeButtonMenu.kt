package ai.opencode.core.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.DropdownMenuPopupPositionProvider
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.window.PopupProperties

/**
 * A Material menu that opens just above the button it's placed in (call it inside a box the
 * button's size), lined up with the button's leading edge. Its popup doesn't take focus, so the
 * keyboard stays up. [scroll] gets the room the menu has, to start scrolled somewhere.
 */
@Composable
fun OpenCodeButtonMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    scroll: @Composable (room: Dp) -> ScrollState = { rememberScrollState() },
    content: @Composable ColumnScope.() -> Unit,
) {
    var anchor by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    val top = WindowInsets.statusBars.getTop(density)
    val room = (with(density) { (anchor.top - top).toDp() } - OpenCodeSpacing.s6 * 2).coerceAtLeast(48.dp)
    val gap = with(density) { OpenCodeSpacing.s6.roundToPx() }
    val position = remember(gap, top, anchor) { ButtonMenuPosition(anchor.roundToIntRect(), gap, top) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { anchor = it.boundsInWindow() }) {
        DropdownMenuPopup(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            popupPositionProvider = position,
            properties = PopupProperties(focusable = false),
        ) {
            Surface(
                shape = MenuDefaults.shape,
                color = MenuDefaults.containerColor,
                tonalElevation = MenuDefaults.TonalElevation,
                shadowElevation = MenuDefaults.ShadowElevation,
            ) {
                Column(
                    modifier
                        .heightIn(max = room)
                        .padding(vertical = 8.dp)
                        .width(IntrinsicSize.Max)
                        .verticalScroll(scroll(room)),
                    content = content,
                )
            }
        }
    }
}

/** Puts the menu's bottom a small gap above the button, and keeps its top clear of the status bar. */
private class ButtonMenuPosition(private val anchor: IntRect, private val gap: Int, private val top: Int) : DropdownMenuPopupPositionProvider {
    override var transformOrigin = TransformOrigin(0f, 1f)
        private set

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        var x = anchor.left
        transformOrigin = TransformOrigin(0f, 1f)
        if (layoutDirection == LayoutDirection.Rtl) {
            x = anchor.right - popupContentSize.width
            transformOrigin = TransformOrigin(1f, 1f)
        }
        x = x.coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
        val y = maxOf(top + gap, anchor.top - gap - popupContentSize.height)
        return IntOffset(x, y)
    }
}
