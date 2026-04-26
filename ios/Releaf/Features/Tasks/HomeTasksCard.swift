/*
 * HomeTasksCard.swift
 *
 * Home-screen entry point for the workspace Tasks surface. Renders a
 * clickable card with "TASKS" eyebrow, count summary, and a forward
 * arrow. Tapping emits a NavigationLink on TasksRoute.
 *
 * Reads the open-count via [TaskRepository.observeOpenCount] so the
 * home page doesn't need its own VM to surface task state.
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

public struct HomeTasksCard: View {
    @Environment(\.accentPalette) private var accent
    @EnvironmentObject private var authStore: AuthStore
    @State private var openCount: Int = 0
    @State private var observationTask: _Concurrency.Task<Void, Never>?

    public init() {}

    public var body: some View {
        NavigationLink(value: TasksRoute()) {
            HStack(spacing: AppSpacing.s3) {
                ZStack {
                    Circle()
                        .fill(accent.soft)
                        .frame(width: 44, height: 44)
                    Text("✓")
                        .font(.system(size: 22))
                        .foregroundStyle(accent.primary)
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text("TASKS")
                        .font(AppText.eyebrow)
                        .tracking(AppLetterSpacing.eyebrow)
                        .foregroundStyle(accent.primary)
                    Text("Your tasks")
                        .font(AppText.sectionTitle)
                        .foregroundStyle(AppColors.textPrimary)
                    Text(summary)
                        .font(AppText.meta)
                        .foregroundStyle(AppColors.textSecondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Text("→")
                    .font(.system(size: 20))
                    .foregroundStyle(accent.primary)
            }
            .padding(AppSpacing.s4)
            .background(AppColors.cardSolid)
            .overlay(
                RoundedRectangle(cornerRadius: AppRadius.md)
                    .stroke(AppColors.borderDefault, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: AppRadius.md))
        }
        .buttonStyle(.plain)
        .task { await startObservation() }
        .onDisappear { observationTask?.cancel() }
    }

    private var summary: String {
        switch openCount {
        case 0:  return "All caught up · tap to add"
        case 1:  return "1 open task"
        default: return "\(openCount) open tasks"
        }
    }

    @MainActor
    private func startObservation() async {
        guard let userId = authStore.session?.userId else { return }
        observationTask?.cancel()
        observationTask = _Concurrency.Task {
            let stream = TaskRepository().observeOpenCount(userId: userId)
            do {
                for try await n in stream {
                    await MainActor.run { self.openCount = n }
                }
            } catch {
                // Observation failure is non-fatal — leave the count at
                // its last-known value (0 on first load). The Tasks
                // screen itself surfaces the real data.
            }
        }
    }
}
