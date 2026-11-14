package ai.opencode.feature.composer

/** iOS `AutocompleteOrder`: where the best match sits. */
enum class AutocompleteOrder(val rawValue: String) { Top("top"), Bottom("bottom") }

/** iOS `AutocompleteExitMode`: how a leaving row goes. */
enum class AutocompleteExitMode(val rawValue: String) { Fade("fade"), Collapse("collapse") }

/** iOS `AutocompleteCommandAction`: whether choosing a `/` command fills it in or runs it. */
enum class AutocompleteCommandAction(val rawValue: String) { Fill("fill"), Run("run") }

/**
 * Port of iOS `ComposerAutocompleteTunables` (`composer.autocomplete.*`), baked to their defaults:
 * lengths in dp, times in seconds.
 */
object ComposerAutocompleteTunables {
    /** `composer.autocomplete.order` "Best match". */
    val order = AutocompleteOrder.Bottom

    /** `composer.autocomplete.blur` "Entrance blur (pt)" (0…16). */
    const val blur = 4.0
    /** `composer.autocomplete.scale` "Entrance scale" (0.8…1). */
    const val scale = 0.96

    /** `composer.autocomplete.rowBlur` "New row blur (pt)" (0…8). */
    const val rowBlur = 0.0
    /** `composer.autocomplete.rowScale` "Row scale" (0.6…1). */
    const val rowScale = 1.0
    /** `composer.autocomplete.exitMode` "Leaving row". */
    val exitMode = AutocompleteExitMode.Collapse
    /** `composer.autocomplete.coalesce` "Commit bursts every (s)" (0…0.3). */
    const val coalesce = 0.0

    /** `composer.autocomplete.keysInstant` "Arrow keys move it at once". */
    const val keysInstant = true

    /** `composer.autocomplete.flightArc` "Height leads" (1…3). */
    const val flightArc = 1.4
    /** `composer.autocomplete.commandAction` "A / command". */
    val commandAction = AutocompleteCommandAction.Fill
    /** `composer.autocomplete.runDelay` "Runs after landing (s)" (0…1). */
    const val runDelay = 0.35

    /** `composer.autocomplete.rowHeight` "Row height (pt)" (36…56). */
    const val rowHeight = 40.0
    /** `composer.autocomplete.iconSize` "Icon (pt)" (16…32). */
    const val iconSize = 22.0
    /** `composer.autocomplete.maxRows` "Rows before scrolling" (2…8). */
    const val maxRows = 5.5
    /** `composer.autocomplete.padding` "Highlight inset (pt)" (2…20). */
    const val padding = 12.0
    /** `composer.autocomplete.rowInset` "Icon inset in highlight (pt)" (2…24). */
    const val rowInset = 7.0
    /** `composer.autocomplete.edgeFade` "Scroll edge fade (pt)" (0…60). */
    const val edgeFade = 24.0

    /** `ComposerTunables.cornerRadius` (the desktop dock's 12; iOS 28), the surface the highlight sits concentric in. */
    const val composerCornerRadius = 12.0
}
