/*
 * PersonEditorView.swift
 *
 * Sheet for creating + editing people (Workspace home People
 * section). Supports an optional device-contact link — tapping
 * "Link contact" presents `CNContactPickerViewController`; on result
 * we cache the contact's display name, primary phone, primary email,
 * its CNContact `identifier` as `contactLookupKey`, and (if it has
 * one) the contact photo as an on-disk JPEG under AttachmentStorage.
 *
 * Mirror of Android's `PersonEditorDialog`. `CNContactPickerViewController`
 * doesn't require Info.plist's `NSContactsUsageDescription` since the
 * picker is mediated by the system — we never touch the contacts
 * store directly.
 */

import SwiftUI
import Contacts
import ContactsUI
import ReleafCoreData
import ReleafCoreDesignSystem

public enum PersonEditorMode: Identifiable {
    case create
    case edit(person: PersonEntity)

    public var id: String {
        switch self {
        case .create:                return "create"
        case .edit(let person):      return "edit:\(person.id)"
        }
    }
}

public struct PersonEditorView: View {

    public let mode: PersonEditorMode
    /// Called with name + phone + email + lookup key (CNContact id) +
    /// photo URI string. The last three are only set when the user
    /// linked a device contact; hand-typed phone/email keep
    /// lookupKey/photoUri nil.
    public let onSubmit: (
        _ name: String,
        _ phone: String?,
        _ email: String?,
        _ lookupKey: String?,
        _ photoUri: String?
    ) -> Void
    public let onCancel: () -> Void

    @State private var name: String
    @State private var phone: String
    @State private var email: String
    @State private var contactLookupKey: String?
    @State private var contactPhotoUri: String?

    @State private var presentingContactPicker = false
    @State private var statusMessage: String? = nil

    public init(
        mode: PersonEditorMode,
        onSubmit: @escaping (String, String?, String?, String?, String?) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.mode = mode
        self.onSubmit = onSubmit
        self.onCancel = onCancel
        switch mode {
        case .create:
            _name             = State(initialValue: "")
            _phone            = State(initialValue: "")
            _email            = State(initialValue: "")
            _contactLookupKey = State(initialValue: nil)
            _contactPhotoUri  = State(initialValue: nil)
        case .edit(let person):
            _name             = State(initialValue: person.name)
            _phone            = State(initialValue: person.contactPhone ?? "")
            _email            = State(initialValue: person.contactEmail ?? "")
            _contactLookupKey = State(initialValue: person.contactLookupKey)
            _contactPhotoUri  = State(initialValue: person.contactPhotoUri)
        }
    }

    private var title: String {
        switch mode {
        case .create:    return "New person"
        case .edit:      return "Edit person"
        }
    }

