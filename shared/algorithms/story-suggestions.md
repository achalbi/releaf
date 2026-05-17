# Story suggestions — date-clustering algorithm

> Spec for the per-platform `StorySuggestionEngine` implementations.
> iOS: `apps/quickink/ios/QuickInk/Stories/StorySuggestionEngine.swift`
> Android: `apps/quickink/android/app/src/main/java/app/quickink/mobile/features/stories/StorySuggestionEngine.kt`
>
> Both platforms implement this natively in v1 (no shared cross-platform
> code yet — see `STORIES_DESIGN.md` §17). They MUST emit identical
> output for identical inputs so the spec lives here.

## 1. Inputs

The engine takes:

- **User ID** — scopes the capture pool.
- **Captures** — every row in `captures` for that user where
  `deleted_at IS NULL`, sorted ascending by `created_at` (the
  capture's local-clock ISO timestamp).
- **Now** — current wall-clock time (used only for a future
  freshness heuristic; not used in v3).
- **Dismissed-set** — an in-memory set of suggestion ids the user
  has dismissed in the current process session. Cleared on app
  restart.

It does **not** consider `story_item` rows. The suggestion is "you
have a cluster in your library that could become a story" — its
provenance is the capture stream, not existing stories.

## 2. Output

Zero or one `StorySuggestion` per call. The shape:

```kotlin
data class StorySuggestion(
  val id: String,                  // stable across runs for same input
  val title: String,               // "Captures, May 4–7" or with locality
  val reason: String,              // "10 scans and 3 photos, May 4–7"
  val candidateRefs: List<String>, // ordered capture ids
  val score: Double,
)
```

`id` is `sha1("\(firstCaptureId)\(lastCaptureId)")` truncated to 16
hex chars. That way two engine runs over the same cluster emit the
same id, so a session-level dismissal sticks across re-runs.

## 3. Clustering

Greedy single-pass over the captures sorted ascending by `created_at`:

1. Start with an empty `current` cluster.
2. For each capture `c`:
   - If `current` is empty: add `c`, continue.
   - Otherwise look at the gap between `c.created_at` and the last
     capture in `current`. If the gap is **strictly greater than 18
     hours** AND `current.size >= 3`, close `current` (push to
     clusters list) and start a new cluster with `c`. Else add `c`
     to `current`.
3. After the loop, if `current.size >= 1`, append it to clusters.

The "≥ 3 items in the previous cluster" rule prevents the algorithm
from creating one-item-then-cut clusters when a user takes a single
capture, waits a day, then takes a single capture. Two singletons
stay merged into one weak cluster, which is then dropped by step 4.

## 4. Filtering

Drop clusters with fewer than **4 items**. Low signal.

## 5. Scoring

For each qualifying cluster, score = `itemCount * max(1, diversity)`.

Diversity in v1 is the count of distinct `source` values present in
the cluster — `scan`, `import`, etc. The v1 schema only emits
`scan` and `import` so diversity is 1 or 2. Once notepad entries
join the suggestion pool (v1.1), diversity expands to the conceptual
{ photo, document, note } triplet from `STORIES_DESIGN.md` §8.1.

If two clusters tie on score, prefer the more recent one (the one
whose last capture is later).

## 6. Title + reason

The selected cluster's title and reason are derived from its
captures:

- **Title** — `"Captures, MMM d–d"` for same-month spans;
  `"Captures, MMM d – MMM d"` for cross-month spans. If 60% or more
  of the cluster's captures share a non-empty `locality`, prepend
  it: `"Tokyo, May 4–7"`.
- **Reason** — `"{N1} scans and {N2} photos, MMM d–d"` (omit the
  zero side; "10 scans, May 4–7" when no imports). If the cluster
  has one source only, drop the "N2" half.

Both strings use the device's locale month abbreviations (`MMM`).
Dates render in the cluster's local timezone (the device's current
timezone — we don't store per-capture timezone in v1).

## 7. Dismissal

The shelf VM owns a process-scoped `Set<String>` of dismissed
suggestion ids. The engine consults this set: if the best cluster's
id is dismissed, it returns the next-best (by score) that isn't.
If no surviving cluster qualifies, returns nil.

The set clears on app restart per the v3 open-question 4 resolution
(session-only). Persisted dismissal is v1.1 polish.

## 8. When to run

The engine is cheap (single sort + single pass + filter + score).
Run it:

- When the Stories shelf is opened (in the VM's `start()`).
- When any capture lands or is removed (a `dirty` flag-flipping
  event on the captures table — wire the shelf VM to observe).

It is **not** run from background workers. No persistence side
effects.

## 9. Determinism

For the same `(userId, captures, dismissed)` input, the engine MUST
emit the same suggestion. This is the property that lets a session
dismissal stick: re-running the engine returns the same id, the
shelf checks the set, suppresses, and renders the empty state.

## 10. Future (v1.1+)

- **Activity mode** (`STORIES_DESIGN.md` §8.2) — co-location, entity
  overlap, tag overlap. Layered on top of the date clusters; same
  output shape, different reason strings.
- **Persisted dismissals** — backed by `story_suggestion_cache`
  table; survives restart.
- **Multi-cut suggestions** — emit a small ranked list rather than a
  single best. Requires the calmer UX from §3d (only one card at
  rest, the rest behind a "More" affordance).
