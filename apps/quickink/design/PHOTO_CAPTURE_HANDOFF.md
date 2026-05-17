# Photo Capture — Claude Code Handoff

Paste this into Claude Code in `apps/quickink`. Self-contained brief
for implementing the Photo Capture feature designed in
`design/PHOTO_CAPTURE_SPEC.md`.

---

## Context (read first)

You are adding a **third capture surface** to QuickInk iOS — a
single-shot photo mode — that plugs into the existing scan
pipeline. Full design in `design/PHOTO_CAPTURE_SPEC.md`; read it
top-to-bottom before writing code. Sections you'll reference most:
§2 (architecture), §4 (the new surface), §5 (enum + dispatch
changes), §13 (file list).

**The integration is small on purpose.** The flow controller, the
voice-note pane, the review screen, and the import-artifact helper
are already source-agnostic. Photo capture only needs to (a) capture
one frame, (b) hand it to `ImportArtifacts.build(from: [image])`,
(c) call `controller.onScanComplete(..., source: "photo", paperSize: .custom)`,
and (d) dismiss. Everything downstream — voice note → review →
OCR → library — runs unchanged.

---

## Goal

After this PR:

1. Long-pressing the ⚡ FAB on the bottom nav opens a camera that
   takes one photo and routes it into the standard scan flow.
2. A camera-icon button on `DocumentCaptureSurface` and
   `BusinessCardCaptureSurface` shutter rows is a second entry
   into the same surface.
3. After "Use Photo," the user lands on `VoiceNoteCapturePane`,
   then `ScanReviewScreen`, exactly like a scanned document.
4. Persistence sets `source = "photo"` and `paperSize = .custom`
   on the capture row.

Non-goals: multi-shot burst, quad detection, new review screen,
new analytics events, Android port (file a paired ticket — §14 of
the spec).

---

## File map

**Create**
- `ios/QuickInk/Scan/PhotoCaptureSurface.swift` — new surface.
  Model it on `ios/QuickInk/Scan/CardCapture/BusinessCardCaptureSurface.swift`
  for the AVCaptureSession + permission-gate scaffolding, but
  strip out the detector, stability gate, and overlay. See spec
  §4 for required states.

**Modify**
- `ios/QuickInk/Scan/CaptureMode.swift` — add `.photo` case.
  Update `analyticsKey`, `pillLabel`, `paperSize`,
  `fromAnalyticsKey`. Spec §5.
- `ios/QuickInk/Scan/QuickCaptureScreen.swift` — accept
  `initialMode: CaptureMode?` in init; add `.photo` branch in
  the surface `switch coordinator.mode`. Long-press route uses a
  no-persist coordinator (`persist: { _ in }`) so the FAB
  long-press doesn't overwrite the user's pill choice.
- `ios/QuickInk/Scan/DocumentCaptureSurface.swift` — replace
  the left `Spacer().frame(width: 64)` in `shutterRow` (line ~148)
  with a Photo button (SF Symbol `camera`, same 48pt disc styling
  as the existing `importButton` next to it). Tap calls
  `coordinator.select(.photo)` — but the coordinator lives on
  the parent. Pass `onSelectPhoto: () -> Void` into
  `DocumentCaptureSurface` and have `QuickCaptureScreen` wire it
  to the coordinator.
- `ios/QuickInk/Scan/CardCapture/BusinessCardCaptureSurface.swift`
  — same Photo button treatment in its shutter row.
