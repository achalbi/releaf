/*
 * AddContactReviewSheet.swift
 *
 * SwiftUI sheet that shows the [BusinessCardExtractor] output as
 * editable form fields before the user hands the result to the
 * system contact-create UI. Mirror of Android's
 * `AddContactReviewSheet.kt` — same fields, same edit semantics.
 *
 * On Save we hand the (possibly-edited) values back through
 * `onConfirm` and the parent presents `AddContactSheet`. On Cancel
 * we just dismiss — nothing persists.
 */

import SwiftUI
import ReleafCoreScan

/// Editable representation of the extracted contact. Multi-value
/// fields collapse to comma-separated strings so the user can edit
/// them inline; the launcher splits them back out before firing
/// the system contact form.
struct EditableContact: Equatable {
    var name: String
    var designation: String
    var company: String
    var phones: String
    var emails: String
    var websites: String
    var address: String

    static func from(_ c: ExtractedContact) -> EditableContact {
        EditableContact(
            name:        c.name        ?? "",
            designation: c.designation ?? "",
            company:     c.company     ?? "",
            phones:      c.phones.joined(separator: ", "),
            emails:      c.emails.joined(separator: ", "),
            websites:    c.websites.joined(separator: ", "),
            address:     c.address     ?? ""
        )
    }
}

struct AddContactReviewSheet: View {

    let extracted: ExtractedContact
    let onCancel: () -> Void
    let onConfirm: (EditableContact) -> Void

    @State private var form: EditableContact

    init(
        extracted: ExtractedContact,
        onCancel: @escaping () -> Void,
        onConfirm: @escaping (EditableContact) -> Void
    ) {
        self.extracted = extracted
        self.onCancel  = onCancel
        self.onConfirm = onConfirm
        self._form = State(initialValue: EditableContact.from(extracted))
    }

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: QuickInkSpacing.s4) {
                    Text("We pulled the fields below from the scan. Edit any that look wrong, then save to add to your contacts.")
                        .font(QuickInkText.meta)
                        .foregroundStyle(QuickInkColors.inkSoft)

                    confidencePill

                    fieldEditor("Name",        text: $form.name)
                    fieldEditor("Designation", text: $form.designation)
                    fieldEditor("Company",     text: $form.company)
                    fieldEditor("Phones",      text: $form.phones,   hint: "Comma-separated")
                    fieldEditor("Emails",      text: $form.emails,   hint: "Comma-separated")
                    fieldEditor("Websites",    text: $form.websites, hint: "Comma-separated")
                    fieldEditor("Address",     text: $form.address, multiLine: true)
                }
                .padding(QuickInkSpacing.s5)
            }
            .background(QuickInkColors.bg.ignoresSafeArea())
            .navigationTitle("Review contact")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onCancel)
                        .foregroundStyle(QuickInkColors.muted)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { onConfirm(form) }
                        .foregroundStyle(QuickInkColors.accent)
                }
            }
        }
    }

    @ViewBuilder
    private var confidencePill: some View {
        let (label, tint): (String, Color) = {
            let c = extracted.confidence
            if c >= 0.7 { return ("High confidence",   QuickInkColors.success) }
            if c >= 0.4 { return ("Medium confidence", QuickInkColors.warning) }
            return       ("Low confidence — please review", QuickInkColors.danger)
        }()
        VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
            HStack {
                Text(label)
                    .font(QuickInkText.caption)
                    .foregroundStyle(tint)
                Spacer()
                Text("\(Int(extracted.confidence * 100))%")
                    .font(QuickInkText.caption)
                    .foregroundStyle(QuickInkColors.inkSoft)
            }
            ProgressView(value: extracted.confidence)
                .tint(tint)
        }
        .padding(QuickInkSpacing.s3)
        .background(QuickInkColors.borderSoft)
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous))
    }

    @ViewBuilder
    private func fieldEditor(
        _ label: String,
        text: Binding<String>,
        hint: String? = nil,
        multiLine: Bool = false
    ) -> some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s1) {
            Text(label.uppercased())
                .font(QuickInkText.eyebrow)
                .tracking(QuickInkLetterSpacing.eyebrow)
                .foregroundStyle(QuickInkColors.muted)
            if multiLine {
                TextEditor(text: text)
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.ink)
                    .frame(minHeight: 80)
                    .padding(QuickInkSpacing.s2)
                    .background(QuickInkColors.surface)
                    .overlay(
                        RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous)
                            .stroke(QuickInkColors.border, lineWidth: 1)
                    )
                    .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous))
            } else {
                TextField(hint ?? "", text: text)
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.ink)
                    .padding(QuickInkSpacing.s3)
                    .background(QuickInkColors.surface)
                    .overlay(
                        RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous)
                            .stroke(QuickInkColors.border, lineWidth: 1)
                    )
                    .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous))
            }
        }
    }
}