    private var canSave: Bool {
        !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var hasLink: Bool {
        contactLookupKey != nil
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s4) {
            HStack {
                Text(title)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundColor(QuickInkColors.ink)
                Spacer()
                Button("Cancel", action: onCancel)
                    .foregroundColor(QuickInkColors.muted)
            }

            VStack(alignment: .leading, spacing: 6) {
                Text("NAME")
                    .font(.system(size: 10.5, weight: .semibold))
                    .tracking(1.2)
                    .foregroundColor(QuickInkColors.muted)
                TextField("e.g. Mom, Dr. Rao", text: $name)
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()
            }

            if hasLink {
                linkedContactCard
            }

            Button(action: { presentingContactPicker = true }) {
                HStack(spacing: 8) {
                    Image(systemName: "person.crop.rectangle")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(QuickInkColors.inkSoft)
                    Text(hasLink ? "Pick a different contact" : "Link a contact")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(QuickInkColors.ink)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .background(QuickInkColors.borderSoft, in: RoundedRectangle(cornerRadius: 10))
                .overlay(
                    RoundedRectangle(cornerRadius: 10)
                        .stroke(QuickInkColors.border, lineWidth: 1)
                )
            }
            .buttonStyle(.plain)

            VStack(alignment: .leading, spacing: 6) {
                Text("PHONE (OPTIONAL)")
                    .font(.system(size: 10.5, weight: .semibold))
                    .tracking(1.2)
                    .foregroundColor(QuickInkColors.muted)
                TextField("+1 555 0100", text: $phone)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.phonePad)
            }

            VStack(alignment: .leading, spacing: 6) {
                Text("EMAIL (OPTIONAL)")
                    .font(.system(size: 10.5, weight: .semibold))
                    .tracking(1.2)
                    .foregroundColor(QuickInkColors.muted)
                TextField("name@example.com", text: $email)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.emailAddress)
                    .autocapitalization(.none)
            }

            if let statusMessage = statusMessage {
                Text(statusMessage)
                    .font(.system(size: 12))
                    .foregroundColor(QuickInkColors.muted)
            }

            Button(action: {
                onSubmit(
                    name.trimmingCharacters(in: .whitespacesAndNewlines),
                    phone.trimmingCharacters(in: .whitespacesAndNewlines),
                    email.trimmingCharacters(in: .whitespacesAndNewlines),
                    contactLookupKey,
                    contactPhotoUri
                )
            }) {
                Text("Save")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 13)
                    .background(canSave ? QuickInkColors.ink : QuickInkColors.muted,
                                in: RoundedRectangle(cornerRadius: 10))
            }
            .buttonStyle(.plain)
            .disabled(!canSave)
            Spacer()
        }
        .padding(AppSpacing.s4)
        .background(QuickInkColors.surface)
        .sheet(isPresented: $presentingContactPicker) {
            CNContactPickerView { contact in
                presentingContactPicker = false
                if let contact = contact {
                    applyPickedContact(contact)
                }
            }
            .ignoresSafeArea()
        }
    }

    private var linkedContactCard: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Image(systemName: "person.text.rectangle")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(QuickInkColors.accent)
                Text("Linked to device contact")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundColor(QuickInkColors.accent)
                Spacer()
                Button("Unlink") {
                    contactLookupKey = nil
                    contactPhotoUri = nil
                }
                .font(.system(size: 10.5, weight: .semibold))
                .tracking(1)
                .foregroundColor(QuickInkColors.muted)
                .buttonStyle(.plain)
            }
            let phoneTrim = phone.trimmingCharacters(in: .whitespaces)
            if !phoneTrim.isEmpty {
                HStack(spacing: 6) {
                    Image(systemName: "phone.fill")
                        .font(.system(size: 11))
                        .foregroundColor(QuickInkColors.accent.opacity(0.7))
                    Text(phoneTrim)
                        .font(.system(size: 12))
                        .foregroundColor(QuickInkColors.ink)
                }
            }
            let emailTrim = email.trimmingCharacters(in: .whitespaces)
            if !emailTrim.isEmpty {
                HStack(spacing: 6) {
                    Image(systemName: "envelope.fill")
                        .font(.system(size: 11))
                        .foregroundColor(QuickInkColors.accent.opacity(0.7))
                    Text(emailTrim)
                        .font(.system(size: 12))
                        .foregroundColor(QuickInkColors.ink)
                }
            }
        }
        .padding(AppSpacing.s3)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(QuickInkColors.accentSoft.opacity(0.45),
                    in: RoundedRectangle(cornerRadius: 10))
    }

    /// Resolve a `CNContact` from the picker into the editor's flat
    /// fields. Picks the first phone + first email (CNContact doesn't
    /// expose an `isPrimary` flag — the iOS Contacts app surfaces the
    /// user-preferred entry as the first labeled value).
    private func applyPickedContact(_ contact: CNContact) {
        contactLookupKey = contact.identifier
        let display = [contact.givenName, contact.familyName]
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        let displayFallback = display.isEmpty ? contact.organizationName : display
        if name.trimmingCharacters(in: .whitespaces).isEmpty, !displayFallback.isEmpty {
            name = displayFallback
        }
        if let primaryPhone = contact.phoneNumbers.first?.value.stringValue {
            phone = primaryPhone
        }
        if let primaryEmail = contact.emailAddresses.first?.value as String? {
            email = primaryEmail
        }
        if let imageData = contact.imageData,
           let url = AttachmentStorage.write(imageData, ext: "jpg") {
            contactPhotoUri = url.absoluteString
        } else if contact.imageData == nil {
            contactPhotoUri = nil
        }
        statusMessage = nil
    }
}

// MARK: - CNContactPicker bridge

private struct CNContactPickerView: UIViewControllerRepresentable {

    let onResult: (CNContact?) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onResult: onResult) }

    func makeUIViewController(context: Context) -> CNContactPickerViewController {
        let picker = CNContactPickerViewController()
        picker.delegate = context.coordinator
        picker.displayedPropertyKeys = [
            CNContactGivenNameKey,
            CNContactFamilyNameKey,
            CNContactOrganizationNameKey,
            CNContactPhoneNumbersKey,
            CNContactEmailAddressesKey,
            CNContactImageDataKey,
        ]
        return picker
    }

    func updateUIViewController(_ uiViewController: CNContactPickerViewController, context: Context) {}

    final class Coordinator: NSObject, CNContactPickerDelegate {
        let onResult: (CNContact?) -> Void
        init(onResult: @escaping (CNContact?) -> Void) {
            self.onResult = onResult
        }
        func contactPicker(_ picker: CNContactPickerViewController, didSelect contact: CNContact) {
            onResult(contact)
        }
        func contactPickerDidCancel(_ picker: CNContactPickerViewController) {
            onResult(nil)
        }
    }
}
