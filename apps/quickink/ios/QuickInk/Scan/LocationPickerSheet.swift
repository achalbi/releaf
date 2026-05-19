/*
 * LocationPickerSheet.swift
 *
 * Attach / detach Places to a capture. Invoked from `ScanDetailScreen`.
 * Mirror of Android's `LocationPickerSheet`.
 */

import SwiftUI
import Combine
import ReleafCoreDesignSystem

@MainActor
public struct LocationPickerSheet: View {

    public let userId: String
    public let captureId: String
    public let onDismiss: () -> Void

    @StateObject private var model: PickerModel

    public init(
        userId: String,
        captureId: String,
        onDismiss: @escaping () -> Void
    ) {
        self.userId    = userId
        self.captureId = captureId
        self.onDismiss = onDismiss
        _model = StateObject(wrappedValue: PickerModel(
            userId:    userId,
            captureId: captureId
        ))
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s3) {
            HStack {
                Text("Places on this scan")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundColor(QuickInkColors.ink)
                Spacer()
                Button("Done", action: { commitAndDismiss() })
                    .foregroundColor(QuickInkColors.accent)
                    .font(.system(size: 14, weight: .semibold))
            }

            HStack(spacing: 8) {
                TextField("Add or search a place", text: $model.draft)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit { model.addDraftIfValid() }
                Button("Add") { model.addDraftIfValid() }
                    .disabled(model.draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }

            if model.allLocations.isEmpty {
                Text("No places yet. Type one above to start.")
                    .font(.system(size: 12.5))
                    .foregroundColor(QuickInkColors.muted)
                    .padding(.vertical, AppSpacing.s2)
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(model.allLocations) { loc in
                            row(for: loc)
                            Divider().background(QuickInkColors.borderSoft)
                        }
                    }
                }
                .frame(maxHeight: 320)
            }
            Spacer()
        }
        .padding(AppSpacing.s4)
        .background(QuickInkColors.surface)
        .onAppear { model.start() }
    }

    private func row(for loc: LocationEntity) -> some View {
        let isSelected = model.selectedIds.contains(loc.id)
        return Button(action: { model.toggle(loc.id) }) {
            HStack(spacing: AppSpacing.s3) {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .foregroundColor(isSelected ? QuickInkColors.accent : QuickInkColors.muted)
                    .font(.system(size: 18))
                VStack(alignment: .leading, spacing: 2) {
                    Text(loc.name)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(QuickInkColors.ink)
                    if let address = loc.address, !address.isEmpty {
                        Text(address)
                            .font(.system(size: 11.5))
                            .foregroundColor(QuickInkColors.muted)
                            .lineLimit(1)
                    }
                }
                Spacer()
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func commitAndDismiss() {
        Task {
            await model.commit()
            onDismiss()
        }
    }

    @MainActor
    final class PickerModel: ObservableObject {

        @Published var draft: String = ""
        @Published private(set) var allLocations: [LocationEntity] = []
        @Published private(set) var selectedIds: Set<String> = []

        private let userId: String
        private let captureId: String
        private let repo: LocationRepository
        private var observeCancel: AnyCancellable?
        private var attachedCancel: AnyCancellable?
        private var originalIds: Set<String> = []
        private var started = false

        init(userId: String, captureId: String) {
            self.userId    = userId
            self.captureId = captureId
            self.repo = LocationRepository()
        }

        func start() {
            guard !started else { return }
            started = true

            observeCancel = repo.observe(userId: userId)
                .receive(on: DispatchQueue.main)
                .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] in
                    self?.allLocations = $0
                })

            attachedCancel = repo.observeLocationIds(captureId: captureId)
                .receive(on: DispatchQueue.main)
                .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] ids in
                    let set = Set(ids)
                    self?.selectedIds = set
                    if self?.originalIds.isEmpty == true {
                        self?.originalIds = set
                    }
                })
        }

        func toggle(_ id: String) {
            if selectedIds.contains(id) {
                selectedIds.remove(id)
            } else {
                selectedIds.insert(id)
            }
        }

        func addDraftIfValid() {
            let trimmed = draft.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { return }
            draft = ""
            Task { [userId, captureId] in
                let entity = try? await repo.findOrCreate(userId: userId, name: trimmed)
                if let entity = entity {
                    try? await repo.attachLocation(captureId: captureId, locationId: entity.id)
                    _ = await MainActor.run { selectedIds.insert(entity.id) }
                }
            }
        }

        func commit() async {
            let target = selectedIds
            let toAttach = target.subtracting(originalIds)
            let toDetach = originalIds.subtracting(target)
            for id in toAttach {
                try? await repo.attachLocation(captureId: captureId, locationId: id)
            }
            for id in toDetach {
                try? await repo.detachLocation(captureId: captureId, locationId: id)
            }
        }
    }
}