- `ios/QuickInk/Nav/QuickInkBottomNavBar.swift` — add
  `onLongPressScan: () -> Void` callback alongside the existing
  `onScan`. Attach a `.onLongPressGesture(minimumDuration: 0.4)`
  to the zap FAB's `ZStack` (currently a plain `Button(action:
  onScan)` around lines 265–283). Light haptic + accessibilityHint
  copy as in spec §3.1.
- `ios/QuickInk/App/QuickInkRoot.swift` — add a
  `@State var pendingInitialMode: CaptureMode? = nil`; wire
  `onLongPressScan` on the bottom nav (around line 488) to set
  it `.photo` and flip `showQuickCapture = true`. Pass it through
  to `QuickCaptureScreen` (around line 556). Reset to `nil` on
  dismiss.

**Read-only (do not edit, but confirm they work)**
- `ios/QuickInk/Scan/ScanFlowController.swift` — already accepts
  `source` and `paperSize`.
- `ios/QuickInk/Scan/ImportArtifacts.swift` — already takes
  `[UIImage]` and returns the URL triple `onScanComplete` wants.
- `ios/QuickInk/Scan/ScanCaptureSurface.swift` — already mounts
  `VoiceNoteCapturePane` whenever the controller leaves `.idle`.
- `ios/QuickInk/Scan/VoiceNoteCapturePane.swift` — copy holds
  for photos, no string change.
- `ios/QuickInk/Scan/CaptureAnalytics.swift` — parameterised on
  `CaptureMode`, no edit needed.

---

## Implementation order

Work it in this order so each commit is independently buildable
and roughly reviewable on its own:

1. **`CaptureMode.swift`** — add `.photo`. Build the project; the
   exhaustive `switch coordinator.mode` in `QuickCaptureScreen`
   will fail to compile. That's the next step.
2. **`QuickCaptureScreen.swift`** — add the `.photo` branch
   (point it at a stub `PhotoCaptureSurface` view that just shows
   a placeholder for now). Add `initialMode: CaptureMode?` arg
   with the no-persist override. Build again.
3. **`PhotoCaptureSurface.swift`** — implement the real surface.
   Start with the permission gate, then the AVCaptureSession +
   preview + shutter, then the captured-state preview UI, then
   the `Use Photo` → `ImportArtifacts.build` → `onScanComplete`
   handoff. Don't fold in flash/flip controls until the basic
   shutter path lands.
4. **`QuickInkBottomNavBar.swift`** — add `onLongPressScan` and
   the long-press gesture on the zap FAB.
5. **`QuickInkRoot.swift`** — wire the long-press callback to
   set `pendingInitialMode = .photo` and flip the cover.
6. **`DocumentCaptureSurface.swift` + `BusinessCardCaptureSurface.swift`**
   — add the Photo icon button to each shutter row. Wire via a
   new `onSelectPhoto: () -> Void` arg threaded from
   `QuickCaptureScreen`.

Each step should compile + run before moving on. The placeholder
in step 2 means you can verify the long-press → initialMode path
end-to-end before the real camera surface exists.

---

## Acceptance criteria

- [ ] Tapping the ⚡ FAB still opens Document mode (with the pill
      reflecting last-used mode from UserDefaults).
- [ ] Long-pressing the ⚡ FAB opens `PhotoCaptureSurface` directly.
- [ ] After a long-press capture, the user's *next* tap on the
      FAB returns them to the previously-pill-selected mode
      (Document or Business Card) — long-press did NOT overwrite
      `quickink.capture.last_mode`.
- [ ] Inside Document mode, the new camera icon in the shutter
      row's left slot opens `PhotoCaptureSurface` via the
      coordinator. Same in Business Card mode.
- [ ] `PhotoCaptureSurface` requests camera permission on first
      mount; denial shows the rationale + Settings CTA.
- [ ] Shutter tap freezes the preview; Retake returns to live
      preview; Use Photo calls `controller.onScanComplete` with
      `source: "photo"`, `paperSize: .custom`.
- [ ] After Use Photo, `QuickCaptureScreen` dismisses and the
      user lands on `VoiceNoteCapturePane`, then `ScanReviewScreen`
      on Skip / Save.
- [ ] The new capture row has `source = 'photo'` in SQLite.
- [ ] OSLog shows `capture_mode_selected mode=photo` and
      `capture_manual_fired mode=photo`.
- [ ] No regressions on Document or Business Card capture flows.
- [ ] Code compiles under the package's iOS 16 floor (no use of
      iOS 17+ Observable macro / `.onChange(of:initial:_:)` two-
      arg form — see `ScanFlowController.swift` header for the
      floor rationale).

---

## Test plan

Manual:
1. Cold launch → Home → tap FAB → confirm Document. Close.
2. Long-press FAB → confirm Photo surface mounts.
3. Snap photo → Retake → Snap again → Use Photo → confirm voice-
   note pane → Skip → confirm review screen → Done → confirm row
   appears in Library.
4. Tap FAB again → confirm it returns to Document (not Photo).
5. Open Document mode → tap Photo icon in shutter row → confirm
   surface switch via coordinator (top-bar pill should still
   read "Document" — pill is two-wide, Photo is a transient
   mode entered by icon, not by pill).
6. Deny camera permission in Settings → reopen Photo surface →
   confirm rationale + Settings CTA appear.
7. Background the app on the captured-preview state → return →
   confirm we land on live preview (buffer dropped, per spec
   §11).

Automated: extend the existing `Tests/` target with a unit test
that constructs `CaptureModeCoordinator(initial: .document,
persist: persistSpy)` and asserts `select(.photo)` does call
`persistSpy` — and a separate test with `persist: { _ in }`
asserts no overwrite. Add a snapshot test for `PhotoCaptureSurface`
in its `.permissionGate` and `.preview` states (use the same
snapshot harness `Tests/` uses for the card surface, if one
exists; otherwise skip).

---

## Style / parity notes

- Every file you touch has a docblock header. Match the existing
  voice (terse, references the Android mirror, explains *why*
  decisions were made — see the header on `CaptureMode.swift` as
  the template).
- Each new/changed file's docblock must end with `Mirror of
  Android <FileName>.kt.` — even when the mirror doesn't exist
  yet, since the parity contract is the header. File the Android
  ticket and link it in the PR description (spec §14).
- Spacing tokens come from `QuickInkSpacing`; radii from
  `QuickInkRadius`; colors from `QuickInkColors`. Don't hardcode.
- Coral shutter disc + white ring matches
  `DocumentCaptureSurface.documentShutterButton` — copy that
  exact shape, swap the SF Symbol to `camera.fill`.
- Haptics: `UIImpactFeedbackGenerator(style: .light)` on the
  Photo icon tap (matches the pill `tap` helper); `.medium` on
  the FAB long-press.
- The shutter must fire on tap, not on touch-down — match
  Document's `Button(action:)` model.

---

## Open questions (don't block on these)

These are flagged in the spec §15 but won't block the PR. Default
to my picks unless the reviewer wants otherwise:

1. **"Last photo" thumbnail in shutter row bottom-left** — skip
   in v1, leave the slot empty.
2. **Photo icon on Home screen too** — no, keep Home uncluttered.
3. **Library "Photo" pill for `source=photo`** — file a follow-up
   ticket; do not edit Library renderers in this PR.

---

## When you're done

Open a PR titled `Photo capture surface + long-press FAB entry`
with this checklist in the description:

```
Implements design/PHOTO_CAPTURE_SPEC.md.

- [x] CaptureMode.photo
- [x] PhotoCaptureSurface.swift
- [x] QuickCaptureScreen .photo dispatch + initialMode override
- [x] FAB long-press → Photo surface (no persist)
- [x] In-screen Photo icon on Document + Business Card surfaces
- [x] source="photo", paperSize=.custom on capture row
- [x] Voice-note pane → review pane unchanged
- [x] Manual test plan in handoff doc passed
- [ ] Android port (follow-up ticket: <link>)
- [ ] Library "Photo" pill (follow-up ticket: <link>)
```
