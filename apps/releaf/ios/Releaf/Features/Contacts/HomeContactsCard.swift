/*
 * HomeContactsCard.swift
 *
 * Compact Contacts card for the signed-in Home dashboard. Shows
 * the total count + the first few contact names, and links out to
 * the full Contacts screen.
 */

import SwiftUI
import ReleafData
import ReleafDesignSystem

public struct HomeContactsCard: View {
    @EnvironmentObject private var authStore: AuthStore
    @State private var contacts: [DirectoryContact] = []
    @State private var streamTask: Task<Void, Never>?

    private let repository: ContactDirectoryRepository
    private let onOpenContacts: () -> Void

    public init(
        onOpenContacts: @escaping () -> Void,
        repository: ContactDirectoryRepository = ContactDirectoryRepository()
    ) {
        self.onOpenContacts = onOpenContacts
        self.repository = repository
    }

    public var body: some View {
        let preview = Array(contacts.prefix(3))
        Button(action: onOpenContacts) {
            HStack(spacing: AppSpacing.s3) {
                RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                    .fill(Color(hex: 0xF2E7DB))
                    .frame(width: 56, height: 56)
                    .overlay(
                        Image(systemName: "person.2.fill")
                            .foregroundStyle(AppColors.coral)
                    )

                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text("CONTACTS")
                            .font(AppText.eyebrow)
                            .tracking(AppLetterSpacing.eyebrow)
                            .foregroundStyle(AppColors.coral)
                        Spacer()
                        Text("\(contacts.count)")
                            .font(AppText.tag)
                            .foregroundStyle(AppColors.textSecondary)
                    }
                    Text(headline)
                        .font(AppText.sectionTitle)
                        .foregroundStyle(AppColors.textPrimary)
                    Text(previewLine(preview))
                        .font(AppText.meta)
                        .foregroundStyle(AppColors.textSecondary)
                        .lineLimit(1)

                    if !preview.isEmpty {
                        HStack(spacing: -8) {
                            ForEach(preview) { contact in
                                MiniAvatar(
                                    initial: contact.name.first.map { String($0).uppercased() } ?? "?"
                                )
                            }
                            if contacts.count > preview.count {
                                Text("+ \(contacts.count - preview.count) more")
                                    .font(AppText.meta)
                                    .foregroundStyle(AppColors.textTertiary)
                                    .padding(.leading, AppSpacing.s2)
                            }
                        }
                        .padding(.top, AppSpacing.s2)
                    }
                }
            }
            .padding(AppSpacing.s4)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(AppColors.cardSolid)
            .clipShape(RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                    .stroke(AppColors.borderDefault, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .task { startStream() }
        .onDisappear {
            streamTask?.cancel()
            streamTask = nil
        }
    }

    private var headline: String {
        switch contacts.count {
        case 0:  return "Nothing captured yet"
        case 1:  return "1 contact"
        default: return "\(contacts.count) contacts"
        }
    }

    private func previewLine(_ preview: [DirectoryContact]) -> String {
        if preview.isEmpty {
            return "Add a contact to a page or notepad entry — it'll land here."
        }
        let names = preview.map { $0.name }.joined(separator: ", ")
        return contacts.count > preview.count ? "\(names)…" : names
    }

    private func startStream() {
        streamTask?.cancel()
        guard let userId = authStore.session?.userId, !userId.isEmpty else { return }
        streamTask = Task { [repository] in
            do {
                for try await all in repository.observeAll(userId: userId) {
                    await MainActor.run { contacts = all }
                }
            } catch {}
        }
    }
}

private struct MiniAvatar: View {
    let initial: String
    var body: some View {
        Circle()
            .fill(AppColors.coralSoft)
            .frame(width: 26, height: 26)
            .overlay(
                Circle().stroke(AppColors.cardSolid, lineWidth: 1.5)
            )
            .overlay(
                Text(initial)
                    .font(AppText.tag)
                    .foregroundStyle(AppColors.coral)
            )
    }
}
