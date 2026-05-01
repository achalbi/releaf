/*
 * SettingsScreen.swift
 *
 * Slice 5 — two persisted toggles. Account row, theme override,
 * version info, etc. land in later slices alongside the auth
 * wiring + brand pass.
 *
 * Mirror of Android `SettingsScreen.kt`.
 */

import SwiftUI
import ReleafCoreAuth
import ReleafCoreDesignSystem
import ReleafCoreSync

struct SettingsScreen: View {

    let onBack: () -> Void
    @ObservedObject var authStore: AuthStore

    @StateObject private var settings = SettingsState()

    /// Slice 4.2b — observes `SyncStateStore.shared` for the
    /// "Last synced" row. The store is a published `ObservableObject`,
    /// so SwiftUI re-renders this view whenever a sync pass writes a
    /// fresh `lastFullSyncAt` (via `SyncRepository.recordSuccess`).
    @ObservedObject private var syncState = SyncStateStore.shared

    var body: some View {
        VStack(spacing: 0) {
            topBar

            ScrollView {
                VStack(spacing: AppSpacing.s5) {
                    section(title: "Account") {
                        accountRow
                    }

                    section(title: "Sync") {
                        toggleRow(
                            label: "Back up to Google Drive",
                            help:  "Scans and notes sync to Drive so they follow you across devices.",
                            isOn:  $settings.driveBackupEnabled
                        )
                        // Toggling Drive backup ON kicks an
                        // immediate pass so the user doesn't wait
                        // 15 minutes to see their first upload.
                        // The worker no-ops when the flag is off,
                        // so toggling OFF doesn't need to cancel
                        // the schedule. Mirror of the Android
                        // SettingsScreen's `requestImmediate` call
                        // path — see SettingsScreen.kt.
                        // iOS 17+ two-arg form preferred, but the
                        // one-arg form keeps iOS-16 compatibility
                        // (see Phase-3 build-target compromise).
                        .onChange(of: settings.driveBackupEnabled) { newValue in
                            if newValue, case .signedIn = authStore.state {
                                QuickInkSyncEnvironment.shared.scheduler.requestImmediate()
                            }
                        }
                        // Last sync row — reads the most recent
                        // successful pass from `SyncStateStore`.
                        // Renders the raw ISO-8601 timestamp for now;
                        // a "moments ago / 5m ago / yesterday at 3pm"
                        // formatter can land later when the rest of
                        // the surface gets a relative-time util.
                        // (Releaf's DriveSettingsSection ships the
                        // same way today.)
                        lastSyncedRow

                        // Slice 4.2d — Sync now / Restore from
                        // Drive controls. Mirror of Releaf's
                        // DriveSettingsSection CTAs. Both call
                        // requestImmediate; sync is bidirectional,
                        // so a manual kick of the same worker
                        // covers both push (sync now) and pull
                        // (restore) — distinct labels just frame
                        // the intent for the user. Taps on the
                        // signed-out state no-op gracefully because
                        // the scheduler's `runOnce` closure
                        // short-circuits without a session.
                        syncControlsRow
                    }

                    section(title: "Experimental") {
                        toggleRow(
                            label: "Searchable PDF export",
                            help:  "Adds an invisible OCR text layer to exported PDFs so PDF readers can search and copy the text. Off by default while we tune the layout.",
                            isOn:  $settings.searchablePdfExportEnabled
                        )
                    }
                }
                .padding(.horizontal, AppSpacing.s5)
                .padding(.top, AppSpacing.s4)
                .padding(.bottom, AppSpacing.s8)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(AppColors.canvas.ignoresSafeArea())
    }

    /// Account section content — shows the signed-in display
    /// name + email when there's a session, plus a Sign out
    /// button. Sign out flips `AuthStore.state` to `.signedOut`,
    /// which `QuickInkRoot`'s router observes and bounces to the
    /// SignIn screen (Option A — see `QuickInkRoot.ReSignInGate`).
    @ViewBuilder
    private var accountRow: some View {
        let session: GoogleAuthSession? = {
            if case .signedIn(let s) = authStore.state { return s }
            return nil
        }()

        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: AppSpacing.s1) {
                Text(session?.displayName ?? "Signed in")
                    .font(AppText.body)
                    .foregroundStyle(AppColors.textPrimary)
                Text(session?.email ?? "Not signed in")
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)
            }

            Spacer()

            if session != nil {
                Button(action: signOut) {
                    HStack(spacing: AppSpacing.s1) {
                        Image(systemName: "rectangle.portrait.and.arrow.right")
                            .font(.system(size: 14))
                        Text("Sign out")
                            .font(AppText.body)
                    }
                    .foregroundStyle(AppColors.coralDeep)
                    .padding(AppSpacing.s2)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Sign out")
            }
        }
    }

