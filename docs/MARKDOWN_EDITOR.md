# Markdown Editor — design + spike

Status: Spike. Not wired into either app target. The spikes under
`ios/spikes/markdown-roundtrip/` and `android/spikes/markdown-roundtrip/` exist
only to validate library choice and round-trip behavior before we commit to an
editor implementation in v2.

## Scope

`notes` fields across Releaf (Notebook pages, Notepad entries, Daily Log
entries, Task descriptions, Capture memos, Reference Link blurbs) are stored
as **canonical CommonMark** (GFM subset — tables, strikethrough, task-list
items). All schema-level agreement around this lives in
`design-system/migrations/v1_initial.sql` (`notes` columns are `TEXT` with
CommonMark content, nothing richer).

The editor needs to:

1. **Preserve everything the user typed.** No lossy parse+render cycle that
   silently rewrites formatting the user prefers (e.g. don't rewrite `*em*` to
   `_em_` behind their back on every save).
2. **Render a live preview.** Inline or split — TBD. The spike doesn't lock it
   in, but `docs/NAV_GRAPH.md`'s Notepad and Daily Log flows both assume
   preview is readily reachable.
3. **Round-trip the AST.** After one serialize pass, re-parsing MUST produce
   an AST that's structurally equal to the first parse's AST. This is weaker
   than byte-exact idempotence (CommonMark permits `*x*` ≡ `_x_`) but strong
   enough to guarantee no semantic drift.
4. **Stay the same on both platforms.** Same storage bytes, same render,
   same behavior when the Drive manifest moves notes between a Mac-edited
   Notebook and the mobile apps (see `docs/DRIVE_SCHEMA.md`).

Out of scope for the spike: wikilinks, custom directives, citation syntax,
inline LaTeX. We can layer those on top once the core engine is chosen.

## Library selection

### iOS — `swift-markdown` (Apple)

- **Package:** `https://github.com/apple/swift-markdown`
- **Why:** First-party Apple Swift package. Stable since 2022. Uses the
  `cmark-gfm` C library under the hood (same parser as GitHub), exposes a
  high-level Swift AST (`Document`, `Paragraph`, `Heading`, `Emphasis`, etc.)
  via a `Markup` protocol with a visitor pattern (`MarkupVisitor`,
  `MarkupWalker`, `MarkupRewriter`).
- **Round-trip:** Ships `MarkupFormatter`, a `MarkupWalker` that emits
  normalized CommonMark. Output is deterministic — running it twice is
  idempotent, and re-parsing produces an identical AST.
- **Render for preview:** Either walk the AST and build `AttributedString`
  manually, or call `AttributedString(markdown:)` for quick previews during
  development. `AttributedString(markdown:)` is lossy (drops tables, drops
  footnotes, drops task list items) — acceptable only for display.
- **Editing UX:** The spike validates the engine. The editor surface itself
  would be a `UITextView` backed by `NSAttributedString`, with an
  input-accessory toolbar for block insertions (heading, list, etc.) and a
  cursor position that maps source offsets to AST nodes via `SourceLocation`.

### Android — `commonmark-java` + `flexmark-java` (for round-trip)

- **Primary parser:** `org.commonmark:commonmark:0.21.0` — Atlassian's
  reference CommonMark implementation. Lightweight (~300 KB), used by
  Markwon and many Atlassian products.
- **Formatter (round-trip):** `com.vladsch.flexmark:flexmark-all:0.64.8` —
  a CommonMark-compatible fork that ships a `Formatter` visitor which emits
  canonical CommonMark from an AST. The spike uses flexmark-java for the
  round-trip step; the production editor can use flexmark for both
  parse+format or keep commonmark-java for parsing and use flexmark only when
  serializing edits back.
- **Why not Markwon:** Markwon is display-only. It renders CommonMark to
  `Spanned` for TextView, but can't produce markdown source from the AST.
  We'd still need commonmark-java (which Markwon wraps) for editing.
- **Render for preview:** `commonmark-java`'s `HtmlRenderer` for a WebView
  preview, or a custom `AbstractVisitor` that builds an
  `AnnotatedString` for Compose Text. For the editor itself, Compose's
  `BasicTextField2` + a `VisualTransformation` that applies styling runs
  over the underlying source.
