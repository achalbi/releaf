/*
 * PersonDetailScreen.swift
 *
 * Read-only list of captures attached to a single Person. Mirror of
 * Android's person detail composable in `WorkspaceHomeScreen.kt`.
 */

import SwiftUI
import Combine
import GRDB
import ReleafCoreDesignSystem

@MainActor
public struct PersonDetailScreen: View {

    public let userId: String
    public let person: PersonEntity
    public let onOpenCapture: (CaptureSummary) -> Void
    public let onBack: () -> Void

    @StateObject private var model: DetailModel
    @State private var editorMode: PersonEditorMode? = nil

    public init(
        userId: String,
        person: PersonEntity,
        onOpenCapture: @escaping (CaptureSummary) -> Void,
        onBack: @escaping () -> Void
    ) {
        self.userId        = userId
        self.person        = person
        self.onOpenCapture = onOpenCapture
        self.onBack        = onBack
        _model = StateObject(wrappedValue: DetailModel(person: person))
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s3) {
            header
                .padding(.horizontal, AppSpacing.s4)
                .padding(.top, AppSpacing.s3)

            if model.captures.isEmpty {
                emptyState
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(model.captures) { capture in
                            captureRow(capture)
                            Divider().background(QuickInkColors.borderSoft)
                        }
                    }
                    .padding(.horizontal, AppSpacing.s4)
                }
            }
        }
        .background(QuickInkColors.bg)
        .onAppear { model.start() }
        .sheet(item: $editorMode) { mode in
            PersonEditorView(
                mode:     mode,
                onSubmit: { name, phone, email, lookupKey, photoUri in
                    Task {
                        if case .edit(let target) = mode {
                            if !name.isEmpty, name != target.name {
                                try? await PersonRepository().rename(id: target.id, newName: name)
                            }
                            let nextPhone = phone?.isEmpty == true ? nil : phone
                            let nextEmail = email?.isEmpty == true ? nil : email
                            if nextPhone != target.contactPhone ||
                               nextEmail != target.contactEmail ||
                               lookupKey != target.contactLookupKey ||
                               photoUri  != target.contactPhotoUri {
                                try? await PersonRepository().setContactLink(
                                    id:        target.id,
                                    lookupKey: lookupKey,
                                    phone:     nextPhone,
                                    email:     nextEmail,
                                    photoUri:  photoUri
                                )
                            }
                        }
                    }
                    editorMode = nil
                },
                onCancel: { editorMode = nil }
            )
            .presentationDetents([.medium])
        }
    }

    private var header: some View {
        HStack(spacing: AppSpacing.s3) {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(QuickInkColors.ink)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(person.name)
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundColor(QuickInkColors.ink)
                let detail = person.contactPhone ?? person.contactEmail
                if let detail = detail, !detail.isEmpty {
                    Text(detail)
                        .font(.system(size: 12.5))
                        .foregroundColor(QuickInkColors.muted)
                }
            }
            Spacer()
            Button(action: { editorMode = .edit(person: person) }) {
                Image(systemName: "ellipsis.circle")
                    .font(.system(size: 18))
                    .foregroundColor(QuickInkColors.muted)
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: AppSpacing.s2) {
            Spacer()
            Image(systemName: "person.crop.circle.badge.questionmark")
                .font(.system(size: 28))
                .foregroundColor(QuickInkColors.muted)
            Text("No scans attached to \(person.name) yet.")
                .font(.system(size: 13))
                .foregroundColor(QuickInkColors.muted)
            Text("Attach this person to a scan from its detail screen.")
                .font(.system(size: 12))
                .foregroundColor(QuickInkColors.muted)
                .multilineTextAlignment(.center)
                .padding(.horizontal, AppSpacing.s4)
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }

    private func captureRow(_ capture: CaptureSummary) -> some View {
        Button(action: { onOpenCapture(capture) }) {
            HStack(spacing: AppSpacing.s3) {
                RoundedRectangle(cornerRadius: 6)
                    .fill(QuickInkColors.borderSoft)
                    .frame(width: 36, height: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(capture.title ?? "Untitled")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(QuickInkColors.ink)
                    Text("\(capture.pageCount) \(capture.pageCount == 1 ? "page" : "pages")")
                        .font(.system(size: 11.5))
                        .foregroundColor(QuickInkColors.muted)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 12))
                    .foregroundColor(QuickInkColors.muted)
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    @MainActor
    final class DetailModel: ObservableObject {
        @Published private(set) var captures: [CaptureSummary] = []

        private let person: PersonEntity
        private let dbQueue: DatabaseQueue
        private var cancellable: AnyCancellable?
        private var started = false

        init(person: PersonEntity, database: QuickInkDatabase = .shared) {
            self.person = person
            self.dbQueue = database.dbQueue
        }

        func start() {
            guard !started else { return }
            started = true
            let personId = person.id
            cancellable = ValueObservation.tracking { db in
                try CaptureSummary.fetchAll(db, sql: """
                    SELECT captures.id, captures.title, captures.preview_uri,
                           captures.pdf_uri, captures.page_count, captures.created_at,
                           captures.source,
                           captures.latitude, captures.longitude,
                           captures.locality, captures.sub_locality, captures.address,
                           captures.folder_id, captures.last_opened_at,
                           captures.last_opened_page, captures.last_opened_device
                    FROM captures
                    JOIN capture_people
                      ON capture_people.capture_id = captures.id
                    WHERE capture_people.person_id = ?
                      AND capture_people.deleted_at IS NULL
                      AND captures.deleted_at IS NULL
                    ORDER BY captures.created_at DESC
                    """, arguments: [personId])
            }
            .publisher(in: dbQueue)
            .receive(on: DispatchQueue.main)
            .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] in
                self?.captures = $0
            })
        }
    }
}
