package ai.opencode.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The Light / Dark / System control, as Settings › Appearance and the Overview's bar show it:
 * three segments in one tinted well, the chosen one filled with the theme's brand.
 *
 * The control only reports the choice; the reducer writes it (the shared key is the reducer's).
 */
@Composable
fun OpenCodeAppearanceControl(
    selected: AppearanceColorScheme,
    onSelect: (AppearanceColorScheme) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OpenCodeTheme.colors
    val well = RoundedCornerShape(OpenCodeDisplay.controlRadius)
    val segment = RoundedCornerShape(OpenCodeDisplay.controlRadius - 3.dp)
    Row(
        modifier
            .clip(well)
            .background(colors.groupedCard)
            .border(1.dp, colors.border, well)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (option in AppearanceColorScheme.entries) {
            val chosen = option == selected
            Box(
                Modifier
                    .clip(segment)
                    .background(if (chosen) colors.brandSoft else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(option) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option.title,
                    style = OpenCodeTextStyles.caption1.copy(
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (chosen) colors.brand else colors.secondaryText,
                )
            }
        }
    }
}
