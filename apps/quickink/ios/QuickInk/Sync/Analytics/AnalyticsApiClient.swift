/*
 * AnalyticsApiClient.swift
 *
 * URLSession wrapper that POSTs outbox events to the QuickInk
 * analytics backend. Two endpoints:
 *
 *   POST /v1/identify              — once on cold-start / sign-in
 *   POST /v1/events/capture/batch  — N events per call, ≤ 200
 *
 * Auth: every request carries a Google ID token in Authorization.
 * The token comes from `AuthStore.idToken()` — the iOS impl
 * uses GoogleSignIn's `refreshTokensIfNeeded` which auto-skips
 * the network call when the cached token is still fresh.
 *
 * Failure semantics — per the consolidated v1 spec:
 *   2xx                    → return .success(acceptedIds)
 *   401 (after one retry)  → caller leaves rows queued + logs
 *   429                    → caller honors Retry-After
 *   other 4xx              → caller drops rows
 *   5xx / network          → caller schedules backoff
 *
 * Mirror of Android `AnalyticsApiClient.kt`.
 */

import Foundation
import ReleafCoreAuth

struct AnalyticsApiClient {

    // ── Result type the flush task switches on ──────────────────

    enum ApiResult: Equatable {
        case success(acceptedIds: [String])
        case unauthorized
        case rateLimited(retryAfterSeconds: TimeInterval)
        case clientError(code: Int, body: String?)
        case serverError(code: Int, body: String?)
        case networkError(message: String)
    }

    // ── Wire-shape DTOs ─────────────────────────────────────────

    private struct BatchEnvelope: Encodable {
        let events: [RawCapturePayload]
    }

    private struct BatchResponse: Decodable {
        let accepted: [String]?
    }

    /// Mirrors the Rails controller's expected JSON keys exactly.
    /// The same shape AnalyticsRepository wrote into payloadJson.
    private struct RawCapturePayload: Codable {
        let capture_id:  String
        let source:      String
        let page_count:  Int
        let category:    String?
        let has_ocr:     Bool
        let ocr_chars:   Int
        let captured_at: String
    }

    let authStore: AuthStore
    let baseUrl:   URL
    let session:   URLSession

    init(authStore: AuthStore, baseUrl: URL, session: URLSession = .shared) {
        self.authStore = authStore
        self.baseUrl   = baseUrl
        self.session   = session
    }

    // ── Public surface ──────────────────────────────────────────

    /// POST a single identify body. Returns .success with empty
    /// acceptedIds (the endpoint returns 204) — caller drops the
    /// outbox row regardless on success.
    func postIdentify(payloadJson: String) async -> ApiResult {
        await post(
            path:     "/v1/identify",
            bodyJson: payloadJson
        ) { _ in
            .success(acceptedIds: [])
        }
    }

    /// POST a batch of capture events. `rows` is (id, payloadJson)
    /// tuples — id is the outbox row id (= capture_event UUID);
    /// payloadJson was serialized by AnalyticsRepository when the
    /// row was enqueued.
    func postCaptureBatch(rows: [(id: String, payloadJson: String)]) async -> ApiResult {
        guard !rows.isEmpty else { return .success(acceptedIds: []) }

        let decoder = JSONDecoder()
        let encoder = JSONEncoder()

        let parsed: [RawCapturePayload]
        do {
            parsed = try rows.map {
                let data = $0.payloadJson.data(using: .utf8) ?? Data()
                return try decoder.decode(RawCapturePayload.self, from: data)
            }
        } catch {
            return .networkError(message: "outbox payload parse failed: \(error)")
        }

        let envelope = BatchEnvelope(events: parsed)
        let bodyData: Data
        do {
            bodyData = try encoder.encode(envelope)
        } catch {
            return .networkError(message: "envelope encode failed: \(error)")
        }
        let bodyJson = String(data: bodyData, encoding: .utf8) ?? "{}"

        return await post(
            path:     "/v1/events/capture/batch",
            bodyJson: bodyJson
        ) { responseBody in
            guard let data = responseBody?.data(using: .utf8),
                  let parsedResp = try? decoder.decode(BatchResponse.self, from: data)
            else {
                return .success(acceptedIds: [])
            }
            return .success(acceptedIds: parsedResp.accepted ?? [])
        }
    }

    // ── Core POST with auth + 401 refresh ────────────────────────

    private func post(
        path: String,
        bodyJson: String,
        successMapper: (_ responseBody: String?) -> ApiResult
    ) async -> ApiResult {
        do {
            let first = try await doPost(path: path, bodyJson: bodyJson)
            // Status 401 → refresh token + retry once. Real
            // possibility on stale tokens that outlived the
            // silent-refresh check (e.g. between an outbox enqueue
            // and the worker firing).
            if first.code == 401 {
                let retry = try await doPost(path: path, bodyJson: bodyJson)
                return mapResult(retry, successMapper: successMapper)
            }
            return mapResult(first, successMapper: successMapper)
        } catch {
            NSLog("[analytics] network error on %@: %@",
                  path, "\(error)")
            return .networkError(message: "\(error)")
        }
    }

    private struct HttpResponse {
        let code:    Int
        let body:    String?
        let headers: [AnyHashable: Any]
    }

    private func doPost(path: String, bodyJson: String) async throws -> HttpResponse {
        let token = try await authStore.idToken()
        var request = URLRequest(url: baseUrl.appendingPathComponent(path))
        request.httpMethod = "POST"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        // 60s tolerates Cloud Run cold-start latency (~10–25s when
        // min-instances=0 + the revision has been idle). The first
        // request after an idle period is what we'd otherwise miss
        // — every subsequent request lands in <200ms because the
        // instance is warm. The outbox would retry on backoff
        // anyway, so this is a "make the first event land in one
        // shot" optimization rather than a correctness fix.
        request.timeoutInterval = 60
        request.httpBody = bodyJson.data(using: .utf8)

        let (data, urlResp) = try await session.data(for: request)
        guard let http = urlResp as? HTTPURLResponse else {
            throw AnalyticsError.invalidResponse
        }
        return HttpResponse(
            code:    http.statusCode,
            body:    String(data: data, encoding: .utf8),
            headers: http.allHeaderFields
        )
    }

    private func mapResult(
        _ resp: HttpResponse,
        successMapper: (_ responseBody: String?) -> ApiResult
    ) -> ApiResult {
        switch resp.code {
        case 200..<300:
            return successMapper(resp.body)
        case 401:
            return .unauthorized
        case 429:
            // Rack::Attack sets Retry-After in seconds. Default
            // to 60 if header missing or unparseable.
            let retryAfter = (resp.headers["Retry-After"] as? String).flatMap(TimeInterval.init) ?? 60
            return .rateLimited(retryAfterSeconds: retryAfter)
        case 400..<500:
            return .clientError(code: resp.code, body: resp.body?.prefix(maxBodyLog).map(String.init).joined())
        case 500..<600:
            return .serverError(code: resp.code, body: resp.body?.prefix(maxBodyLog).map(String.init).joined())
        default:
            return .networkError(message: "unexpected status \(resp.code)")
        }
    }

    private let maxBodyLog = 200

    enum AnalyticsError: Error {
        case invalidResponse
    }
}
