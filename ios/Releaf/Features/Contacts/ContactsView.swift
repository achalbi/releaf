/*
 * ContactsView.swift
 *
 * Top-level Contacts surface on iOS. Shows the unified app
 * directory by default; as the user types, the search field also
 * surfaces device contacts (behind `CNContactStore` permission).
 */

import SwiftUI
import ReleafData
import ReleafDesignSystem

public struct ContactsView: View {
    @StateObject private var viewModel: ContactsViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var selectedContact: DirectoryContact?

    public init(userId: String) {
        _viewModel = StateObject(wrappedValue: ContactsViewModel(userId: userId))
    }

    public var body: some View {
        ZStack {
            AppColors.canvas.ignoresSafeArea()
            VStack(spacing: 0) {
                header
                searchField
                    .padding(.horizontal, AppSpacing.s4)
                    .padding(.bottom, AppSpacing.s3)
                content
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .hidesBottomBar()
        .task { viewModel.start() }
        .onDisappear { viewModel.stop() }
        .sheet(item: $selectedContact) { contact in
            ContactDetailSheet(
                contact: contact,
                onDismiss: { selectedContact = nil }
            )
            .presentationDetents([.medium])
        }
    }

    // MARK: - Header

    private var header: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            HStack {
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(AppColors.textPrimary)
                        .frame(width: 24, height: 24)
                }
                .buttonStyle(.plain)
                Spacer()
            }
            Text("CONTACTS")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.coral)
            Text("Your directory")
                .font(AppText.editorialTitle)
                .foregroundStyle(AppColors.textPrimary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, AppSpacing.s4)
        .padding(.top, AppSpacing.s3)
        .padding(.bottom, AppSpacing.s3)
    }

    // MARK: - Search field

    private var searchField: some View {
        HStack(spacing: AppSpacing.s2) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(AppColors.textSecondary)
            // Use the plain `TextField(_:text:)` initialiser — the
            // `prompt:` overload silently drops the placeholder on
            // iOS < 17 in some contexts, which is how the field
            // looked uneditable before.
            TextField(
                "Search contacts + phone book",
                text: Binding(
                    get: { viewModel.state.query },
                    set: { viewModel.updateQuery($0) }
                )
            )
            .textInputAutocapitalization(.sentences)
            .autocorrectionDisabled(true)
            .submitLabel(.search)
            .foregroundStyle(AppColors.textPrimary)
            .tint(AppColors.coral)
            if !viewModel.state.query.isEmpty {
                Button {
                    viewModel.clearQuery()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(AppColors.textSecondary)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, AppSpacing.s3)
        .padding(.vertical, AppSpacing.s3)
        .background(AppColors.inputBg)
        .clipShape(Capsule())
        .overlay(
            Capsule().stroke(AppColors.borderDefault, lineWidth: 1)
        )
    }

    // MARK: - Content

    @ViewBuilder private var content: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: AppSpacing.s3) {
                appSection
                if viewModel.state.isSearching {
                    deviceSection
                }
                Spacer(minLength: AppSpacing.s10)
            }
            .padding(.horizontal, AppSpacing.s4)
            .padding(.top, AppSpacing.s2)
        }
    }

    private var appSection: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            SectionHeader(
                title: viewModel.state.isSearching ? "In your notes" : "All contacts",
                subtitle: appSubtitle,
                badge: "\(viewModel.state.filteredAppContacts.count)"
            )
            if viewModel.state.isLoading {
                Text("Loading…")
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textTertiary)
            } else if viewModel.state.filteredAppContacts.isEmpty {
                EmptyCard(
                    title: viewModel.state.isSearching ? "No matches" : "No contacts yet",
                    subtitle: viewModel.state.isSearching
                        ? "Try searching by name, phone, or email."
                        : "Contacts you add to notes or pages will show up here."
                )
            } else {
                ForEach(viewModel.state.filteredAppContacts) { contact in
                    Button { selectedContact = contact } label: {
                        ContactRow(contact: contact)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private var appSubtitle: String {
        if viewModel.state.isSearching && viewModel.state.filteredAppContacts.isEmpty {
            return "No matches in notebooks or notepad."
        }
        if viewModel.state.isSearching {
            return "Matches from notebooks + notepad entries."
        }
        return "Everyone you've captured across notebooks and notepad."
    }

    private var deviceSection: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            SectionHeader(
                title: "Device contacts",
                subtitle: deviceSubtitle,
                badge: viewModel.state.devicePermissionGranted
                    ? "\(viewModel.state.deviceContacts.count)" : nil
            )
            if !viewModel.state.devicePermissionGranted {
                PermissionCta { viewModel.requestPermission() }
            } else if viewModel.state.deviceContacts.isEmpty {
                // Empty state is already conveyed by the subtitle.
                EmptyView()
            } else {
                ForEach(viewModel.state.deviceContacts) { contact in
                    Button { selectedContact = contact } label: {
                        ContactRow(contact: contact)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private var deviceSubtitle: String {
        if !viewModel.state.devicePermissionGranted {
            return "Let Releaf read your phone contacts to surface them here."
        }
        if viewModel.state.deviceContacts.isEmpty {
            return "No device matches for \u{201C}\(viewModel.state.query)\u{201D}."
        }
        return "From your phone's address book."
    }
}

// MARK: - Section header

private struct SectionHeader: View {
    let title: String
    let subtitle: String
    let badge: String?

    var body: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title.uppercased())
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(AppColors.coral)
                Text(subtitle)
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)
            }
            Spacer()
            if let badge {
                Text(badge)
                    .font(AppText.tag)
                    .foregroundStyle(AppColors.textPrimary)
                    .padding(.horizontal, AppSpacing.s3)
                    .padding(.vertical, 5)
                    .background(Capsule().fill(AppColors.neutralSoft))
            }
        }
    }
}