    private func signOut() {
        Task { await authStore.signOut() }
    }

    @ViewBuilder
    private var topBar: some View {
        HStack {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18))
                    .foregroundStyle(AppColors.textPrimary)
                    .padding(AppSpacing.s3)
            }
            .accessibilityLabel("Back")

            Text("Settings")
                .font(AppText.pageTitle)
                .foregroundStyle(AppColors.textPrimary)

            Spacer()
        }
        .padding(.horizontal, AppSpacing.s2)
        .padding(.top, AppSpacing.s2)
    }

    @ViewBuilder
    private func section<Content: View>(
        title: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            Text(title.uppercased())
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.textSecondary)

            VStack(spacing: AppSpacing.s2) {
                content()
            }
            .padding(AppSpacing.s4)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(AppColors.cardSolid)
            .clipShape(RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous))
        }
    }

    @ViewBuilder
    private func toggleRow(label: String, help: String, isOn: Binding<Bool>) -> some View {
        VStack(alignment: .leading, spacing: AppSpacing.s1) {
            Toggle(isOn: isOn) {
                Text(label)
                    .font(AppText.body)
                    .foregroundStyle(AppColors.textPrimary)
            }
            Text(help)
                .font(AppText.meta)
                .foregroundStyle(AppColors.textSecondary)
        }
    }

    /// "Last synced" row inside the Sync section. Renders the
    /// most recent successful-sync timestamp from `SyncStateStore`,
    /// or "Never" on a fresh install before any pass has landed.
    /// When `syncState.state.pendingCount > 0`, surfaces a
    /// "N pending" chip — rows that failed the most recent pass
    /// and will retry on the next tick.
    @ViewBuilder
    private var lastSyncedRow: some View {
        HStack {
            Text("Last synced")
                .font(AppText.body)
                .foregroundStyle(AppColors.textPrimary)
            Spacer()
            if syncState.state.pendingCount > 0 {
                Text("\(syncState.state.pendingCount) pending")
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.coral)
                    .padding(.trailing, AppSpacing.s2)
            }
            Text(syncState.state.lastFullSyncAt ?? "Never")
                .font(AppText.meta)
                .foregroundStyle(AppColors.textSecondary)
        }
    }

    /// Slice 4.2d — Sync now / Restore from Drive button row.
    /// Mirror of Releaf's `DriveSettingsSection` CTAs. Both call
    /// `requestImmediate`; sync is bidirectional, so a manual
    /// kick of the same worker handles both push (sync now) and
    /// pull (restore) — the labels just frame the intent. Taps
    /// on the signed-out state no-op because
    /// `QuickInkSyncEnvironment.scheduler.runOnce` short-circuits
    /// without a session.
    @ViewBuilder
    private var syncControlsRow: some View {
        let isSignedIn: Bool = {
            if case .signedIn = authStore.state { return true }
            return false
        }()
        HStack(spacing: AppSpacing.s3) {
            AppButton("Sync now", variant: .secondary) {
                if isSignedIn {
                    QuickInkSyncEnvironment.shared.scheduler.requestImmediate()
                }
            }
            .frame(maxWidth: .infinity)

            AppButton("Restore from Drive", variant: .secondary) {
                if isSignedIn {
                    QuickInkSyncEnvironment.shared.scheduler.requestImmediate()
                }
            }
            .frame(maxWidth: .infinity)
        }
    }
}
