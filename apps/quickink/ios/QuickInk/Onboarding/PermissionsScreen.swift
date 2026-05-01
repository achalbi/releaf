/*
 * PermissionsScreen.swift
 *
 * Onboarding step 2/3. Educates the user about the camera
 * permission QuickInk will request the first time they tap "scan."
 *
 * Doesn't actually request the permission here — that lands at
 * scan time (the system permission sheet appears in-context when
 * VisionKit's `VNDocumentCameraViewController` first presents).
 * Pre-empting it on this screen would just produce an awkward
 * out-of-context prompt; the screen's job is to set expectation.
 */

import SwiftUI
import ReleafCoreDesignSystem

struct PermissionsScreen: View {
    let onContinue: () -> Void

    var body: some View {
        VStack(spacing: AppSpacing.s5) {
            Spacer()

            Image(systemName: "camera.viewfinder")
                .font(.system(size: 64))
                .foregroundStyle(AppColors.themeGreenPrimary)

            VStack(spacing: AppSpacing.s2) {
                Text("Camera access")
                    .font(AppText.pageTitle)
                    .foregroundStyle(AppColors.textPrimary)

                Text("QuickInk needs the camera to scan documents. We'll ask the first time you tap a scan — you can decline anytime in Settings.")
                    .font(AppText.body)
                    .foregroundStyle(AppColors.textSecondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, AppSpacing.s5)
            }

            Spacer()

            Button(action: onContinue) {
                Text("Continue")
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
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(AppColors.canvas.ignoresSafeArea())
    }
}
