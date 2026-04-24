/*
 * ContactsViewModel.swift
 *
 * Backs the Contacts screen. Observes the aggregated app directory
 * and, on user input, searches the device address book. Coalesces
 * both halves into a single `ContactsState` the view renders.
 */

import Foundation
import ReleafData

@MainActor
public final class ContactsViewModel: ObservableObject {

    public struct State: Equatable {
        public var query: String = ""
        public var isLoading: Bool = true
        public var allAppContacts: [DirectoryContact] = []
        public var filteredAppContacts: [DirectoryContact] = []
        public var deviceContacts: [DirectoryContact] = []
        public var devicePermissionGranted: Bool = false

        public var isSearching: Bool {
            !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
    }

    @Published public private(set) var state: State = State()

    private let userId: String
    private let directory: ContactDirectoryRepository
    private let device: DeviceContactsProvider

    private var directoryTask: Task<Void, Never>?
    private var deviceSearchTask: Task<Void, Never>?

    public init(
        userId: String,
        directory: ContactDirectoryRepository = ContactDirectoryRepository(),
        device: DeviceContactsProvider = DeviceContactsProvider()
    ) {
        self.userId = userId
        self.directory = directory
        self.device = device
        self.state.devicePermissionGranted = device.hasPermission
    }

    public func start() {
        stop()
        directoryTask = Task { [weak self, directory, userId] in
            guard let self else { return }
            do {
                for try await all in directory.observeAll(userId: userId) {
                    self.state.isLoading = false
                    self.state.allAppContacts = all
                    self.recomputeFiltered()
                }
            } catch {}
        }
    }

    public func stop() {
        directoryTask?.cancel()
        directoryTask = nil
        deviceSearchTask?.cancel()
        deviceSearchTask = nil
    }

    deinit {
        directoryTask?.cancel()
        deviceSearchTask?.cancel()
    }

    public func updateQuery(_ value: String) {
        state.query = value
        recomputeFiltered()
        scheduleDeviceSearch()
    }

    public func clearQuery() {
        state.query = ""
        state.deviceContacts = []
        recomputeFiltered()
        deviceSearchTask?.cancel()
    }

    public func requestPermission() {
        Task { [weak self, device] in
            let granted = await device.requestPermission()
            await MainActor.run {
                self?.state.devicePermissionGranted = granted
                self?.scheduleDeviceSearch()
            }
        }
    }

    // MARK: - Private

    private func recomputeFiltered() {
        let trimmed = state.query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            state.filteredAppContacts = state.allAppContacts
            return
        }
        let lc = trimmed.lowercased()
        state.filteredAppContacts = state.allAppContacts.filter { c in
            if c.name.lowercased().contains(lc) { return true }
            if let phone = c.phone, phone.lowercased().contains(lc) { return true }
            if let email = c.email, email.lowercased().contains(lc) { return true }
            if let org = c.organization, org.lowercased().contains(lc) { return true }
            return false
        }
    }

    private func scheduleDeviceSearch() {
        deviceSearchTask?.cancel()
        let q = state.query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty, device.hasPermission else {
            state.deviceContacts = []
            return
        }
        deviceSearchTask = Task { [weak self, device] in
            try? await Task.sleep(nanoseconds: 150_000_000)
            if Task.isCancelled { return }
            let hits = await device.search(rawQuery: q)
            await MainActor.run {
                self?.state.deviceContacts = hits
            }
        }
    }
}
