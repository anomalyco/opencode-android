package ai.opencode.feature.transcript.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Dev mode: a canvas's presented frames per second. [frames] is counted on the main thread where a frame reaches the
 * screen; once a second it becomes the badge's text (hidden while nothing was presented).
 */
internal class AgentWidgetFpsMeter {
    var frames = 0
    /** The 2D canvas's last counted draw revision: a redraw of the same content isn't a frame. */
    var revision = -1
    var badge: TextLayoutResult? by mutableStateOf(null)
}

/** A meter in dev mode, else null (nothing counts, nothing shows). */
@Composable
internal fun rememberAgentWidgetFpsMeter(): AgentWidgetFpsMeter? {
    if (!remember { AgentWidgetCapabilities.isDevMode }) return null
    val meter = remember { AgentWidgetFpsMeter() }
    val measurer = rememberTextMeasurer(cacheSize = 0)
    LaunchedEffect(meter) {
        val style = TextStyle(color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        var since = System.nanoTime()
        var shown = 0
        while (true) {
            delay(1_000)
            val now = System.nanoTime()
            val fps = (meter.frames * 1e9 / (now - since)).roundToInt()
            meter.frames = 0
            since = now
            if (fps != shown) {
                shown = fps
                meter.badge = if (fps == 0) null else measurer.measure("$fps fps", style)
            }
        }
    }
    return meter
}

/** Draws the meter's badge in the bottom-start corner (programs' HUDs tend to sit at the top), over the content. */
internal fun Modifier.agentWidgetFpsBadge(meter: AgentWidgetFpsMeter?): Modifier = if (meter == null) this else drawWithContent {
    drawContent()
    val badge = meter.badge ?: return@drawWithContent
    val pad = 3.dp.toPx()
    val inset = 6.dp.toPx()
    val top = size.height - inset - badge.size.height - pad * 2
    drawRoundRect(
        Color(0x99000000),
        topLeft = Offset(inset, top),
        size = Size(badge.size.width + pad * 2, badge.size.height + pad * 2),
        cornerRadius = CornerRadius(pad, pad),
    )
    drawText(badge, topLeft = Offset(inset + pad, top + pad))
}