// MARK: - Contact row

private struct ContactRow: View {
    let contact: DirectoryContact

    var body: some View {
        HStack(spacing: AppSpacing.s3) {
            Avatar(contact: contact)
            VStack(alignment: .leading, spacing: 2) {
                Text(contact.name)
                    .font(AppText.sectionTitle)
                    .foregroundStyle(AppColors.textPrimary)
                if let org = contact.organization {
                    Text(org)
                        .font(AppText.meta)
                        .foregroundStyle(AppColors.textSecondary)
                }
                let meta = [contact.phone, contact.email].compactMap { $0 }.joined(separator: " · ")
                if !meta.isEmpty {
                    Text(meta)
                        .font(AppText.meta)
                        .foregroundStyle(AppColors.textSecondary)
                }
            }
            Spacer()
            trailingBadge
        }
        .padding(AppSpacing.s3)
        .background(AppColors.cardSolid)
        .clipShape(RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .stroke(AppColors.borderDefault, lineWidth: 1)
        )
    }

    @ViewBuilder
    private var trailingBadge: some View {
        if contact.source == .app && contact.appOccurrences > 0 {
            Text("\(contact.appOccurrences)×")
                .font(AppText.tag)
                .foregroundStyle(AppColors.greenText)
                .padding(.horizontal, AppSpacing.s3)
                .padding(.vertical, 4)
                .background(Capsule().fill(AppColors.successSoft))
        } else if contact.source == .device {
            Text("Phone")
                .font(AppText.tag)
                .foregroundStyle(AppColors.textSecondary)
                .padding(.horizontal, AppSpacing.s3)
                .padding(.vertical, 4)
                .background(Capsule().fill(AppColors.neutralSoft))
        }
    }
}

private struct Avatar: View {
    let contact: DirectoryContact

    var body: some View {
        let initial = contact.name.first.map { String($0).uppercased() } ?? "?"
        let isApp = contact.source == .app
        Circle()
            .fill(isApp ? AppColors.coralSoft : AppColors.neutralSoft)
            .frame(width: 40, height: 40)
            .overlay(
                Text(initial)
                    .font(AppText.sectionTitle)
                    .foregroundStyle(isApp ? AppColors.coral : AppColors.textPrimary)
            )
    }
}

// MARK: - Permission CTA

