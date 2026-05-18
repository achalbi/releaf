/*
 * SpeechModelDownloadState.kt
 *
 * Process-wide download progress for the sherpa-onnx Whisper model.
 * `SpeechTranscriber.downloadAndExtractSherpaModel` updates this
 * state holder while bytes flow in; UI surfaces (Settings, the
 * post-record sheet, the root shell) observe via [state] to render
 * progress.
 *
 * Singleton because the download is fire-and-forget from a coroutine
 * launched off the record sheet — the user can navigate away mid-
 * download and we still want a global modal/banner to surface
 * progress wherever they land.
 */

package app.quickink.mobile.data.voicenote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ModelDownloadState {
    /** No download in flight. UI surfaces hide. */
    object Idle : ModelDownloadState

    /**
     * In progress. [totalBytes] is `0` until the HTTP response
     * headers land (Content-Length); UI should render an
     * indeterminate progress bar while it's still zero, switching
     * to a determinate bar once it becomes positive.
     */
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) : ModelDownloadState

    /** Last attempt failed; UI may surface this until the user retries (or dismisses). */
    data class Failed(val message: String) : ModelDownloadState
}

object SpeechModelDownloadProgress {

    private val _state = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val state: StateFlow<ModelDownloadState> = _state.asStateFlow()

    /** Set the current state. Called from [SpeechTranscriber] only. */
    internal fun update(value: ModelDownloadState) {
        _state.value = value
    }
}
