package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.ui.openCodeFieldContainer
import ai.opencode.core.ui.OpenCodeTextFieldDefaults
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.composer.R
import ai.opencode.server.model.ModelProviderGroup
import ai.opencode.server.model.ModelSearch
import ai.opencode.server.model.OpenCodeModel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** One Picker's surface colour: the desktop's floating surface. */
object OnePickerSurfaceDefaults {
    val color: Color @Composable get() = OpenCodeSurfaces.color
}

internal val OnePickerFavoriteTint = Color(0.92f, 0.68f, 0.10f)

/**
 * One Picker in the model pickers' sheet: the search field, then the list (or its spinner or empty
 * state) under edge fades. [onDismiss] clears `activePicker` (after the sheet's slide-down), and
 * leaving clears the search (`modelListClosed`). A pick slides the sheet down, then closes it.
 */
@Composable
fun OnePickerSheetHost(
    state: ModelPickerState,
    onEvent: (ModelPickerEvent) -> Unit,
    onDismiss: () -> Unit,
    onPickModel: (OpenCodeModel, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    ModelPickerDetentsSheet(onClose = onDismiss, onClosed = { onEvent(ModelPickerEvent.ModelListClosed) }) {
        val hideThen = LocalModelSheetHideThen.current
        val close = { hideThen?.invoke(onDismiss) ?: onDismiss() }
        OnePickerPanelContent(
            state = state,
            onEvent = onEvent,
            onDismiss = close,
            onPickModel = { model, level ->
                onPickModel(model, level)
                close()
            },
            modifier = modifier,
        )
    }
}

/**
 * The last catalog the panel built and the inputs it came from, so a redraw that changes none of
 * them reuses it. A plain class, so filling it never recomposes anything. Read and filled on the
 * main thread only.
 */
class OnePickerCatalogCache {
    /**
     * What the catalog and its row snapshots are built from. Kotlin's immutable lists compare by
     * identity first, so the key holds the list itself and only a new catalogue is compared in
     * full.
     */
    data class Key(
        val models: List<OpenCodeModel>,
        val visibility: Any?,
        val showsHiddenModels: Boolean,
        val favorites: Set<String>,
        val recents: List<String>,
        val currentModelID: String?,
        val currentLevel: String?,
        val modelVariants: Map<String, String>,
        val filter: String,
        val recentLimit: Int,
        val showsAllModels: Boolean,
        val frozen: List<ModelPickerSection>?,
    ) {
        /**
         * The catalog's inputs as they are now, listing [recents] as the picker's recent rows and
         * [frozen] (closing) in place of the sections.
         */
        constructor(state: ModelPickerState, recents: List<String>, frozen: List<ModelPickerSection>?) : this(
            models = state.models,
            visibility = state.modelVisibility,
            showsHiddenModels = state.showsAllModels,
            favorites = state.favoriteModelIDs,
            recents = recents,
            currentModelID = state.currentModelInfo?.id,
            currentLevel = state.currentLevel,
            modelVariants = state.modelVariants,
            filter = state.modelFilter,
            recentLimit = OnePickerTunables.RECENTS,
            showsAllModels = OnePickerTunables.SHOWS_ALL_MODELS,
            frozen = frozen,
        )

        /** The same inputs, except for the frozen sections. */
        fun matchesIgnoringFrozen(other: Key): Boolean = other.copy(frozen = frozen) == this
    }

    class Catalog(val sections: List<ModelPickerSection>, val snapshots: List<OnePickerSectionSnapshot>)

    private var key: Key? = null
    private var value: Catalog? = null

    /** The sections last built, as the panel shows them: what a close freezes. */
    val shownSections: List<ModelPickerSection>? get() = value?.sections

    fun catalog(next: Key, build: () -> Catalog): Catalog {
        val cached = value
        val cachedKey = key
        if (cached != null && cachedKey != null) {
            if (cachedKey == next) return cached
            // Freezing the sections already on screen (a close) changes nothing the rows show:
            // the catalog and its snapshots stay as they are.
            val frozen = next.frozen
            if (frozen != null && cachedKey.matchesIgnoringFrozen(next) && frozen == cached.sections) {
                key = next
                return cached
            }
        }
        val built = build()
        key = next
        value = built
        return built
    }

    companion object {
        /** The catalog and its row snapshots for [key], from the picker's models. */
        fun build(key: Key, state: ModelPickerState): Catalog {
            val sections = key.frozen ?: OnePickerCatalog.sections(
                models = state.pickerModels,
                favorites = key.favorites,
                recents = key.recents,
                currentModelID = key.currentModelID,
                filter = key.filter,
                recentLimit = key.recentLimit,
                showsAllModels = key.showsAllModels,
            )
            return Catalog(sections, state.onePickerSnapshotSections(sections))
        }
    }
}

/** Each section's immutable row snapshots. */
internal fun ModelPickerState.onePickerSnapshotSections(sections: List<ModelPickerSection>): List<OnePickerSectionSnapshot> {
    val currentID = currentModelInfo?.id
    val favorites = favoriteModelIDs
    val query = modelFilter
    var highlightedCurrentYet = false
    return sections.map { section ->
        val mixesProviders = section.kind !is ModelPickerSection.Kind.Provider
        val prefix = when (section.kind) {
            ModelPickerSection.Kind.Favorites -> "model-favorite"
            ModelPickerSection.Kind.Recent -> "model-recent"
            else -> "model-row"
        }
        val duplicateShortNames = section.models.groupingBy { it.shortName }.eachCount().filterValues { it > 1 }.keys
        val rows = section.models.map { model ->
            val scale = ModelLevelScale(model.variants)
            val parsed = OnePickerCatalog.parsedSearchLevel(query, model)
            val shown = if (parsed.matched) parsed.level else rememberedLevel(model)
            val providerTitle = ModelProviderGroup.title(model.providerID)
            val needsSubtitle = mixesProviders || model.shortName in duplicateShortNames
            val isPrimaryCurrent = model.id == currentID && !highlightedCurrentYet
            if (isPrimaryCurrent) highlightedCurrentYet = true
            OnePickerRowItem(
                sectionID = section.id,
                idPrefix = prefix,
                model = model,
                shortName = model.shortName,
                providerTitle = providerTitle,
                providerSubtitle = if (needsSubtitle) (if (mixesProviders) providerTitle else model.modelID) else null,
                isCurrent = isPrimaryCurrent,
                isFavorite = model.id in favorites,
                inFavoritesSection = section.kind == ModelPickerSection.Kind.Favorites,
                isAdjustable = scale.isAdjustable,
                shownLevel = shown,
                levelLabel = ModelLevelScale.label(shown),
                hasParsedSearchLevel = parsed.matched,
                showsArc = OnePickerTunables.ROW_SHOWS_ARC,
                rowHeight = OnePickerTunables.ROW_HEIGHT,
                levelAlpha = OnePickerTunables.LEVEL_ALPHA,
            )
        }
        OnePickerSectionSnapshot(section.id, section.title, rows)
    }
}

@Composable
private fun OnePickerPanelContent(
    state: ModelPickerState,
    onEvent: (ModelPickerEvent) -> Unit,
    onDismiss: () -> Unit,
    onPickModel: (OpenCodeModel, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    var searchFieldFocused by remember { mutableStateOf(false) }
    var scrollEdges by remember { mutableStateOf(OnePickerScrollEdges()) }
    val catalogCache = remember { OnePickerCatalogCache() }
    val recentsAtOpen = remember { state.recentModelIDs }
    var frozenSections by remember { mutableStateOf<List<ModelPickerSection>?>(null) }

    // The catalog and its row snapshots, rebuilt only when one of their inputs changes.
    val key = OnePickerCatalogCache.Key(state, recentsAtOpen, frozenSections)
    val catalog = catalogCache.catalog(key) { OnePickerCatalogCache.build(key, state) }
    val isSearching = state.modelFilter.trim().isNotEmpty()
    val hiddenModelCount = state.hiddenModelCount
    val footer = if (hiddenModelCount > 0) OnePickerFooterSnapshot(hiddenModelCount, state.showsAllModels) else null
    val showsSpinner = state.isLoadingModels && state.models.isEmpty()
    val showsEmptyState = catalog.sections.isEmpty() && !state.isLoadingModels && hiddenModelCount == 0

    val pick = { model: OpenCodeModel, level: String? ->
        if (frozenSections == null) {
            frozenSections = catalog.sections
            onPickModel(model, level)
        }
    }
    val listActions = remember { OnePickerListActions() }
    listActions.onPickModel = { item -> pick(item.model, item.shownLevel) }
    listActions.onPickLevel = { model, level -> onEvent(ModelPickerEvent.ModelRowLevelChanged(model, level)) }
    listActions.onToggleFavorite = { onEvent(ModelPickerEvent.ToggleFavoriteModel(it)) }
    listActions.onToggleShowAllModels = { onEvent(ModelPickerEvent.ShowAllModelsToggled) }
    listActions.onEdgesChanged = { scrollEdges = it }

    Column(
        modifier
            .fillMaxSize()
            .semantics { dismiss { onDismiss(); true } }
            .testTag("model-list"),
    ) {
        OnePickerSearchBar(
            state = state,
            focusRequester = searchFocus,
            focused = searchFieldFocused,
            onFocusChanged = { searchFieldFocused = it },
            onEvent = onEvent,
            onHideKeyboard = { focusManager.clearFocus() },
            onSubmit = {
                val resolved = ModelSearch.resolvePick(state.pickerModels, state.modelFilter, state.favoriteModelIDs, recentsAtOpen)
                if (resolved != null) {
                    val level = if (resolved.hasExplicitLevel) resolved.variant else state.rememberedLevel(resolved.model)
                    pick(resolved.model, level)
                }
            },
        )
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds(),
        ) {
            val fade = min(OnePickerTunables.EDGE_FADE, maxHeight.value.toDouble() / 2)
            Box(Modifier.fillMaxSize().onePickerEdgeFade(top = scrollEdges.topOverflow, bottom = scrollEdges.bottomOverflow, fade = fade)) {
                if (showsSpinner) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp))
                } else {
                    OnePickerListView(
                        sections = catalog.snapshots,
                        openGeneration = 0,
                        isSearching = isSearching,
                        bottomFooterRow = footer,
                        actions = listActions,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (showsEmptyState) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            ModelListEmptyState(state, includesLoadError = true, onTryAgain = { onEvent(ModelPickerEvent.ModelListOpened) })
                        }
                    }
                }
            }
        }
    }
}

