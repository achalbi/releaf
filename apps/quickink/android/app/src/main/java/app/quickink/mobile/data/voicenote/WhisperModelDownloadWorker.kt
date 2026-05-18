/*
 * WhisperModelDownloadWorker.kt
 *
 * Foreground-service WorkManager job that pulls the sherpa-onnx
 * Whisper archive for a single [WhisperModel]. Hosting the fetch
 * in WorkManager (vs. an in-app coroutine) is what makes "download
 * survives the app being closed" actually true: the system keeps
 * the foreground service alive even after the user dismisses the
 * activity, so the bytes keep flowing until the archive is on disk.
 *
 * The notification surfaces the same bytes / total readout the
 * in-app modal does. Tapping the notification re-opens the app
 * (MainActivity, singleTask) so the user can land back on the
 * voice-note card whose Transcribe tap kicked the fetch.
 *
 * Resume: this worker delegates to [SpeechTranscriber.runModelDownload]
 * which uses the same `<modelDir>.partial` + HTTP `Range:` machinery
 * as the in-app suspend path. A worker that's killed mid-fetch
 * (OOM, user "Force stop") leaves bytes on disk; the next enqueue
 * picks them up.
 *
 * Retries: transient `IOException` returns `Result.retry()` so
 * WorkManager backs off and tries again on its constraint-met
 * schedule. Hard failures (corrupted archive, HTTP 4xx) return
 * `Result.failure(...)` and surface in the modal as a "Download
 * paused" notice.
 */

