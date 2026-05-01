/*
 * HomeScreen.swift
 *
 * QuickInk's camera-first Home. Per QUICKINK_PROPOSAL.md §6.4 the
 * home opens directly to the document scanner — there's no
 * intermediate dashboard, the value prop is "tap once and you're
 * scanning." For Slice 3 the home is just a single big Scan CTA;
 * recent captures + library navigation come in Slice 4.
 *
 * The scanner is presented as a `fullScreenCover` over this view
 * (rather than auto-launching on first appear) so the user can
 * back out without immediately re-entering. The auto-launch
 * behavior — "tapping the home tab opens the scanner" — comes
 * with the bottom-nav wiring in Slice 6.
 */

import SwiftUI
import ReleafCoreDesignSystem
import ReleafCoreScan

struct HomeScreen: View {
    @ObservedObject var controller: ScanFlowController

    /// Tapped when the user hits the Notes button in the top-right.
    /// Wired to `MainShell`'s NavigationStack `path.append(.notesList)`.
    let onOpenNotes: () -> Void

    /// Tapped when the user hits the gear icon.
    let onOpenSettings: () -> Void

    @State private var showScanner = false

    /// Camera-first auto-launch — opens the scanner once on the
    /// first time Home becomes visible per app launch (per
    /// QUICKINK_PROPOSAL.md §6.4). Stored on Home itself so
    /// popping back from Notes / Settings doesn't re-trigger;
    /// `@State` survives across NavigationStack push/pop because
    /// SwiftUI keeps the same `HomeScreen` instance on the stack.
    @State private var hasAutoLaunched = false

    var body: some View {
        VStack(spacing: 0) {
            // Top bar — Notes + Settings icons in the top-right.
            // Kept minimal so Home stays camera-first; richer
            // toolbar (search etc.) is later.
            HStack(spacing: 0) {
                Spacer()
                Button(action: onOpenNotes) {
                    Image(systemName: "list.bullet.rectangle")
                        .font(.system(size: 20))
                        .foregroundStyle(AppColors.textPrimary)
                        .padding(AppSpacing.s3)
                }
                .accessibilityLabel("Notes")
                Button(action: onOpenSettings) {
                    Image(systemName: "gearshape")
                        .font(.system(size: 20))
                        .foregroundStyle(AppColors.textPrimary)
                        .padding(AppSpacing.s3)
                }
                .accessibilityLabel("Settings")
            }
            .padding(.horizontal, AppSpacing.s2)
            .padding(.top, AppSpacing.s2)

            VStack(spacing: AppSpacing.s5) {
                Spacer()

                Image(systemName: "doc.text.viewfinder")
                    .font(.system(size: 96))
                    .foregroundStyle(AppColors.themeGreenPrimary)

                VStack(spacing: AppSpacing.s2) {
                    Text("Scan a document")
                        .font(AppText.pageTitle)
                        .foregroundStyle(AppColors.textPrimary)

                    Text("Capture pages, search the text, never lose them.")
                        .font(AppText.body)
                        .foregroundStyle(AppColors.textSecondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, AppSpacing.s5)
                }

                Spacer()

                Button(action: { showScanner = true }) {
                    Text("Scan")
                        .font(AppText.body)
                        .foregroundStyle(AppColors.textOnAccent)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, AppSpacing.s3)
                        .background(AppColors.themeGreenPrimary)
                        .clipShape(Capsule())
                }
                .padding(.horizontal, AppSpacing.s5)
                .padding(.bottom, AppSpacing.s5)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(AppColors.canvas.ignoresSafeArea())
        .onAppear {
            if !hasAutoLaunched {
                hasAutoLaunched = true
                showScanner = true
            }
        }
        .fullScreenCover(isPresented: $showScanner) {
            DocumentScannerView(
                onComplete: { pdfURL, previewURL, pageURLs in
                    showScanner = false
                    controller.onScanComplete(
                        pdfURL:     pdfURL,
                        previewURL: previewURL,
                        pageURLs:   pageURLs
                    )
                },
                onCancel: { showScanner = false }
            )
            .ignoresSafeArea()
        }
    }
}
