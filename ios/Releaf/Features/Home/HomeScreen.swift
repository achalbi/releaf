/*
 * HomeScreen.swift
 *
 * Signed-in Home. Keeps the existing greeting / onboarding / tasks
 * card, and appends two Room/GRDB-backed summary cards (Notebook +
 * Notepad) at the end — a compact dashboard view of what the user
 * has actually captured. The mid-screen raw notebook list from the
 * classic design is gone; the Notebook summary card covers that
 * affordance.
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

public struct HomeScreen: View {
    @EnvironmentObject private var authStore: AuthStore
    @Environment(\.showOnboardingWizard) private var showOnboarding
    @Environment(\.accentPalette) private var accent
    @StateObject private var viewModel: HomeDashboardViewModel

    private let onOpenNotebook: (String) -> Void
    private let onOpenNotebooksTab: () -> Void
    private let onOpenNotepadTab: () -> Void
    private let onOpenNotepadEntry: (String) -> Void
    private let onOpenContacts: () -> Void

    public init(
        userId: String,
        onOpenNotebook: @escaping (String) -> Void = { _ in },
        onOpenNotebooksTab: @escaping () -> Void = {},
        onOpenNotepadTab: @escaping () -> Void = {},
        onOpenNotepadEntry: @escaping (String) -> Void = { _ in },
        onOpenContacts: @escaping () -> Void = {}
    ) {
        _viewModel = StateObject(wrappedValue: HomeDashboardViewModel(userId: userId))
        self.onOpenNotebook      = onOpenNotebook
        self.onOpenNotebooksTab  = onOpenNotebooksTab
        self.onOpenNotepadTab    = onOpenNotepadTab
        self.onOpenNotepadEntry  = onOpenNotepadEntry
        self.onOpenContacts      = onOpenContacts
    }

    public var body: some View {
        ZStack {
            DotGridBackground().ignoresSafeArea()
            content
        }
        .task { viewModel.start() }
        .onDisappear { viewModel.stop() }
        .toolbar(.hidden, for: .navigationBar)
    }

    @ViewBuilder private var content: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: AppSpacing.s6) {
                header
                OnboardingQuickGuideCard(onShowIntro: showOnboarding)
                HomeTasksCard()
                HomeContactsCard(onOpenContacts: onOpenContacts)

                // ── Dashboard cards (new) ──────────────────────────
                if viewModel.state.isLoading {
                    ProgressView()
                        .tint(AppColors.coral)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, AppSpacing.s6)
                } else {
                    VStack(spacing: AppSpacing.s4) {
                        notebookCard
                        notepadCard
                    }
                }

                Spacer(minLength: AppSpacing.s10)
            }
            .padding(AppSpacing.s4)
        }
    }

    // MARK: - Header

    private var header: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            Text("RELEAF")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.coral)

            HStack(alignment: .firstTextBaseline) {
                Text(greeting)
                    .font(AppText.editorialTitle)
                    .foregroundStyle(AppColors.textPrimary)

                Spacer()

                Button {
                    Task { await authStore.signOut() }
                } label: {
                    Text("Sign out")
                        .font(AppText.button)
                        .foregroundStyle(AppColors.coral)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var greeting: String {
        if let name = authStore.session?.displayName, !name.isEmpty {
            return "Hi, \(name)"
        }
        return "Good morning"
    }

    // MARK: - Notebook card

    private var notebookCard: some View {
        PaletteSummaryCard(
            title: "Notebook",
            background: accent.primary,
            action: onOpenNotebooksTab
        ) {
            SummaryStatsRow(items: [
                SummaryStat(label: "Total", value: "\(viewModel.state.totalNotebooks)"),
                SummaryStat(label: "Active", value: "\(viewModel.state.activeNotebooks)"),
                SummaryStat(label: "Archived", value: "\(viewModel.state.archivedNotebooks)"),
            ])
        }
    }

    // MARK: - Notepad card

    private var notepadCard: some View {
        PaletteSummaryCard(
            title: "Notepad",
            background: accent.deep,
            action: onOpenNotepadTab
        ) {
            SummaryStatsRow(items: [
                SummaryStat(label: "Entries", value: "\(viewModel.state.totalNotepadEntries)"),
                SummaryStat(label: "Today", value: "\(viewModel.state.todayNotepadCount)"),
            ])
        }
    }
}

// MARK: - Summary card shell

private struct SummaryStat: Equatable {
    let label: String
    let value: String
}

private struct PaletteSummaryCard<Content: View>: View {
    let title: String
    let background: Color
    let action: () -> Void
    @ViewBuilder let content: () -> Content

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: AppSpacing.s4) {
                HStack {
                    Text(title)
                        .font(AppText.sectionTitle)
                        .foregroundStyle(AppColors.onAccent)
                    Spacer()
                    Image(systemName: "arrow.up.right")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(AppColors.onAccent.opacity(0.86))
                }
                content()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(AppSpacing.s5)
            .background(background)
            .clipShape(RoundedRectangle(cornerRadius: AppRadius.lg, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

private struct SummaryStatsRow: View {
    let items: [SummaryStat]

    var body: some View {
        HStack(spacing: AppSpacing.s3) {
            ForEach(items, id: \.label) { item in
                SummaryStatTile(item: item)
            }
        }
    }
}

private struct SummaryStatTile: View {
    let item: SummaryStat

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            Text(item.value)
                .font(.system(size: 32, weight: .bold, design: .default))
                .foregroundStyle(AppColors.onAccent)
            Text(item.label)
                .font(AppText.meta)
                .foregroundStyle(AppColors.onAccent.opacity(0.82))
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(AppSpacing.s4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.onAccent.opacity(0.12))
        .clipShape(RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .stroke(AppColors.onAccent.opacity(0.14), lineWidth: 1)
        )
    }
}
