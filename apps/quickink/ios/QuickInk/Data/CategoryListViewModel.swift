/*
 * CategoryListViewModel.swift
 *
 * Lightweight observable wrapper around `CategoryRepository.observe`
 * for SwiftUI views. Bound from the scan-review picker chips and
 * the Settings → Categories screen.
 *
 * Mirror of the Flow-collection pattern Android's
 * `CategoriesSettingsScreen` uses; same data source, same ordering.
 */

import Foundation
import Combine

@MainActor
public final class CategoryListViewModel: ObservableObject {

    @Published public private(set) var categories: [CategoryEntity] = []

    private let repository: CategoryRepository
    private let userId: String
    private var cancellable: AnyCancellable?

    public init(
        repository: CategoryRepository = CategoryRepository(),
        userId: String
    ) {
        self.repository = repository
        self.userId = userId
    }

    /// Open the live observation. Call from `.task` or `.onAppear`
    /// of the hosting view; safe to call multiple times — the
    /// previous subscription is replaced.
    public func start() {
        cancellable = repository.observe(userId: userId)
            .receive(on: DispatchQueue.main)
            .sink(
                receiveCompletion: { _ in },
                receiveValue: { [weak self] in self?.categories = $0 }
            )
    }
}