private struct PermissionCta: View {
    let onGrant: () -> Void

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: AppSpacing.s1) {
                Text("Show device contacts")
                    .font(AppText.sectionTitle)
                    .foregroundStyle(AppColors.textPrimary)
                Text("Releaf only reads them for this search session.")
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)
            }
            Spacer()
            Button(action: onGrant) {
                Text("Enable")
                    .font(AppText.button)
                    .foregroundStyle(AppColors.onPrimary)
                    .padding(.horizontal, AppSpacing.s4)
                    .padding(.vertical, AppSpacing.s3)
                    .background(Capsule().fill(AppColors.actionPrimary))
            }
            .buttonStyle(.plain)
        }
        .padding(AppSpacing.s4)
        .background(AppColors.cardSolid)
        .clipShape(RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .stroke(AppColors.borderDefault, lineWidth: 1)
        )
    }
}

// MARK: - Empty state

private struct EmptyCard: View {
    let title: String
    let subtitle: String

    var body: some View {
        VStack(spacing: AppSpacing.s1) {
            Text(title)
                .font(AppText.sectionTitle)
                .foregroundStyle(AppColors.textPrimary)
            Text(subtitle)
                .font(AppText.meta)
                .foregroundStyle(AppColors.textTertiary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(AppSpacing.s6)
        .background(AppColors.cardSolid)
        .clipShape(RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .stroke(AppColors.borderDefault, lineWidth: 1)
        )
    }
}

// MARK: - Detail sheet

private struct ContactDetailSheet: View {
    let contact: DirectoryContact
    let onDismiss: () -> Void
    @Environment(\.openURL) private var openURL

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: AppSpacing.s4) {
                    header
                    if let org = contact.organization {
                        DetailField(label: "Organization", value: org)
                    }
                    if let phone = contact.phone {
                        DetailField(label: "Phone", value: phone)
                    }
                    if let email = contact.email {
                        DetailField(label: "Email", value: email)
                    }
                    if contact.source == .app && contact.appOccurrences > 0 {
                        Text(
                            "Captured in \(contact.appOccurrences) " +
                            (contact.appOccurrences == 1 ? "note." : "notes.")
                        )
                        .font(AppText.meta)
                        .foregroundStyle(AppColors.textTertiary)
                    }

                    actionRow
                }
                .padding(AppSpacing.s5)
            }
            .background(AppColors.canvas)
            .navigationTitle(contact.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Close", action: onDismiss)
                }
            }
        }
    }

    private var header: some View {
        HStack(spacing: AppSpacing.s3) {
            let initial = contact.name.first.map { String($0).uppercased() } ?? "?"
            Circle()
                .fill(contact.source == .app ? AppColors.coralSoft : AppColors.neutralSoft)
                .frame(width: 56, height: 56)
                .overlay(
                    Text(initial)
                        .font(AppText.sectionTitle)
                        .foregroundStyle(
                            contact.source == .app ? AppColors.coral : AppColors.textPrimary
                        )
                )
            VStack(alignment: .leading, spacing: 2) {
                Text(contact.name)
                    .font(AppText.editorialTitle)
                    .foregroundStyle(AppColors.textPrimary)
                Text(contact.source == .app ? "In your notes" : "Device contact")
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)
            }
        }
    }

    @ViewBuilder
    private var actionRow: some View {
        HStack(spacing: AppSpacing.s3) {
            if let phone = contact.phone {
                actionButton(label: "Call", icon: "phone.fill") {
                    if let url = URL(string: "tel:\(phone.replacingOccurrences(of: " ", with: ""))") {
                        openURL(url)
                    }
                }
            }
            if let email = contact.email {
                actionButton(label: "Email", icon: "envelope.fill") {
                    if let url = URL(string: "mailto:\(email)") {
                        openURL(url)
                    }
                }
            }
        }
    }

    private func actionButton(
        label: String,
        icon: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: AppSpacing.s2) {
                Image(systemName: icon)
                Text(label)
                    .font(AppText.button)
            }
            .foregroundStyle(AppColors.onPrimary)
            .padding(.horizontal, AppSpacing.s4)
            .padding(.vertical, AppSpacing.s3)
            .frame(maxWidth: .infinity)
            .background(Capsule().fill(AppColors.actionPrimary))
        }
        .buttonStyle(.plain)
    }
}

private struct DetailField: View {
    let label: String
    let value: String
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label.uppercased())
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.textSecondary)
            Text(value)
                .font(AppText.body)
                .foregroundStyle(AppColors.textPrimary)
        }
    }
}