- **Editing UX:** Same shape as iOS — a text field over the raw source plus
  a companion toolbar/preview. `VisualTransformation` is powerful enough to
  apply headings/emphasis styling without mutating the source.

### Why not one cross-platform core?

We considered embedding `cmark-gfm` as a C library on both sides (iOS
exposes it directly; Android via NDK). Rejected because:

- Two native builds per CI matrix (Intel/ARM × simulator/device × NDK
  ABIs) — high maintenance overhead for a single feature.
- Both chosen platform libraries already wrap cmark-gfm semantically (Apple's
  swift-markdown is literally a Swift wrapper around cmark-gfm; commonmark-java
  is a faithful re-implementation with the same spec conformance).
- Storage is plain UTF-8 markdown in both SQLite and Drive. Interop works
  because the *format* is standardized, not because we ship one parser.

## Round-trip policy

The contract both spikes enforce:

```
parse(source)                  → AST₀
format(AST₀)                   → source'
parse(source')                 → AST₁
assert structurallyEqual(AST₀, AST₁)
```

This is the "CommonMark-normalized fixpoint" — after one round of
parse+format, further round-trips are no-ops. `source` and `source'` may
differ in whitespace/delimiter choices, but the meaning is preserved.

For *editing* (not saving), we apply a stronger rule: **never mutate the
source on a no-op edit.** If the user opens a note and saves without changing
anything, the bytes-on-disk stay byte-identical. Implementation detail:
capture the source on load, diff against the current editor state on save,
and only persist if the diff is non-empty. This keeps Drive sync quiet and
keeps the user's preferred style (e.g. `_em_` vs `*em*`) stable across
sessions.

The format stage *is* applied when the user actually changes anything. That
guarantees both apps agree on how newly-typed markdown is persisted —
mechanical edits from either platform produce identical on-disk bytes.

## What the spikes prove

1. **Round-trip fixpoint** — the AST-equality assertion above holds for a
   corpus of fixtures covering headings, paragraphs, emphasis, strong, links,
   images, inline code, code blocks (fenced + indented), blockquotes, lists
   (ordered/unordered/nested/task), horizontal rules, and HTML blocks.
2. **Idempotent format** — `format(format(AST))` == `format(AST)` after one
   pass (no multi-pass convergence needed).
3. **UTF-8 stability** — multibyte runes survive (tested with emoji, CJK,
   combining accents).

The spikes print a per-fixture report and a PASS/FAIL summary. They return
non-zero on any assertion failure so they can be wired into CI if we decide
to keep them as engine regression tests once the editor ships.

## Running the spikes

```sh
# iOS
swift run --package-path ios/spikes/markdown-roundtrip

# Android
./android/spikes/markdown-roundtrip/gradlew -p android/spikes/markdown-roundtrip run
```

Both spikes are self-contained — they do not depend on the main app targets
and cannot accidentally leak into shipping code. Delete the directories when
the v2 editor is implemented if the assertions get promoted to proper test
targets.

## Next steps (not this spike)

- Decide preview mode: split-screen (iPad, landscape), inline swap (phone),
  or "eye" toggle. `docs/NAV_GRAPH.md` flagged this as TBD.
- Decide input-accessory toolbar affordances: heading cycle, bold/italic
  toggles, list toggles, link inserter, image inserter, code block.
- Wire the editor to the soft-delete + dirty-flag plumbing (see
  `v1_initial.sql`).
- Wire attachments through to the flat `media/` layout in Drive.
- Revisit after a week of dogfooding — the spike proves feasibility, not UX.

## Open questions

- **Wikilinks (`[[page]]`) for cross-notebook refs.** Not in CommonMark. If
  we want them, pick a parser plugin approach (swift-markdown doesn't support
  plugins yet — we'd have to post-process; flexmark has a `WikiLink`
  extension). Deferred until the Daily Log ↔ Notebook linking story lands.
- **Task list persistence vs rendering.** GFM task list items are stored as
  `- [ ] text` / `- [x] text`. The editor needs to let users tap the checkbox
  in preview and update the source — round-trip still holds because we edit
  the source directly, not the AST.
- **Paste handling.** Rich text pasted from Safari/Chrome: do we convert
  HTML to markdown (`turndown`-style) or paste plain text and let the user
  format? Recommendation: plain text paste, toolbar for formatting. Revisit
  after dogfooding.
