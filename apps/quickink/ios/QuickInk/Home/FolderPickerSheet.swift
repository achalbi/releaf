/*
 * FolderPickerSheet.swift
 *
 * SwiftUI bottom sheet that lists the user's active folders and
 * picks one for a single capture. iOS B.2 — move-capture-to-folder.
 *
 * Used from ScanDetailScreen's Actions card; reusable anywhere a
 * "move this capture" affordance lands later. Writes via
 * `CaptureRepository.setFolder` directly — no intermediate
 * repository layer.
 *
 * Mirror of `FolderPickerSheet.kt` in QuickInk's Android target.
 */

import SwiftUI
import Combine
import GRDB
import ReleafCoreDesignSystem

@MainActor
public struct FolderPickerSheet: View {

    public let userId: String
    /// Folder the capture is currently in; the matching row shows
    /// a check.
    public let currentFolderId: String?
    public let onPickFolder: (FolderEntity) -> Void
    public let onDismiss: () -> Void

    @State private var folders: [FolderEntity] = []
    @State private var cancellable: AnyCancellable? = nil

    public init(
        userId: String,
        currentFolderId: String?,
        onPickFolder: @escaping (FolderEntity) -> Void,
        onDismiss: @escaping () -> Void
    ) {
        self.userId = userId
        self.currentFolderId = currentFolderId
        self.onPickFolder = onPickFolder
        self.onDismiss = onDismiss
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Move to folder")
                .font(.system(size: 17, weight: .semibold))
                .foregroundColor(QuickInkColors.ink)
                .padding(.horizontal, QuickInkSpacing.s4)
                .padding(.top, QuickInkSpacing.s3)
            Text("Each capture lives in one folder. Pick a destination — the capture moves immediately.")
                .font(.system(size: 12.5))
                .foregroundColor(QuickInkColors.muted)
                .padding(.horizontal, QuickInkSpacing.s4)
                .padding(.top, 4)
                .padding(.bottom, QuickInkSpacing.s2)

            Divider().background(QuickInkColors.border)

            ScrollView {
                VStack(spacing: 0) {
                    ForEach(folders) { folder in
                        folderRow(folder)
                    }
                    if folders.isEmpty {
                        Text("No folders yet. Create one from the Workspace home.")
                            .font(.system(size: 12.5))
                            .foregroundColor(QuickInkColors.muted)
                            .padding(QuickInkSpacing.s4)
                    }
                }
            }
            Spacer(minLength: 12)
        }
        .background(QuickInkColors.surface)
        .onAppear { observeFolders() }
    }

    private func folderRow(_ folder: FolderEntity) -> some View {
        let isCurrent = folder.id == currentFolderId
        return Button(action: { onPickFolder(folder) }) {
            HStack(spacing: QuickInkSpacing.s3) {
                RoundedRectangle(cornerRadius: 6)
                    .fill(colorFromHex(folder.color) ?? QuickInkColors.accent)
                    .frame(width: 24, height: 24)
                VStack(alignment: .leading, spacing: 2) {
                    Text(folder.name)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(QuickInkColors.ink)
                    if folder.isDefault {
                        Text("Default")
                            .font(.system(size: 11))
                            .foregroundColor(QuickInkColors.muted)
                    }
                }
                Spacer()
                if isCurrent {
                    Image(systemName: "checkmark")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(QuickInkColors.accent)
                }
            }
            .padding(.horizontal, QuickInkSpacing.s4)
            .padding(.vertical, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func observeFolders() {
        guard cancellable == nil else { return }
        cancellable = ValueObservation.tracking { [userId] db in
            try FolderEntity
                .filter(Column("user_id") == userId)
                .filter(Column("deleted_at") == nil)
                .order(Column("position").asc, Column("name").asc)
                .fetchAll(db)
        }
        .publisher(in: QuickInkDatabase.shared.dbQueue)
        .receive(on: DispatchQueue.main)
        .sink(receiveCompletion: { _ in }, receiveValue: { folders = $0 })
    }
}