/** Fades rows into the top/bottom edges when they overflow there. */
private fun Modifier.onePickerEdgeFade(top: Boolean, bottom: Boolean, fade: Double): Modifier {
    if ((!top && !bottom) || fade <= 0) return this
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fadePx = fade.dp.toPx()
            if (top) {
                drawRect(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = fadePx),
                    size = size.copy(height = fadePx),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (bottom) {
                drawRect(
                    Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - fadePx, endY = size.height),
                    topLeft = Offset(0f, size.height - fadePx),
                    size = size.copy(height = fadePx),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
}

/** The search field above the list. */
@Composable
private fun OnePickerSearchBar(
    state: ModelPickerState,
    focusRequester: FocusRequester,
    focused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    onEvent: (ModelPickerEvent) -> Unit,
    onHideKeyboard: () -> Unit,
    onSubmit: () -> Unit,
) {
    val colors = OpenCodeTheme.colors
    val fieldColors = OpenCodeTextFieldDefaults.colors()
    // The sheet's host recomposes its content a frame after the store changes, so the field keeps its own text.
    var text by remember { mutableStateOf(state.modelFilter) }
    val onTextChange: (String) -> Unit = { newText ->
        text = newText
        onEvent(ModelPickerEvent.FilterChanged(newText))
    }
    val hasTrailing = text.isNotEmpty() || focused
    val searchModelsText = stringResource(R.string.composer_model_search_placeholder)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 6.dp)
            .height(44.dp)
            .openCodeFieldContainer(isFocused = focused)
            .padding(start = 10.dp, end = if (hasTrailing) 2.dp else 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenCodeIcon(SFSymbol.Magnifyingglass, null, size = OpenCodeTextFieldDefaults.iconSize, tint = fieldColors.icon)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) Text(searchModelsText, style = OpenCodeTextFieldDefaults.textStyle, color = fieldColors.placeholder, maxLines = 1)
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                singleLine = true,
                textStyle = OpenCodeTextFieldDefaults.textStyle.copy(color = colors.text),
                cursorBrush = SolidColor(fieldColors.cursor),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onAny = { onSubmit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { onFocusChanged(it.isFocused) }
                    .semantics { contentDescription = searchModelsText }
                    .testTag("model-search"),
            )
        }
        if (text.isNotEmpty()) {
            OnePickerSearchButton(SFSymbol.XmarkCircleFill, stringResource(R.string.composer_model_clear_search_description), "model-search-clear") {
                onTextChange("")
            }
        } else if (focused) {
            OnePickerSearchButton(SFSymbol.KeyboardChevronCompactDown, stringResource(R.string.composer_model_hide_keyboard_description), "model-search-hide-keyboard", onHideKeyboard)
        }
    }
}

@Composable
private fun OnePickerSearchButton(symbol: SFSymbol, label: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .openCodeTapTarget()
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        OpenCodeIcon(symbol, null, size = 20.dp, tint = OpenCodeTheme.colors.tertiaryText)
    }
}
