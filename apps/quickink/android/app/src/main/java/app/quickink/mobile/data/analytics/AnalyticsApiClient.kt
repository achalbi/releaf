/*
 * AnalyticsApiClient.kt
 *
 * Thin OkHttp wrapper that POSTs outbox events to the QuickInk
 * analytics backend. Two endpoints:
 *
 *   POST /v1/identify              — once on cold-start / sign-in
 *   POST /v1/events/capture/batch  — N events per call, ≤ 200
 *
 * Auth: every request carries a Google ID token in Authorization.
 * The token comes from `AuthStore.idToken()` (which under the hood
 * is `RealGoogleAuthClient.idToken()` doing the silent-refresh
 * dance via Credential Manager). On 401 we force-refresh the
 * token once and retry the same request.
 *
 * Failure semantics — per the consolidated v1 spec:
 *   2xx                    → return Success(acceptedIds)
 *   401 (after one retry)  → caller should leave rows queued + log
 *   429                    → caller honours Retry-After
 *   other 4xx              → caller drops rows (won't succeed on retry)
 *   5xx / network          → caller schedules backoff
 *
 * Mirror of iOS `AnalyticsApiClient.swift`.
 */

package app.quickink.mobile.data.analytics

import android.util.Log
import app.releaf.mobile.auth.AuthStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class AnalyticsApiClient(
    private val authStore: AuthStore,
    private val baseUrl:   String,
) {

    private val http = OkHttpClient.Builder()
        // Cold-start tolerant. Cloud Run with min-instances=0
        // takes ~10–25s for the first hit after an idle period;
        // every subsequent request lands in <200ms. 60s read
        // timeout means the outbox doesn't have to wait a full
        // backoff cycle just to absorb the first cold start.
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        // No retryOnConnectionFailure — we want explicit control
        // over retries (auth refresh on 401 is the only retry the
        // client owns; everything else goes through outbox backoff).
        .retryOnConnectionFailure(false)
        .build()

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    // ── Result types the worker switches on ─────────────────────────

    sealed interface ApiResult {
        /** Server accepted the events with the given ids. */
        data class Success(val acceptedIds: List<String>) : ApiResult
        /** 401 after one auth refresh — leave rows queued. */
        data object Unauthorized : ApiResult
        /** 429 — leave rows, retry after [retryAfterSeconds]. */
        data class RateLimited(val retryAfterSeconds: Long) : ApiResult
        /** 4xx other than 401/429 — drop rows; retrying won't help. */
        data class ClientError(val code: Int, val body: String?) : ApiResult
        /** 5xx — leave rows + back off. */
        data class ServerError(val code: Int, val body: String?) : ApiResult
        /** Network / IO failure — leave rows + back off. */
        data class NetworkError(val message: String) : ApiResult
    }

    // ── Wire-shape DTOs (must match Rails controllers) ──────────────

    @Serializable
    private data class BatchEnvelope(val events: List<RawCapturePayload>)

    @Serializable
    private data class BatchResponse(val accepted: List<String> = emptyList())

    @Serializable
    private data class RawCapturePayload(
        val capture_id: String,
        val source: String,
        val page_count: Int,
        val category: String? = null,
        val has_ocr: Boolean,
        val ocr_chars: Int,
        val captured_at: String,
    )

    // ── Public surface ──────────────────────────────────────────────

    /**
     * POST a single identify body. Returns Success with an empty
     * acceptedIds list (the endpoint is 204 with no body); the
     * caller drops the outbox row regardless on Success.
     */
    suspend fun postIdentify(payloadJson: String): ApiResult =
        post("/v1/identify", payloadJson) { _ ->
            ApiResult.Success(acceptedIds = emptyList())
        }

    /**
     * POST a batch of capture events. The `rawCaptureRows` argument
     * carries (id, payloadJson) tuples — id is the outbox row id
     * (= capture_event UUID); payloadJson was serialized by
     * AnalyticsRepository when the row was enqueued.
     */
    suspend fun postCaptureBatch(
        rawCaptureRows: List<Pair<String, String>>,
    ): ApiResult {
        if (rawCaptureRows.isEmpty()) return ApiResult.Success(emptyList())

        // Re-parse each stored payload into a typed DTO so we can
        // wrap them in {events: [...]}. Could also have stored the
        // pre-wrapped envelope, but that bloats the outbox and
        // forces re-batching after partial server rejection.
        val events = rawCaptureRows.map { (_, raw) ->
            json.decodeFromString(RawCapturePayload.serializer(), raw)
        }
        val body = json.encodeToString(BatchEnvelope(events))

        return post("/v1/events/capture/batch", body) { responseBody ->
            val parsed = if (responseBody.isNullOrBlank()) {
                BatchResponse()
            } else {
                runCatching { json.decodeFromString(BatchResponse.serializer(), responseBody) }
                    .getOrDefault(BatchResponse())
            }
            ApiResult.Success(acceptedIds = parsed.accepted)
        }
    }

    // ── Core POST with auth + 401 refresh ───────────────────────────

    private suspend fun post(
        path: String,
        bodyJson: String,
        successMapper: (responseBody: String?) -> ApiResult,
    ): ApiResult {
        return try {
            val first = doPost(path, bodyJson, refresh = false)
            // Status 401 → refresh token + retry once.
            // 401 is a real possibility on stale id tokens that
            // outlived the silent-refresh check (e.g. between an
            // outbox enqueue and the worker firing).
            if (first.code == 401) {
                first.close()
                doPost(path, bodyJson, refresh = true).use { mapResult(it, successMapper) }
            } else {
                first.use { mapResult(it, successMapper) }
            }
        } catch (e: IOException) {
            Log.w(TAG, "[analytics] network error on $path: ${e.message}")
            ApiResult.NetworkError(e.message ?: e::class.java.simpleName)
        } catch (e: Exception) {
            Log.w(TAG, "[analytics] unexpected error on $path: ${e.message}")
            ApiResult.NetworkError(e.message ?: e::class.java.simpleName)
        }
    }

    private suspend fun doPost(path: String, bodyJson: String, refresh: Boolean): Response {
        // Force a fresh token by clearing the cached one when
        // refresh=true. The shared client doesn't expose that
        // directly today; for v1 we just call idToken() again —
        // the impl already auto-refreshes when the cached token's
        // remaining TTL is below MIN_TTL_SECONDS. The `refresh`
        // flag is reserved for a future hook on AuthStore that
        // actively invalidates the cache before fetching.
        val token = authStore.idToken()
        val request = Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return http.newCall(request).execute()
    }

    private fun mapResult(
        response: Response,
        successMapper: (String?) -> ApiResult,
    ): ApiResult {
        val code = response.code
        val body = runCatching { response.body?.string() }.getOrNull()

        return when {
            code in 200..299 -> successMapper(body)

            code == 401 -> ApiResult.Unauthorized

            code == 429 -> {
                // Rack::Attack sets Retry-After in seconds. Default
                // to 60 if header missing or unparseable.
                val retryAfter = response.header("Retry-After")
                    ?.toLongOrNull()
                    ?: 60L
                ApiResult.RateLimited(retryAfter)
            }

            code in 400..499 -> ApiResult.ClientError(code, body?.take(MAX_BODY_LOG))

            code in 500..599 -> ApiResult.ServerError(code, body?.take(MAX_BODY_LOG))

            else -> ApiResult.NetworkError("unexpected status $code")
        }
    }

    companion object {
        private const val TAG = "QuickInkAnalytics"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_BODY_LOG = 200
    }
}
