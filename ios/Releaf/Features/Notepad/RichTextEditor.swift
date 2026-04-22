/*
 * RichTextEditor.swift
 *
 * WYSIWYG markdown editor for iOS. SwiftUI's `TextEditor` has no rich-
 * text support, so we bridge to `UITextView` via
 * `UIViewRepresentable` and hold formatting as `NSAttributedString`
 * attributes. Markdown round-trip:
 *
 *   - LOAD: `AttributedString(markdown:)` with `.inlineOnlyPreservingWhitespace`.
 *     Inline styles (**bold**, *italic*, `code`, [link](url)) become
 *     attributed runs. Block-level syntax (`# heading`, `- bullet`) is
 *     preserved as literal text.
 *   - SAVE: walk the NSAttributedString runs and emit `**text**` /
 *     `*text*` for bold/italic runs. Everything else passes through as
 *     plain characters — so literal `#` and `-` the user typed survive.
 *
 * Underline has no clean markdown equivalent and is lost on save.
 * Headings/lists/code are still typeable but aren't surfaced on the
 * toolbar; that matches the Android toolbar's inline-only actions.
 *
 * Controller pattern: a separate `RichTextEditorController` holds a
 * weak reference to the UITextView. The SwiftUI toolbar talks to the
 * controller (toggleBold, toggleItalic, toggleUnderline); the
 * controller mutates the attributed text directly and refreshes the
 * `isXActive` flags the toolbar highlights.
 */

import SwiftUI
import UIKit

// MARK: - Controller

@MainActor
final class RichTextEditorController: ObservableObject {

    /// Set by the RichTextEditor's `makeUIView`. We keep it weak so the
    /// controller can live longer than the view without preventing
    /// deallocation.
    weak var textView: UITextView?

    @Published private(set) var isBoldActive: Bool = false
    @Published private(set) var isItalicActive: Bool = false
    @Published private(set) var isUnderlineActive: Bool = false

    func toggleBold()      { toggleTrait(.traitBold) }
    func toggleItalic()    { toggleTrait(.traitItalic) }

    func toggleUnderline() {
        guard let tv = textView else { return }
        let range = tv.selectedRange
        guard range.length > 0 else { return }
        let mutable = NSMutableAttributedString(attributedString: tv.attributedText)
        let currentlyUnderlined = isUnderlined(in: tv.attributedText, at: range.location)
        if currentlyUnderlined {
            mutable.removeAttribute(.underlineStyle, range: range)
        } else {
            mutable.addAttribute(
                .underlineStyle,
                value: NSUnderlineStyle.single.rawValue,
                range: range
            )
        }
        tv.attributedText = mutable
        tv.selectedRange = range
        // UITextView doesn't fire `textViewDidChange` for attribute-only
        // mutations, so prod the SwiftUI side manually.
        tv.delegate?.textViewDidChange?(tv)
        refreshActiveStyles()
    }

    /// Reads the current selection (or cursor position) and updates the
    /// `isXActive` flags so the toolbar can highlight the buttons
    /// matching whatever's under the caret.
    func refreshActiveStyles() {
        guard let tv = textView, tv.attributedText.length > 0 else {
            isBoldActive = false
            isItalicActive = false
            isUnderlineActive = false
            return
        }
        // Use the start of the selection (or the cursor position if
        // nothing is selected) as the probe point. Clamp inside the
        // document so we don't index-out-of-bounds on an empty buffer.
        let probe = min(
            max(tv.selectedRange.location, 0),
            max(tv.attributedText.length - 1, 0)
        )
        let attrs = tv.attributedText.attributes(at: probe, effectiveRange: nil)
        let font = (attrs[.font] as? UIFont) ?? UIFont.preferredFont(forTextStyle: .body)
        let traits = font.fontDescriptor.symbolicTraits
        isBoldActive      = traits.contains(.traitBold)
        isItalicActive    = traits.contains(.traitItalic)
        isUnderlineActive = (attrs[.underlineStyle] as? Int) ?? 0 != 0
    }

    // MARK: Private

    private func toggleTrait(_ trait: UIFontDescriptor.SymbolicTraits) {
        guard let tv = textView else { return }
        let range = tv.selectedRange
        guard range.length > 0 else { return }
        let mutable = NSMutableAttributedString(attributedString: tv.attributedText)
        mutable.enumerateAttribute(.font, in: range, options: []) { value, subRange, _ in
            let current = (value as? UIFont) ?? UIFont.preferredFont(forTextStyle: .body)
            let traits = current.fontDescriptor.symbolicTraits
            let newTraits = traits.contains(trait)
                ? traits.subtracting(trait)
                : traits.union(trait)
            if let newDescriptor = current.fontDescriptor.withSymbolicTraits(newTraits) {
                let newFont = UIFont(descriptor: newDescriptor, size: current.pointSize)
                mutable.addAttribute(.font, value: newFont, range: subRange)
            }
        }
        tv.attributedText = mutable
        tv.selectedRange = range
        tv.delegate?.textViewDidChange?(tv)
        refreshActiveStyles()
    }

