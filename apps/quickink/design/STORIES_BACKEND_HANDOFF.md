# Stories backend — handoff for the server session

**For:** the engineer (or session) standing up the public-link backend.
**Status:** apps are ready. Server work hasn't started.
**Last updated:** May 2026.

This is the doc to read after `STORIES_HANDOFF.md` is finished and you're picking up the work that crosses out of the app codebase. The
client-side publish flow already exists, gated by a single
`STUB_BACKEND` constant on both platforms. Your job: stand up the
real service, replace the stubbed call, and host the reader page.

---

## 0. State of play

**Done in the apps (Phase 6, client side):**

- Confirm dialog → publish flow plumbed through the share sheet on
  both platforms.
- `StoryPublisher` (iOS + Android) — typed surface for `publish` /
  `unpublish`, stubbed implementations returning a random 8-char
  slug after 800 ms.
- `StoryRepository.markPublished(storyId, slug)` /
  `markUnpublished(storyId)` — flips `share_mode` / `share_slug` /
  `status`, marks dirty.
- Drive sync round-trip — `StoryPayloadV1` carries `share_mode` /
  `share_slug` so the published state syncs across devices via the
  existing pipeline.
- "Stop sharing" UX in the link box, destructive-confirmed.
- Passcode "+ add" still toasts ("ships in v1.1") — passcode is
  optional per design, so the v3 ship doesn't need it on day one.

**Stub flip points:**

| File | Constant | Today | Action |
|---|---|---|---|
| `ios/QuickInk/Stories/StoryPublisher.swift` | `kBackendStubbed` | `true` | flip to `false`, fill in `publish()` body |
| `android/.../features/stories/StoryPublisher.kt` | `STUB_BACKEND` | `true` | flip to `false`, fill in `publish()` body |

Both files have a placeholder `throw` for the real path with inline
doc tracing the contract. Once the endpoint is live, the client
becomes a thin wrapper around it.

---

## 1. Endpoint contract

Per `STORIES_DESIGN.md` §9:

```
POST   /v1/stories/publish
DELETE /v1/stories/publish/{slug}
```

### 1.1 Publish

**Request body** — the story manifest. The shape should round-trip
the existing `StoryPayloadV1` + `StoryItemPayloadV1` Drive payloads
verbatim so the client can build it from local rows with one
serializer:

```json
{
  "story": { /* StoryPayloadV1 minus share_mode/share_slug */ },
  "items": [ /* StoryItemPayloadV1[], ordered by position */ ],
  "voice_clips": [ /* StoryVoiceClipPayloadV1[], optional */ ],
  "media": [
    { "ref_id": "<capture-id>", "drive_file_id": "...", "kind": "preview_jpeg" },
    { "ref_id": "<voice-clip-id>", "drive_file_id": "...", "kind": "audio_m4a" }
  ],
  "passcode_hash": null   // optional; bcrypt(passcode) when set
}
```

**Response** — `200 OK`:

```json
{
  "slug": "h7tj9k28",
  "url":  "https://share.quickink.app/s/h7tj9k28",
  "expires_at": null
}
```

Status codes the client already handles:
- `429` → `PublishException.RateLimited`
- transport / 5xx → `PublishException.Network`
- anything else → `PublishException.Other(message)`

**Slug rules:**
- 8 chars from base32 `[a-z2-7]`
- Unguessable; collisions retried server-side
- Owned by the server — the client's `generateSlug()` is fallback /
  preview only.

### 1.2 Unpublish

```
DELETE /v1/stories/publish/{slug}
Authorization: Bearer <user-token>
```

Authorize against the story's owning user. Idempotent: a
`404 Not Found` is treated as success client-side (the slug is
already gone, which is what the user wanted).

### 1.3 Auth

Both endpoints require a Google-issued bearer token, same as the
Drive client. The QuickInk app already vends `accessToken` to the
binary-sync layer — same approach: pass it through to the
publisher.

---

## 2. Media handling

The manifest itself is small. The expensive part is the referenced
media — story-item refs point at `captures` rows whose preview JPEGs
are already on the user's Drive. Two options:

