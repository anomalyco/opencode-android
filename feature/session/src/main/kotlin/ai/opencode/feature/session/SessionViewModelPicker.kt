package ai.opencode.feature.session

import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticEffect
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.feature.composer.modelpicker.ModelDisplayNames
import ai.opencode.feature.composer.modelpicker.ModelPickerEvent
import ai.opencode.feature.composer.modelpicker.ModelPickerState
import ai.opencode.feature.composer.modelpicker.OnePickerSheetHost
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelRef
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

// The session's glue for:feature:composer's model pickers. The pickers read a `ModelPickerState`
// built from `SessionFeature.State` and send `ModelPickerEvent`s, which map to the `SessionFeature`
// actions of the same names. The composer's `OnePickerChip` opens One Picker in its sheet
// ([SessionModelPicker]).

internal fun SessionFeature.State.modelPickerState(
    favorites: Set<String> = favoriteModelIDs.value,
    recents: List<String> = recentModelIDs.value,
    variants: Map<String, String> = modelVariants.value,
    displayNames: Map<String, ModelDisplayNames> = modelDisplayNamesByServer.value,
): ModelPickerState = ModelPickerState(
    models = models,
    currentModel = currentModel,
    favoriteModelIDs = favorites,
    recentModelIDs = recents,
    modelVariants = variants,
    modelFilter = modelFilter,
    isLoadingModels = isLoadingModels,
    modelsLoadError = modelsLoadError,
    modelVisibility = modelVisibility,
    showsAllModels = showsAllModels,
    modelDisplayNames = displayNames[server.persistenceID] ?: ModelDisplayNames(),
)

/** The `SessionFeature` action a picker event stands for; null when none does. */
internal fun modelPickerAction(event: ModelPickerEvent): SessionFeature.Action? = when (event) {
    is ModelPickerEvent.ModelPicked -> SessionFeature.Action.ModelPicked(event.model)
    is ModelPickerEvent.ModelPickedWithLevel -> SessionFeature.Action.ModelPickedWithLevel(event.model, event.level, event.ticked)
    is ModelPickerEvent.ModelLevelPicked -> SessionFeature.Action.ModelLevelPicked(event.model, event.level)
    is ModelPickerEvent.ModelRowLevelChanged -> SessionFeature.Action.ModelRowLevelChanged(event.model, event.level)
    is ModelPickerEvent.VariantPicked -> SessionFeature.Action.VariantPicked(event.variant)
    is ModelPickerEvent.ToggleFavoriteModel -> SessionFeature.Action.ToggleFavoriteModel(event.model)
    is ModelPickerEvent.FilterChanged -> SessionFeature.Action.Binding(BindingAction.ModelFilter(event.text))
    // Never sent.
    ModelPickerEvent.ModelSearchSubmitted -> null
    ModelPickerEvent.ShowAllModelsToggled -> SessionFeature.Action.ShowAllModelsToggled
    ModelPickerEvent.ModelListOpened -> SessionFeature.Action.ModelListOpened
    ModelPickerEvent.ModelListClosed -> SessionFeature.Action.ModelListClosed
    ModelPickerEvent.Dismiss -> SessionFeature.Action.Binding(BindingAction.ActivePicker(null))
}

/** What the picker's state is built from, observed narrowly so a streaming reply doesn't redraw it. */
private data class PickerInputs(
    val models: List<OpenCodeModel>,
    val currentModel: OpenCodeModelRef?,
    val modelFilter: String,
    val isLoadingModels: Boolean,
    val modelsLoadError: String?,
    val showsAllModels: Boolean,
    val serverID: String,
)

/**
 * The picker's state from the session, recomputed when the session's picker inputs or the
 * shared favorites, recents, remembered levels or model visibility change.
 */
@Composable
internal fun rememberModelPickerState(store: StoreOf<SessionFeature.State, SessionFeature.Action>): ModelPickerState {
    val inputs by store.observe {
        PickerInputs(it.models, it.currentModel, it.modelFilter, it.isLoadingModels, it.modelsLoadError, it.showsAllModels, it.server.persistenceID)
    }
    val favorites by remember(store) { store.state.favoriteModelIDs.flow }.collectAsState(store.state.favoriteModelIDs.value)
    val recents by remember(store) { store.state.recentModelIDs.flow }.collectAsState(store.state.recentModelIDs.value)
    val variants by remember(store) { store.state.modelVariants.flow }.collectAsState(store.state.modelVariants.value)
    val visibility by remember(store) { store.state.modelVisibilityByServer.flow }.collectAsState(store.state.modelVisibilityByServer.value)
    val hideModels by remember(store) { store.state.hideModelsExperiment.flow }.collectAsState(store.state.hideModelsExperiment.value)
    val displayNames by remember(store) { store.state.modelDisplayNamesByServer.flow }.collectAsState(store.state.modelDisplayNamesByServer.value)
    return remember(inputs, favorites, recents, variants, visibility, hideModels, displayNames) {
        store.state.modelPickerState(favorites, recents, variants, displayNames)
    }
}

