/*
 * QuickCaptureSheet.swift
 *
 * Bottom sheet presenting the 7 capture modes as large tappable rows.
 * Triggered by the CaptureFAB or the center Leaf in the BottomNav.
 *
 * Ported from Inkcreate mobile DS.
 */

import SwiftUI

// MARK: - QuickCaptureSheet

public struct QuickCaptureSheet: View {
    private let modes: [CaptureMode]
    private let onSelect: (CaptureMode) -> Void

    public init(
        modes: [CaptureMode] = CaptureMode.allCases,
        onSelect: @escaping (CaptureMode) -> Void
    ) {
        self.modes = modes
        self.onSelect = onSelect
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header

            ScrollView(showsIndicators: false) {
                VStack(spacing: AppSpacing.s2) {
                    ForEach(modes) { mode in
                        CaptureRow(mode: mode) { onSelect(mode) }
                    }
                }
                .padding(.horizontal, AppSpacing.s4)
                .padding(.bottom, AppSpacing.s6)
            }
        }
        .background(AppColors.canvas.ignoresSafeArea())
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationCornerRadius(AppRadius.lg + 4)
    }

    // MARK: Header

    private var header: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s1) {
            Text("NEW CAPTURE")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundColor(AppColors.coralDeep)
            Text("What do you want to add?")
                .font(AppText.pageTitle)
                .foregroundColor(AppColors.textPrimary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, AppSpacing.s4)
        .padding(.top, AppSpacing.s4)
        .padding(.bottom, AppSpacing.s3)
    }
}

// MARK: - Row

private struct CaptureRow: View {
    let mode: CaptureMode
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: AppSpacing.s3) {
                iconChip
                VStack(alignment: .leading, spacing: 2) {
                    Text(mode.title)
                        .font(AppText.button)
                        .foregroundColor(AppColors.textPrimary)
                    Text(mode.subtitle)
                        .font(AppText.meta)
                        .foregroundColor(AppColors.textSecondary)
                }
                Spacer(minLength: AppSpacing.s2)
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(AppColors.textTertiary)
            }
            .padding(.horizontal, AppSpacing.s4)
            .padding(.vertical, AppSpacing.s3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                    .fill(AppColors.cardSolid)
            )
            .overlay(
                RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                    .stroke(AppColors.borderDefault, lineWidth: 1)
            )
            .appShadow(.xs)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("\(mode.title). \(mode.subtitle)"))
    }

    private var iconChip: some View {
        ZStack {
            RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous)
                .fill(AppColors.coralSoft)
            Image(systemName: mode.systemIcon)
                .font(.system(size: 18, weight: .semibold))
                .foregroundColor(AppColors.coralDeep)
        }
        .frame(width: 40, height: 40)
    }
}

// MARK: - Convenience presentation

public extension View {
    /// Presents `QuickCaptureSheet` driven by a Bool binding.
    func quickCaptureSheet(
        isPresented: Binding<Bool>,
        onSelect: @escaping (CaptureMode) -> Void
    ) -> some View {
        self.sheet(isPresented: isPresented) {
            QuickCaptureSheet { mode in
                onSelect(mode)
                isPresented.wrappedValue = false
            }
        }
    }
}

#if DEBUG
struct QuickCaptureSheet_Previews: PreviewProvider {
    struct Host: View {
        @State private var open = true
        var body: some View {
            AppColors.canvas
                .ignoresSafeArea()
                .quickCaptureSheet(isPresented: $open) { _ in }
        }
    }
    static var previews: some View {
        Host().previewDisplayName("QuickCaptureSheet")
    }
}
#endif