package app.quickink.mobile.data.voicenote

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WhisperModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = coroutineScope {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return@coroutineScope Result.failure(
            workDataOf(KEY_ERROR_MESSAGE to "Missing model id")
        )
        val model = WhisperModel.fromId(modelId)
        Log.d(TAG, "doWork start: model=${model.id} attempt=$runAttemptCount")

        // Promote to a foreground service before the first byte
        // arrives. `setForeground` may throw on devices that refuse
        // notifications (e.g. POST_NOTIFICATIONS denied on Android 13+);
        // we ignore the failure and continue as a regular background
        // worker — bytes still flow, just without the OS guarantee.
        runCatching {
            setForeground(makeForegroundInfo(model, bytesDownloaded = 0, totalBytes = 0))
        }.onFailure { Log.w(TAG, "setForeground rejected; running as bg worker", it) }

        // The download's `onProgress` callback fires from inside a
        // synchronous read loop, so it can't call `setProgress` or
        // `setForeground` directly (both are `suspend`). Bridge via
        // a StateFlow that the read loop writes into and a sibling
        // coroutine `sample`s from at ~5 Hz.
        //
        // `sample` (NOT `debounce`): a 256 KB chunk lands every
        // ~50 ms on a fast connection, so `debounce(200)` would
        // sit in its quiet-window check forever and never emit —
        // the visible bug being "Starting download…" sticks
        // because setProgress is never called. `sample(200)` emits
        // the most recent value at fixed intervals regardless of
        // upstream cadence.
        val progressBeats = MutableStateFlow(0L to 0L)
        val mirrorJob = launch {
            progressBeats
                .sample(200L)
                .collectLatest { (bytes, total) ->
                    setProgress(
                        workDataOf(
                            KEY_PROGRESS_BYTES to bytes,
                            KEY_PROGRESS_TOTAL to total,
                        )
                    )
                    runCatching { setForeground(makeForegroundInfo(model, bytes, total)) }
                }
        }

        try {
            withContext(Dispatchers.IO) {
                SpeechTranscriber.runModelDownload(
                    context = applicationContext,
                    model   = model,
                ) { absolute, total ->
                    progressBeats.value = absolute to total
                }
            }
            mirrorJob.cancel()
            Result.success()
        } catch (e: IOException) {
            mirrorJob.cancel()
            Log.w(TAG, "doWork transient failure: ${e.message}")
            // Cap retries so we don't drain the user's data on a
            // genuinely-broken URL; partial bytes stay on disk so
            // a manual retry from the card still resumes.
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR_MESSAGE to (e.message ?: "Download failed")))
        } catch (e: Exception) {
            mirrorJob.cancel()
            Log.e(TAG, "doWork hard failure", e)
            Result.failure(workDataOf(KEY_ERROR_MESSAGE to (e.message ?: "Download failed")))
        }
    }

    private fun makeForegroundInfo(
        model: WhisperModel,
        bytesDownloaded: Long,
        totalBytes: Long,
    ): ForegroundInfo {
        ensureNotificationChannel(applicationContext)

        val tapIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pendingTap = tapIntent?.let {
            PendingIntent.getActivity(
                applicationContext,
                /* requestCode = */ 0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val builder = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading ${model.displayName} transcription model")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pendingTap)

        if (totalBytes > 0) {
            val percent = ((bytesDownloaded * 100L) / totalBytes)
                .coerceIn(0L, 100L)
                .toInt()
            builder
                .setContentText("${bytesDownloaded.toMb()} MB / ${totalBytes.toMb()} MB")
                .setProgress(100, percent, /* indeterminate = */ false)
        } else {
            builder
                .setContentText("Starting download…")
                .setProgress(0, 0, /* indeterminate = */ true)
        }

        val notification = builder.build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ requires the foreground-service type to be
            // declared on both the manifest (done) and the runtime
            // ForegroundInfo. `dataSync` matches our "pulling bytes
            // for later use" workload.
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "WhisperDownloadWorker"

        const val WORK_TAG = "quickink.whisper-download"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_PROGRESS_BYTES = "bytes"
        const val KEY_PROGRESS_TOTAL = "total"
        const val KEY_ERROR_MESSAGE = "error"

        private const val NOTIFICATION_CHANNEL_ID = "quickink.whisper-download"
        private const val NOTIFICATION_CHANNEL_NAME = "Transcription model downloads"
        private const val NOTIFICATION_ID = 0xB0B0  // arbitrary but stable per channel

        private const val MAX_ATTEMPTS = 3

        /** Unique work name keyed by model id — keeps a second tap
         *  from spawning a duplicate fetch and lets the in-app caller
         *  observe a known handle. */
        fun uniqueWorkName(modelId: String): String = "quickink.whisper-download:$modelId"

        /**
         * Enqueue (or no-op if already running) the download for
         * [model]. Returns the WorkManager observable Flow of
         * `WorkInfo` for the unique-named work so the caller can
         * suspend until it succeeds or fails.
         */
        fun enqueue(context: Context, model: WhisperModel): Flow<List<WorkInfo>> {
            val request = OneTimeWorkRequestBuilder<WhisperModelDownloadWorker>()
                .setInputData(workDataOf(KEY_MODEL_ID to model.id))
                .addTag(WORK_TAG)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            val workManager = WorkManager.getInstance(context)
            workManager.enqueueUniqueWork(
                uniqueWorkName(model.id),
                // KEEP — if a fetch is already in flight, a fresh
                // tap should rejoin it rather than restart from
                // scratch. The partial file would survive a REPLACE
                // anyway, but KEEP avoids the extra HTTP probe.
                ExistingWorkPolicy.KEEP,
                request,
            )
            return workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName(model.id))
        }

        private fun ensureNotificationChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
                ?: return
            if (nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows progress while QuickInk downloads a Whisper " +
                    "transcription model in the background."
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        /** Has the user granted POST_NOTIFICATIONS at runtime? On
         *  Android < 13 this is implicit-true. */
        fun hasNotificationPermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
            return ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }

        private fun Long.toMb(): Long = (this + 524_288L) / 1_048_576L

        /** Pull progress / error / state out of a WorkInfo emitted
         *  by [enqueue]'s returned flow. Returns null for terminal
         *  states the caller doesn't care about (e.g. CANCELLED in
         *  contexts where it's the same as FAILED). */
        fun progressFor(info: WorkInfo): Long = info.progress.getLong(KEY_PROGRESS_BYTES, 0L)
        fun totalFor(info: WorkInfo): Long = info.progress.getLong(KEY_PROGRESS_TOTAL, 0L)
        fun errorFor(info: WorkInfo): String? =
            info.outputData.getString(KEY_ERROR_MESSAGE)
                ?: if (info.state == WorkInfo.State.FAILED) "Download failed" else null
    }
}
