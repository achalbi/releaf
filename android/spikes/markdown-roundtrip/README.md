# Android Markdown round-trip spike

Proves that `commonmark-java` (display path) and `flexmark-java` (canonical
save path) agree on semantic meaning across one round-trip. See
`docs/MARKDOWN_EDITOR.md` for the rationale and round-trip policy.

## Run

```sh
gradle -p android/spikes/markdown-roundtrip run
```

Or open `android/spikes/markdown-roundtrip/` in IntelliJ IDEA / Android
Studio and run the `main()` function in `MarkdownRoundTrip.kt`.

## What it does

For each fixture, the spike asserts:

1. The HTML rendering of the source (via commonmark-java) matches the HTML
   rendering after flexmark-java round-trips the source through its AST
   and formatter — i.e. meaning is preserved.
2. flexmark's formatter is idempotent after one pass — repeated
   serialization stabilizes.

Exit 0 on full pass, 1 on any assertion failure.

## Fixture coverage

Matches the iOS spike for parity:

- Plain paragraphs
- All six heading levels
- Emphasis + strong (both `*` and `_` delimiter styles)
- Inline code + links
- Fenced code blocks + indented code blocks
- Blockquotes (nested)
- Unordered + ordered lists (nested)
- GFM task lists
- Horizontal rules
- Images
- HTML blocks
- UTF-8 edge cases (emoji, CJK, combining accents)
- GFM tables
- GFM strikethrough

## Why two libraries

`commonmark-java` is the lightweight reference parser + HTML renderer we
want in the production editor for display. It does not ship a markdown
formatter.

`flexmark-java` is a heavier CommonMark-compatible fork that DOES ship a
`Formatter` visitor capable of emitting canonical CommonMark from its AST.
We use it only on the save path, when the user's edits need to be
serialized back to on-disk bytes.

The spike's cross-library assertion is what makes the combo safe: the HTML
rendering of `commonmark-java.parse(original)` must match
`commonmark-java.parse(flexmark.format(flexmark.parse(original)))`. If
flexmark drifted from commonmark's spec understanding, this would fail.

## When to delete this

When the production editor lands in the main Android app and these
assertions are promoted to a `src/test/` suite, remove
`android/spikes/markdown-roundtrip/`. The spike exists only to bless the
library choice before we commit.
