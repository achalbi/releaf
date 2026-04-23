/*
 * TasksViewModel.swift
 *
 * Backs the workspace-level Tasks screen. Bootstraps by kicking off
 * an `observeActive` task; mutations fire into the repository on a
 * detached `_Concurrency.Task` so the `@Published` lists snap to the
 * new state on the next observation tick.
 */

import Foundation
import Combine
import ReleafData

@MainActor
public final class TasksViewModel: ObservableObject {

    @Published public private(set) var isLoading: Bool = true
    @Published public private(set) var tasks: [TaskRecord] = []

    public var openCount: Int { tasks.filter { !$0.completed }.count }
    public var doneCount: Int { tasks.filter { $0.completed }.count }

    private let repository: TaskRepository
    private let userId: String
    private var observation: _Concurrency.Task<Void, Never>?

    public init(repository: TaskRepository, userId: String) {
        self.repository = repository
        self.userId = userId
    }

    public func bootstrap() async {
        observation?.cancel()
        observation = _Concurrency.Task { [weak self] in
            guard let self else { return }
            let stream = repository.observeActive(userId: userId)
            do {
                for try await batch in stream {
                    await MainActor.run {
                        self.tasks = batch
                        self.isLoading = false
                    }
                }
            } catch {
                await MainActor.run { self.isLoading = false }
            }
        }
    }

    deinit { observation?.cancel() }

    // MARK: - Mutations

    public func addTask(title: String, dueDate: String? = nil, priority: Int = 0) {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        _Concurrency.Task { [repository, userId] in
            _ = try? await repository.create(
                userId: userId,
                title: trimmed,
                dueDate: dueDate,
                priority: priority
            )
        }
    }

    public func toggleCompleted(_ task: TaskRecord) {
        _Concurrency.Task { [repository] in
            try? await repository.setCompleted(id: task.id, completed: !task.completed)
        }
    }

    public func deleteTask(_ task: TaskRecord) {
        _Concurrency.Task { [repository] in
            try? await repository.softDelete(id: task.id)
        }
    }

    public func undoDelete(id: String) {
        _Concurrency.Task { [repository] in
            try? await repository.undoSoftDelete(id: id)
        }
    }
}