/**
 * The model picker, shown by `SessionView` while `activePicker == model`: `OnePickerSheetHost`.
 * Dismissing it sets `activePicker` to null; however it goes, its search is cleared
 * (`modelListClosed`).
 */
@Composable
fun SessionModelPicker(store: StoreOf<SessionFeature.State, SessionFeature.Action>) {
    val state = rememberModelPickerState(store)
    val onEvent: (ModelPickerEvent) -> Unit = { event -> modelPickerAction(event)?.let(store::send) }
    OnePickerSheetHost(
        state = state,
        onEvent = onEvent,
        onDismiss = { store.send(SessionFeature.Action.Binding(BindingAction.ActivePicker(null))) },
        onPickModel = { model, level -> store.send(SessionFeature.Action.ModelPickedWithLevel(model, level)) },
    )
}

/**
 * The model picker's ties to the composer. A plain object; [SessionModelPickerOverlays] creates it
 * and hands it to the composer through [SessionComposerHost.modelPanels].
 */
@Stable
internal class SessionModelPanels(
    private val store: StoreOf<SessionFeature.State, SessionFeature.Action>,
    private val host: SessionComposerHost,
) {
    var sceneActive = true

    /**
     * The model chip put the field's keyboard away for the picker sheet, so the sheet's dismissal
     * hands it back ([SessionSheets]).
     */
    var sheetRestoresFocus = false

    /**
     * The message field takes the keyboard back from the model list, unless something else took
     * the composer meanwhile (another screen, a panel or menu, a request, dictation, a call).
     */
    fun restoreFocusAfterModelList(screen: SessionFeature.State.TaskIdentity): Boolean {
        val state = store.state
        if (state.taskIdentity != screen || !sceneActive ||
            host.isAttachmentMenuOpen || host.floatingCallCovers || state.isAnsweringRequest ||
            state.dictationHold != null || state.isDictating || state.isPreparingDictation
        ) return false
        host.focusComposer()
        return true
    }

    /** The model chip opens the picker sheet in the keyboard's place. */
    fun openModelPicker() {
        if (host.isAttachmentMenuOpen) host.closeAttachmentMenu()
        sheetRestoresFocus = host.messageFieldFocused
        host.dismissComposerKeyboard()
        store.send(SessionFeature.Action.ModelButtonTapped)
    }

    /** A session switch, a call, a request, or dictation taking the composer closes the picker at once. */
    fun closeModelPanels() {
        sheetRestoresFocus = false
        if (store.state.activePicker == SessionFeature.State.ActivePicker.model) {
            store.send(SessionFeature.Action.Binding(BindingAction.ActivePicker(null)))
        }
    }
}

/** Creates the screen's [SessionModelPanels] and hands it to the composer. */
@Composable
internal fun SessionModelPickerOverlays(store: StoreOf<SessionFeature.State, SessionFeature.Action>) {
    val host = LocalSessionComposerHost.current ?: return
    val panels = remember(store) { SessionModelPanels(store, host) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val sceneActive = lifecycle.isAtLeast(Lifecycle.State.RESUMED)

    SideEffect {
        panels.sceneActive = sceneActive
        host.modelPanels = panels
    }
    DisposableEffect(panels) { onDispose { if (host.modelPanels === panels) host.modelPanels = null } }
    // The picker opening taps, like the attachment menu.
    val modelPickerOpen by store.observe { it.activePicker == SessionFeature.State.ActivePicker.model }
    HapticEffect(Haptic.tap, modelPickerOpen) { _, open -> open }
    // Another screen in place closes the picker.
    val taskIdentity by store.observe { it.taskIdentity }
    var seenIdentity by remember { mutableStateOf(taskIdentity) }
    LaunchedEffect(taskIdentity) {
        if (seenIdentity == taskIdentity) return@LaunchedEffect
        seenIdentity = taskIdentity
        panels.closeModelPanels()
    }
}
