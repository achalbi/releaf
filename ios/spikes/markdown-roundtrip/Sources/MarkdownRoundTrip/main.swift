//
// MarkdownRoundTrip — iOS spike for docs/MARKDOWN_EDITOR.md
//
// Proves:
//   1. Round-trip fixpoint: parse→format→parse produces a structurally
//      identical AST.
//   2. Idempotent format: format(format(ast)) == format(ast).
//   3. UTF-8 stability across multibyte characters (emoji, CJK, accents).
//
// Exits 0 on full pass, 1 on any assertion failure. Designed to be runnable
// as a CI gate if we later promote the spike to a regression suite.
//

import Foundation
import Markdown

// MARK: - Fixtures

private struct Fixture {
    let name: String
    let source: String
}

private let fixtures: [Fixture] = [
    Fixture(name: "plain-paragraph",
            source: "Hello, world.\n\nA second paragraph.\n"),
    Fixture(name: "headings",
            source: """
            # H1
            ## H2
            ### H3
            #### H4
            ##### H5
            ###### H6

            Body text after headings.
            """),
    Fixture(name: "emphasis-and-strong",
            source: "A *star em* and a _underscore em_. A **star strong** and a __underscore strong__.\n"),
    Fixture(name: "inline-code-and-links",
            source: "See `Document.init(parsing:)` and [swift-markdown](https://github.com/apple/swift-markdown).\n"),
    Fixture(name: "fenced-code-block",
            source: """
            ```swift
            let doc = Document(parsing: source)
            print(doc.format())
            ```
            """),
    Fixture(name: "indented-code-block",
            source: "    let x = 1\n    let y = 2\n"),
    Fixture(name: "blockquote-nested",
            source: """
            > A quote
            > > nested
            > back out
            """),
    Fixture(name: "unordered-list",
            source: """
            - first
            - second
              - nested
              - nested two
            - third
            """),
    Fixture(name: "ordered-list",
            source: """
            1. one
            2. two
            3. three
            """),
    Fixture(name: "gfm-task-list",
            source: """
            - [ ] pending
            - [x] done
            - [ ] another
            """),
    Fixture(name: "horizontal-rule",
            source: "before\n\n---\n\nafter\n"),
    Fixture(name: "image",
            source: "![alt text](https://example.com/img.png \"title\")\n"),
    Fixture(name: "html-block",
            source: "<div class=\"callout\">inline html</div>\n"),
    Fixture(name: "utf8-emoji-cjk-accents",
            source: "Emoji 🌿, CJK 日本語, accents café, combining a\u{0301}.\n"),
    Fixture(name: "gfm-table",
            source: """
            | H1 | H2 |
            | -- | -- |
            | a  | b  |
            | c  | d  |
            """),
    Fixture(name: "gfm-strikethrough",
            source: "Some ~~strikethrough~~ text.\n"),
]

// MARK: - AST comparison

/// Structural equality for two Markup trees.
///
/// Compares (1) the dynamic type chain, (2) textual content (for `Text`,
/// `CodeBlock`, `InlineCode`, `HTMLBlock`, `InlineHTML`), (3) attributes
/// that carry semantic info (link destinations, image URLs, heading levels,
/// list starts). Source locations and formatting-level attributes are
/// intentionally ignored — we only care that the *meaning* survives.
private func structurallyEqual(_ a: Markup, _ b: Markup) -> Bool {
    guard type(of: a) == type(of: b) else { return false }
    guard a.childCount == b.childCount else { return false }

    switch (a, b) {
    case (let h1 as Heading, let h2 as Heading):
        if h1.level != h2.level { return false }
    case (let l1 as Link, let l2 as Link):
        if l1.destination != l2.destination { return false }
        if l1.title != l2.title { return false }
    case (let i1 as Image, let i2 as Image):
        if i1.source != i2.source { return false }
        if i1.title != i2.title { return false }
    case (let c1 as CodeBlock, let c2 as CodeBlock):
        if c1.language != c2.language { return false }
        if c1.code != c2.code { return false }
    case (let c1 as InlineCode, let c2 as InlineCode):
        if c1.code != c2.code { return false }
    case (let t1 as Text, let t2 as Text):
        if t1.string != t2.string { return false }
    case (let h1 as HTMLBlock, let h2 as HTMLBlock):
        if h1.rawHTML != h2.rawHTML { return false }
    case (let h1 as InlineHTML, let h2 as InlineHTML):
        if h1.rawHTML != h2.rawHTML { return false }
    case (let l1 as OrderedList, let l2 as OrderedList):
        if l1.startIndex != l2.startIndex { return false }
    case (let li1 as ListItem, let li2 as ListItem):
        if li1.checkbox != li2.checkbox { return false }
    default:
        break
    }

    for (childA, childB) in zip(a.children, b.children) {
        if !structurallyEqual(childA, childB) { return false }
    }
    return true
}

// MARK: - Assertions

private var failureCount = 0

private func check(_ label: String, _ predicate: () -> Bool) {
    if predicate() {
        print("  ✓ \(label)")
    } else {
        failureCount += 1
        print("  ✗ \(label)")
    }
}

// MARK: - Runner

print("Releaf iOS — Markdown round-trip spike")
print("swift-markdown version: resolved by SwiftPM (see Package.resolved)")
print("")

for fixture in fixtures {
    print("[\(fixture.name)]")

    let ast0 = Document(parsing: fixture.source)
    let source1 = ast0.format()
    let ast1 = Document(parsing: source1)

    check("parse→format→parse preserves AST") {
        structurallyEqual(ast0, ast1)
    }

    let source2 = ast1.format()
    check("format is idempotent after one pass") {
        source1 == source2
    }

    // Byte-preserving no-op edit check: on a trivially reserialized AST,
    // a further parse+format cycle must not shift bytes.
    let ast2 = Document(parsing: source2)
    check("format(format(ast)) stabilizes") {
        ast2.format() == source2
    }
}

print("")
if failureCount == 0 {
    print("PASS — all round-trip assertions held.")
    exit(0)
} else {
    print("FAIL — \(failureCount) assertion(s) failed.")
    exit(1)
}
