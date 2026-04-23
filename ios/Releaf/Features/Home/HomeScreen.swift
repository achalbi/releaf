/*
 * HomeScreen.swift
 * Signed-in home. Lists notebooks. Each row pushes NotebookDetailView.
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

public struct HomeScreen: View {
    @EnvironmentObject private var authStore: AuthStore
    @Environment(\.showOnboardingWizard) private var showOnboarding
    @StateObject private var viewModel = HomeViewModel()

    public init() {}

    public var body: some View {
        ZStack {
            DotGridBackground().ignoresSafeArea()
            content
        }
        .task { await viewModel.load() }
        .toolbar(.hidden, for: .navigationBar)
    }

    @ViewBuilder private var content: some View {
        switch viewModel.state {
        case .idle, .loading:
            ProgressView()
                .tint(AppColors.coral)

        case .failed(let message):
            VStack(spacing: AppSpacing.s3) {
                Text(message)
                    .font(AppText.body)
                    .foregroundStyle(AppColors.textSecondary)
                AppButton("Try again", variant: .secondary) {
                    Task { await viewModel.load() }
                }
                .fixedSize(horizontal: true, vertical: false)
            }

        case .loaded(let notebooks):
            ScrollView {
                VStack(alignment: .leading, spacing: AppSpacing.s6) {
                    header
                    OnboardingQuickGuideCard(onShowIntro: showOnboarding)
                    HomeTasksCard()
                    ForEach(notebooks) { notebook in
                        NavigationLink(value: NotebookRoute(id: notebook.id)) {
                            NotebookRow(notebook: notebook)
                        }
                        .buttonStyle(.plain)
                    }
                    Spacer(minLength: AppSpacing.s10)
                }
                .padding(AppSpacing.s4)
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            Text("NOTEBOOKS")
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
}

// MARK: - NotebookRow

private struct NotebookRow: View {
    let notebook: Notebook

    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: AppSpacing.s2) {
                Text(notebook.title)
                    .font(AppText.sectionTitle)
                    .foregroundStyle(AppColors.textPrimary)

                if let description = notebook.description, !description.isEmpty {
                    Text(description)
                        .font(AppText.body)
                        .foregroundStyle(AppColors.textSecondary)
                }

                Text(metaLine)
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var metaLine: String {
        let chapters = "\(notebook.chapterCount) chapter\(notebook.chapterCount == 1 ? "" : "s")"
        let pages    = "\(notebook.pageCount) page\(notebook.pageCount == 1 ? "" : "s")"
        return "\(chapters) · \(pages)"
    }
}

#Preview {
    HomeScreen()
        .environmentObject({
            let store = AuthStore(client: StubGoogleAuthClient())
            Task { await store.signIn() }
            return store
        }())
}