    private func isUnderlined(in attr: NSAttributedString, at location: Int) -> Bool {
        guard location < attr.length else { return false }
        let attrs = attr.attributes(at: location, effectiveRange: nil)
        return (attrs[.underlineStyle] as? Int) ?? 0 != 0
    }
}

// MARK: - Editor view

struct RichTextEditor: UIViewRepresentable {
    @Binding var markdown: String
    @ObservedObject var controller: RichTextEditorController
    var tintColor: UIColor
    /// When `true`, the UITextView scrolls its own content and should NOT
    /// be nested in a SwiftUI ScrollView. When `false` (default), the
    /// UITextView expands to content height and the caller owns
    /// scrolling via an outer ScrollView. The sheet variant uses
    /// `true` — nesting in a SwiftUI ScrollView was stealing the
    /// double-tap-to-select gesture from UITextView.
    var isScrollEnabled: Bool = false

    func makeUIView(context: Context) -> UITextView {
        let tv = UITextView()
        tv.delegate = context.coordinator
        tv.font = UIFont.preferredFont(forTextStyle: .body)
        tv.backgroundColor = .clear
        // Remove the default padding so the text aligns flush with the
        // surrounding SwiftUI padding the caller applies.
        tv.textContainerInset = .zero
        tv.textContainer.lineFragmentPadding = 0
        tv.tintColor = tintColor
        tv.isScrollEnabled = isScrollEnabled
        tv.attributedText = MarkdownBridge.parse(markdown)
        controller.textView = tv
        return tv
    }

    func updateUIView(_ tv: UITextView, context: Context) {
        // Only rehydrate on external binding changes. If the coordinator
        // is the one pushing the update (user typed), skip — otherwise
        // we'd reparse markdown mid-edit and blow away the selection.
        if !context.coordinator.isInternalUpdate {
            let newAttr = MarkdownBridge.parse(markdown)
            if newAttr.string != tv.attributedText.string {
                let sel = tv.selectedRange
                tv.attributedText = newAttr
                tv.selectedRange = sel
            }
        }
        if tv.tintColor != tintColor {
            tv.tintColor = tintColor
        }
        if tv.isScrollEnabled != isScrollEnabled {
            tv.isScrollEnabled = isScrollEnabled
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }

    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: RichTextEditor
        /// Set to true just before we write into `markdown`, cleared on
        /// the next runloop. Keeps `updateUIView` from treating the
        /// self-originated binding change as an external one.
        var isInternalUpdate: Bool = false

        init(parent: RichTextEditor) { self.parent = parent }

        func textViewDidChange(_ textView: UITextView) {
            isInternalUpdate = true
            parent.markdown = MarkdownBridge.serialize(textView.attributedText)
            DispatchQueue.main.async { [weak self] in
                self?.isInternalUpdate = false
            }
            parent.controller.refreshActiveStyles()
        }

        func textViewDidChangeSelection(_ textView: UITextView) {
            parent.controller.refreshActiveStyles()
        }
    }
}

// MARK: - Markdown bridge

/// Load + save helpers for the plain-string `markdown` binding. Kept
/// internal to the editor file — nothing else in the project parses
/// markdown this way today.
enum MarkdownBridge {

    /// Parse inline markdown into an NSAttributedString. Block-level
    /// syntax stays as literal text.
    static func parse(_ md: String) -> NSAttributedString {
        var options = AttributedString.MarkdownParsingOptions()
        options.interpretedSyntax = .inlineOnlyPreservingWhitespace
        if let parsed = try? AttributedString(markdown: md, options: options) {
            return NSAttributedString(parsed)
        }
        return NSAttributedString(string: md)
    }

    /// Walk the attributed string's runs and emit markdown. Bold and
    /// italic are re-wrapped in `**…**` / `*…*`; everything else passes
    /// through as the plain substring (so literal `#`, `-`, backticks,
    /// etc. survive unchanged).
    static func serialize(_ attr: NSAttributedString) -> String {
        if attr.length == 0 { return "" }
        var result = ""
        let fullRange = NSRange(location: 0, length: attr.length)
        attr.enumerateAttributes(in: fullRange, options: []) { attrs, range, _ in
            guard range.length > 0 else { return }
            let chunk = (attr.string as NSString).substring(with: range)
            let font = (attrs[.font] as? UIFont) ?? UIFont.preferredFont(forTextStyle: .body)
            let traits = font.fontDescriptor.symbolicTraits
            let bold   = traits.contains(.traitBold)
            let italic = traits.contains(.traitItalic)
            switch (bold, italic) {
            case (true, true):   result += "***\(chunk)***"
            case (true, false):  result += "**\(chunk)**"
            case (false, true):  result += "*\(chunk)*"
            case (false, false): result += chunk
            }
        }
        return result
    }
}
