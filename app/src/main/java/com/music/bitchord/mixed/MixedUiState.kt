package com.music.bitchord.mixed

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the rest of the app needs to know about the mixed playlist screen. */
object MixedUiState {
    private val _editorOpen = MutableStateFlow(false)

    /** True while a transition is being edited, so the page chrome behind it can step out of the way. */
    val editorOpen: StateFlow<Boolean> = _editorOpen

    internal fun setEditorOpen(open: Boolean) {
        _editorOpen.value = open
    }
}
