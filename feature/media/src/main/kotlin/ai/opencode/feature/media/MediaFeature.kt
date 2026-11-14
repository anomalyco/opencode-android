package ai.opencode.feature.media

import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.ifLet

/**
 * The media area's root reducer: hosts the full-screen transcript image viewer
 * ([TranscriptImageViewerFeature]) as a presentation, one at a time; a request while one is up is
 * ignored. [MediaView] provides [LocalMediaPresenter], through which thumbnails ask for it.
 */
class MediaFeature : Reducer<MediaFeature.State, MediaFeature.Action> {
    data class State(
        /** The transcript image viewer, while it's up. */
        val viewer: TranscriptImageViewerFeature.State? = null,
    )

    sealed interface Action {
        /** The view appeared (TCA `.task`). */
        data object Task : Action

        /** Open a thumbnail's images full screen. */
        data class PresentImage(val preview: TranscriptImagePreview) : Action

        /** The viewer's actions; [PresentationAction.Dismiss] once its flight home has landed. */
        data class Viewer(val action: PresentationAction<TranscriptImageViewerFeature.Action>) : Action
    }

    private object ViewerSlot

    private val core = Reducer<State, Action> { state, action ->
        when (action) {
            Action.Task -> Reduced(state)
            is Action.PresentImage ->
                if (state.viewer != null || action.preview.pages.isEmpty()) {
                    Reduced(state)
                } else {
                    Reduced(state.copy(viewer = TranscriptImageViewerFeature.State(action.preview)))
                }
            is Action.Viewer -> Reduced(state)
        }
    }

    private val body: Reducer<State, Action> = ifLet(
        id = ViewerSlot,
        parent = core,
        state = { it.viewer },
        setState = { state, viewer -> state.copy(viewer = viewer) },
        action = { (it as? Action.Viewer)?.action },
        embed = { Action.Viewer(it) },
        child = TranscriptImageViewerFeature(),
        childID = { it.preview.id },
    )

    override fun reduce(state: State, action: Action): Reduced<State, Action> = body.reduce(state, action)
}