1. **CDN copy on publish (preferred).** Server reads the referenced
   Drive files (using the user's bearer), writes them to a
   CDN bucket keyed by slug. The reader page fetches from the CDN.
   Why: doesn't fall over when the user revokes Drive scope,
   doesn't depend on a still-online Drive file.
2. **Direct Drive embeds.** Reader page asks Drive for the file
   via the slug-keyed grant.

Go with **option 1**. It's more storage but matches the user mental
model ("publishing is a snapshot") and isolates the reader from
auth churn. The bucket layout is up to you; suggestion:

```
share.quickink.app/_/<slug>/cover.jpg
share.quickink.app/_/<slug>/items/<itemId>.jpg
share.quickink.app/_/<slug>/voice/<clipId>.m4a
share.quickink.app/_/<slug>/manifest.json
```

The reader page is rendered from `manifest.json` plus the per-item
binaries.

---

## 3. Reader page

`https://share.quickink.app/s/{slug}` — static-rendered HTML with the
§7.4 reader layout. Same DOM shape as
`design/stories-mockup-v3.html` frame `s4`; the Compose / SwiftUI
reader is the source of truth for visual behaviour, so port the
class names + tokens 1:1 if you want a fast path.

**Minimum viable:**
- Next.js or Astro, generates the HTML at publish time, stores in
  the CDN bucket.
- Tokens from `design/BRAND.md` table — same coral / paper /
  Cormorant / Caveat as the apps.
- Day markers computed from the manifest via the same algorithm as
  `StoryDayMarkers.{swift,kt}`. Reuse the spec in
  `shared/algorithms/story-suggestions.md` (the day-marker rule is
  documented in the iOS/Android files themselves).
- Mobile-first; works at 320 px width.
- No SPA / client-side JS beyond a single "Reply with a note"
  mailto link (the actual reply target is a v1.1 decision per
  open-question 5 in `STORIES_HANDOFF.md` §9).

**Out of scope for v3:**
- Comments
- Per-page reactions
- View counters
- Analytics (other than basic CDN-level hit logging)

---

## 4. Passcode (optional)

Per design, passcode is optional. Implementation order:

1. Land publish/unpublish first; ship v3 without passcode.
2. v1.1 — add `passcode_hash` (bcrypt) to the publish payload.
   Server stores it on the manifest row, the reader page renders
   a "Enter passcode" gate that validates against
   `POST /v1/stories/passcode-check` (no-cookie, no-IP-binding — a
   plain check returning `{ ok: bool }`).
3. App side — the share sheet's `+ add` next to "require a passcode"
   currently toasts; replace with a single-field sheet, hash
   client-side with `bcrypt`-equivalent (or send plain over TLS and
   hash server-side; defer the decision).

The app already round-trips `share_mode = 'public_link'` cleanly
across devices, so the passcode bit can land server-side first
without an app update.

---

## 5. Rate limiting + abuse

The app surfaces `PublishException.RateLimited` already. Server
should:
- Cap publishes-per-user to ~10/day at first; relax once you've
  watched a few weeks of dogfood telemetry.
- Reject slug-generation requests that would land in a known-bad
  prefix list (avoid the obvious word + brand collisions).
- Don't auto-publish without an explicit `POST` body — the don't-do
  list in `STORIES_HANDOFF.md` §8 is load-bearing here.

---

## 6. Verification before the apps flip

Smoke check this before flipping `kBackendStubbed` / `STUB_BACKEND`:

1. `curl -X POST https://api.quickink.app/v1/stories/publish` with
   a minimal manifest → expect 200 + a slug.
2. Open `https://share.quickink.app/s/{slug}` → see the reader page
   render the manifest.
3. `curl -X DELETE https://api.quickink.app/v1/stories/publish/{slug}`
   → 204; refresh the reader → 404.

Once those three pass, drop the stub:

**iOS** — `StoryPublisher.swift`:
```swift
public static let kBackendStubbed = false
// In publish(): URLSession.shared.upload(for: request, from: body) ...
```

**Android** — `StoryPublisher.kt`:
```kotlin
const val STUB_BACKEND = false
// In publish(): OkHttpClient().newCall(request)...
```

The rest of the app expects nothing else.

---

## 7. Telemetry

Per `STORIES_DESIGN.md` §605, the metrics that matter:

- `stories.publish.success` (counter) — slug minted
- `stories.publish.fail` (counter, tagged by error type)
- `stories.unpublish.success` (counter)
- `stories.read.hit` (counter, tagged by referrer) — CDN access
- `stories.read.unique_visitors_24h` (gauge)

Wire these into whatever stack the team is already using; the apps
have their own analytics outbox via `AnalyticsRepository.enqueue*`
that you can extend to log `publish_clicked` / `publish_completed`
events alongside the server-side counters.

---

## 8. What's already in place

`shared/algorithms/story-suggestions.md` is the canonical spec for
the (client-side) suggestion engine — useful if you ever want to
mirror the same algorithm on the server for future "popular
story" / "trending" surfaces.

`apps/quickink/design/STORIES_HANDOFF.md` §10 has the apps-side
definition of done; backend session needs its own checklist roughly
matching:

1. `POST /v1/stories/publish` returns within 2 s on a typical
   10-item manifest.
2. The reader page renders correctly at 320 px width with the
   correct tokens.
3. `DELETE /v1/stories/publish/{slug}` removes the manifest AND
   the CDN binaries within ~1 minute.
4. Slug collisions are handled server-side without surfacing to
   the client.
5. Rate limit returns 429 with `Retry-After`.

When all five pass, flip the constants and let the apps know.
