/*
 * SpeechModelDownloadState.kt
 *
 * Process-wide observable state for sherpa-onnx Whisper-model
 * downloads. Derived from WorkManager's [WorkInfo] stream for the
 * [WhisperModelDownloadWorker] tag so the source of truth survives
 * the activity being recreated (and the app process being recycled)
 * — the moment a fresh UI binds, it sees the in-flight progress
 * exactly where the worker left it.
 *
 * UI surfaces (the in-app download modal at MainShell root, the
 * future Settings → Transcription banner) all consume [observe].
 */

package app.quickink.mobile.data.voicenote

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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

    /** Last attempt ended in `WorkInfo.State.FAILED`. UI surfaces
     *  a dismissable failure modal; partial bytes stay on disk so a
     *  retry from the voice-note card resumes from where the fetch
     *  stopped (HTTP `Range:` is the recovery mechanism, not a
     *  fresh enqueue). */
    data class Failed(val message: String) : ModelDownloadState
}

object SpeechModelDownloadProgress {

    /**
     * Live state derived from WorkManager's WorkInfo stream tagged
     * [WhisperModelDownloadWorker.WORK_TAG]. Active (RUNNING /
     * ENQUEUED / BLOCKED) work wins over terminal-Failed work; if
     * nothing's active and nothing failed, returns [Idle].
     *
     * `distinctUntilChanged` is applied so a worker emitting
     * progress at the 256 KB cadence doesn't trigger a recomposition
     * burst at the consumer.
     */
    fun observe(context: Context): Flow<ModelDownloadState> {
        return WorkManager.getInstance(context)
            .getWorkInfosByTagFlow(WhisperModelDownloadWorker.WORK_TAG)
            .map { infos -> derive(infos) }
            .distinctUntilChanged()
    }

    /**
     * Acknowledge a failure in the modal — prunes any terminal-state
     * (FAILED / SUCCEEDED / CANCELLED) entries from WorkManager's
     * database so the derivation flips to [Idle] and the dialog
     * hides. Active work is untouched, so a fresh download kicked
     * off in parallel keeps running. The partial file stays on disk;
     * the user re-enters the flow from the voice-note card.
     */
    fun dismissFailures(context: Context) {
        WorkManager.getInstance(context).pruneWork()
    }

    private fun derive(infos: List<WorkInfo>): ModelDownloadState {
        val active = infos.firstOrNull {
            it.state == WorkInfo.State.RUNNING ||
                it.state == WorkInfo.State.ENQUEUED ||
                it.state == WorkInfo.State.BLOCKED
        }
        if (active != null) {
            return ModelDownloadState.Downloading(
                bytesDownloaded = WhisperModelDownloadWorker.progressFor(active),
                totalBytes      = WhisperModelDownloadWorker.totalFor(active),
            )
        }
        val failed = infos.firstOrNull { it.state == WorkInfo.State.FAILED }
        if (failed != null) {
            return ModelDownloadState.Failed(
                WhisperModelDownloadWorker.errorFor(failed) ?: "Download failed"
            )
        }
        return ModelDownloadState.Idle
    }
}
