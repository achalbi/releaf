# iOS Markdown round-trip spike

Proves that `swift-markdown` (Apple) can round-trip CommonMark source
without semantic drift. See `docs/MARKDOWN_EDITOR.md` for the rationale and
round-trip policy.

## Run

```sh
swift run --package-path ios/spikes/markdown-roundtrip
```

Or open `Package.swift` in Xcode and press ⌘R.

## What it does

For each fixture, the spike asserts:

1. `parse(source) → format → parse` produces an AST structurally equal to
   the original parse.
2. `format` is idempotent after one pass — repeated serialization stabilizes.
3. The normalized source is byte-stable across a second round-trip.

Exit 0 on full pass, 1 on any assertion failure.

## Fixture coverage

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

## Toolchain note

This spike (and the main `ios/Package.swift`) both require a Swift
toolchain where the PackageDescription manifest library links cleanly. On
machines running only `Command Line Tools` (not full Xcode), some CLTs
releases ship a PackageDescription dylib that's out of sync with the Swift
compiler and fails with `Undefined symbols: PackageDescription.Package.__allocating_init`
at manifest compile time. Fix by installing Xcode or reinstalling an
up-to-date CLTs (`sudo xcode-select --install`).

The spike code is independent of this infra issue — once the toolchain
links, `swift run` executes the assertions.

## When to delete this

When the production editor lands in `ReleafFeatures` and these assertions
are promoted to `XCTestCase` targets, remove `ios/spikes/markdown-roundtrip/`.
The spike exists only to bless the library choice before we commit.
