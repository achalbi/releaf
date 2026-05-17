/*
 * StoryPublisher.swift
 *
 * Stories Phase 6 — client-side publish flow with a stubbed HTTP
 * call. The real backend (POST /v1/stories/publish per
 * STORIES_DESIGN.md §9) lives in a separate service repo; when it
 * lands, flip `kBackendStubbed` to `false` and replace
 * `publishStubbed` with the real `URLSession` call.
 *
 * Contract:
 *   - `publish(story, items) async throws -> Published(slug, url)`
 *     – generates a slug, simulates 800 ms latency, returns the
 *     would-be public URL. Stubbed: doesn't actually persist a
 *     manifest anywhere.
 *   - `unpublish(story) async throws` – stubbed; just simulates a
 *     network round trip.
 *
 * Slug format mirrors §9 spec: 8 chars, base32-alphabet
 * (lowercase a-z + digits 2-7), unguessable.
 *
 * Mirror of Android `StoryPublisher.kt`.
 */

import Foundation

public struct StoryPublished: Equatable {
    public let slug: String
    public let url:  String
}

public enum StoryPublisher {

    /// When `true`, all network calls are local stubs. The real
    /// backend service flips this to `false`; the stubbed paths
    /// stay as a local-dev shortcut for previews + UI work.
    public static let kBackendStubbed = true

    public static let publicLinkOrigin = "https://quickink.app/s/"

    public enum PublishError: Error, LocalizedError {
        case network
        case rateLimited
        case other(String)

        public var errorDescription: String? {
            switch self {
            case .network:     return "Couldn't reach the publish service."
            case .rateLimited: return "Too many publish attempts — try again in a minute."
            case .other(let m): return m
            }
        }
    }

    /// Issue a publish for the story. In the stubbed path this
    /// returns a generated slug + URL after a short delay; in the
    /// real path it POSTs the manifest + uploaded media references
    /// to the backend.
    public static func publish(story: Story, items: [StoryItem]) async throws -> StoryPublished {
        if kBackendStubbed {
            return try await publishStubbed(story: story, items: items)
        }
        // Real backend lands here — placeholder so the compiler
        // tracks the surface area:
        //   1. Upload referenced media via QuickInkBinarySync (most
        //      already uploaded thanks to the dirty-sync pipeline).
        //   2. POST /v1/stories/publish with the manifest JSON
        //      (story + items + voice-clip refs).
        //   3. Server returns { slug, url, expires_at? }.
        //   4. On 429 → throw .rateLimited; on transport → .network.
        throw PublishError.other("real backend not wired yet")
    }

    public static func unpublish(story: Story) async throws {
        if kBackendStubbed {
            try await Task.sleep(nanoseconds: 400_000_000)
            return
        }
        // Real backend: DELETE /v1/stories/publish/{slug}.
        throw PublishError.other("real backend not wired yet")
    }

    // MARK: - Stubbed implementation

    private static func publishStubbed(story: Story, items: [StoryItem]) async throws -> StoryPublished {
        try await Task.sleep(nanoseconds: 800_000_000)
        let slug = story.shareSlug.flatMap { $0.isEmpty ? nil : $0 } ?? generateSlug()
        return StoryPublished(slug: slug, url: publicLinkOrigin + slug)
    }

    /// 8-char base32-alphabet slug, sourced from
    /// `SystemRandomNumberGenerator`. Stable surface — when the real
    /// backend ships the server generates the slug authoritatively
    /// and the client uses whatever it returns; this helper is just
    /// the client-side fallback / preview.
    public static func generateSlug() -> String {
        let alphabet = Array("abcdefghijklmnopqrstuvwxyz234567")
        var rng = SystemRandomNumberGenerator()
        var out = ""
        out.reserveCapacity(8)
        for _ in 0..<8 {
            let idx = Int(rng.next() % UInt64(alphabet.count))
            out.append(alphabet[idx])
        }
        return out
    }
}
