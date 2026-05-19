/*
 * PlaceDetailScreen.swift
 *
 * Read-only list of captures attached to a single Place. Mirror of
 * Android's `LocationDetailScreen`. Tapping a row routes to the
 * existing ScanDetailScreen via [onOpenCapture]; long-press cycles
 * to the location editor for rename / address edit.
 */

import SwiftUI
import Combine
import GRDB
import ReleafCoreDesignSystem

@MainActor
public struct PlaceDetailScreen: View {

    public let userId: String
    public let location: LocationEntity
    public let onOpenCapture: (CaptureSummary) -> Void
    public let onBack: () -> Void

    @StateObject private var model: DetailModel
    @State private var editorMode: LocationEditorMode? = nil

    public init(
        userId: String,
        location: LocationEntity,
        onOpenCapture: @escaping (CaptureSummary) -> Void,
        onBack: @escaping () -> Void
    ) {
        self.userId        = userId
        self.location      = location
        self.onOpenCapture = onOpenCapture
        self.onBack        = onBack
        _model = StateObject(wrappedValue: DetailModel(location: location))
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
            LocationEditorView(
                mode:     mode,
                onSubmit: { name, address, latitude, longitude in
                    Task {
                        if case .edit(let loc) = mode {
                            if !name.isEmpty, name != loc.name {
                                try? await LocationRepository().rename(id: loc.id, newName: name)
                            }
                            let nextAddress = address?.isEmpty == true ? nil : address
                            if nextAddress != loc.address ||
                               latitude    != loc.latitude ||
                               longitude   != loc.longitude {
                                try? await LocationRepository().setCoordinates(
                                    id:        loc.id,
                                    latitude:  latitude,
                                    longitude: longitude,
                                    address:   nextAddress
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
                Text(location.name)
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundColor(QuickInkColors.ink)
                if let address = location.address, !address.isEmpty {
                    Text(address)
                        .font(.system(size: 12.5))
                        .foregroundColor(QuickInkColors.muted)
                }
            }
            Spacer()
            Button(action: { editorMode = .edit(location: location) }) {
                Image(systemName: "ellipsis.circle")
                    .font(.system(size: 18))
                    .foregroundColor(QuickInkColors.muted)
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: AppSpacing.s2) {
            Spacer()
            Image(systemName: "mappin.slash")
                .font(.system(size: 28))
                .foregroundColor(QuickInkColors.muted)
            Text("No scans attached to \(location.name) yet.")
                .font(.system(size: 13))
                .foregroundColor(QuickInkColors.muted)
            Text("Attach this place to a scan from its detail screen.")
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

        private let location: LocationEntity
        private let dbQueue: DatabaseQueue
        private var cancellable: AnyCancellable?
        private var started = false

        init(location: LocationEntity, database: QuickInkDatabase = .shared) {
            self.location = location
            self.dbQueue = database.dbQueue
        }

        func start() {
            guard !started else { return }
            started = true
            let locationId = location.id
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
                    JOIN capture_locations
                      ON capture_locations.capture_id = captures.id
                    WHERE capture_locations.location_id = ?
                      AND capture_locations.deleted_at IS NULL
                      AND captures.deleted_at IS NULL
                    ORDER BY captures.created_at DESC
                    """, arguments: [locationId])
            }
            .publisher(in: dbQueue)
            .receive(on: DispatchQueue.main)
            .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] in
                self?.captures = $0
            })
        }
    }
}
