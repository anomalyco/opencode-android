package ai.opencode.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The display layer: the bigger, roomier scale the Overview and the sidebar's summary surfaces
 * use.
 *
 * The transcript, composer and settings keep [OpenCodeSpacing], [OpenCodeRadius] and
 * [OpenCodeTextStyles]: a chat surface is denser than a dashboard, and the two never mix. Nothing
 * here changes an existing token — these are new values for new surfaces.
 */
object OpenCodeDisplay {
    /** A card's corners. */
    val cardRadius: Dp = 26.dp

    /** A panel's corners: a row group inside a card. */
    val panelRadius: Dp = 22.dp

    /** The Overview banner's corners. */
    val heroRadius: Dp = 30.dp

    /** An icon tile's corners. */
    val tileRadius: Dp = 18.dp

    /** A big control's corners (the Overview's buttons). */
    val controlRadius: Dp = 18.dp

    /** The screen's side gutter. */
    val gutter: Dp = 22.dp

    /** The spacing scale: 6, 10, 16, 22, 30, 40. */
    val xs: Dp = 6.dp
    val sm: Dp = 10.dp
    val md: Dp = 16.dp
    val lg: Dp = 22.dp
    val xl: Dp = 30.dp
    val xxl: Dp = 40.dp

    /** A card's own padding. */
    val cardPadding: Dp = 22.dp

    /** The gap between the Overview's sections. */
    val sectionGap: Dp = 16.dp

    /** The gap inside a card. */
    val stackGap: Dp = 12.dp

    /** A soft card's shadow. Long and low, so a card lifts off the canvas without a hard edge. */
    val shadowElevation: Dp = 6.dp

    /** The shadow's tint. */
    val shadowColor: Color = Color(0xFF0D1822)
    val shadowTint: Color = Color(0x260D1822)

    /** A big icon tile's side (the Overview's quick actions). */
    val tileSize: Dp = 50.dp

    /** The small square icon tile beside a list row. */
    val rowTileSize: Dp = 44.dp
}

/**
 * The display type scale: the headline sizes the desktop's own scale has no room for. Tight
 * tracking on the large sizes (as the desktop's `text-*` and the design's display type), wide
 * tracking on the uppercase eyebrows.
 */
object OpenCodeDisplayTextStyles {
    private fun style(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) = TextStyle(
        fontFamily = OpenCodeFonts.system,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = if (tracking == 0.0) TextUnit.Unspecified else tracking.sp,
    )

    /** The banner's headline: 35/40, tightly tracked. */
    val hero = style(35, 40, FontWeight.SemiBold, -1.4)

    /** A page's title: 29/34. */
    val pageTitle = style(29, 34, FontWeight.Bold, -1.0)

    /** A section's title: 17/22. */
    val sectionTitle = style(17, 22, FontWeight.Bold, -0.4)

    /** A card's title: 16/22. */
    val cardTitle = style(16, 22, FontWeight.SemiBold, -0.2)

    /** An uppercase eyebrow above a title: 11/14, widely tracked. */
    val eyebrow = style(11, 14, FontWeight.Bold, 1.4)

    /** A big number: 25/30. */
    val metricValue = style(25, 30, FontWeight.Bold, -0.4)

    /** A number's caption: 12/16. */
    val metricLabel = style(12, 16, FontWeight.Medium, 0.1)

    /** A list row's title: 15/21. */
    val rowTitle = style(15, 21, FontWeight.SemiBold, -0.1)

    /** A list row's detail line: 13/19. */
    val rowDetail = style(13, 19)

    /** A small uppercase caption inside a card (a metric's label on the banner): 12/16. */
    val bannerLabel = style(12, 16, FontWeight.Medium, 0.2)
}

/**
 * The Overview's banner ramps. They are dark in both appearances, so the banner reads the same
 * whether the app is light or dark.
 */
object OpenCodeBrandGradient {
    /** Deep ink into indigo: the Overview's banner. */
    val studio = listOf(Color(0xFF111C27), Color(0xFF202843), Color(0xFF413775))

    /** A quieter slate-to-ink ramp, for a smaller banner. */
    val ink = listOf(Color(0xFF243745), Color(0xFF101A24), Color(0xFF192632))

    /** The brand ramp: indigo into deep blue. */
    val brand = listOf(Color(0xFF5B5FEF), Color(0xFF3E3AB6), Color(0xFF191B43))

    /** A diagonal brush over [colors]. */
    fun brush(colors: List<Color> = studio): Brush = Brush.linearGradient(colors)
}

/** The display layer's card fill: the grouped card, so it sits a step off the canvas. */
val OpenCodeColors.displayCard: Color get() = groupedCard

/**
 * A display card: the surface, its shape and its shadow.
 *
 * Material's `Surface` draws the shadow, not `Modifier.shadow`: the latter paints its ambient
 * part over the layer's contents, which greys a white card.
 */
@Composable
fun OpenCodeDisplayCard(
    shape: Shape,
    color: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = shape,
        color = color,
        shadowElevation = OpenCodeDisplay.shadowElevation,
        tonalElevation = 0.dp,
        modifier = modifier,
        content = content,
    )
}

/** A banner painted with [OpenCodeBrandGradient.studio]. */
fun Modifier.openCodeDisplayBanner(
    colors: List<Color> = OpenCodeBrandGradient.studio,
    shape: Shape = RoundedCornerShape(OpenCodeDisplay.heroRadius),
): Modifier = clip(shape).background(Brush.linearGradient(colors))
