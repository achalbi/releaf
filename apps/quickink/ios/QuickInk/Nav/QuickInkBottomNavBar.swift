/*
 * QuickInkBottomNavBar.swift
 *
 * Floating editorial card bottom navigation — five tabs on an opaque
 * cream surface with a hairline border and a single soft shadow,
 * hovering over the canvas with a lifted coral ⚡ Zap FAB in the
 * centre. Mirror of Android `QuickInkBottomNavBar.kt`; both apps
 * share the same UX vocabulary now (clean cream card, no glass blur,
 * matching width + bottom-clearance values).
 *
 *   ┌─────────────────────────────────────────┐
 *   │  Home   Library   ⚡   Stories Settings │
 *   └─────────────────────────────────────────┘
 *
 * Active-tab indication is driven by `activeTab`; each cell paints
 * its pill-style background (accentSoft fill, accent tint) when its
 * `NavTab` matches. Tapping a tab fires its corresponding callback.
 * The FAB is always coral — it represents an action (scan), not a
 * destination.
 *
 * Surface (matches Android):
 *   - Shape  : RoundedRectangle(QuickInkRadius.lg)
 *   - Fill   : QuickInkColors.surface (opaque)
 *   - Border : 1pt QuickInkColors.border hairline
 *   - Shadow : single ink@12% / radius 8 / y+2
 *
 * Width (matches Android):
 *   - Outer horizontal padding s4 (16pt)
 *   - Outer bottom padding     s6 (24pt) — clears the home indicator
 *   - Inner HStack             0pt horizontal — cells go edge-to-edge
 *
 * Earlier iOS-only divergence (glass-morphism + s3 bottom + s1 inner
 * horizontal padding) was reverted to keep the two platforms in
 * pixel sync.
 */

import SwiftUI

/// Top-level destinations that own a tab in the bottom nav. Used by
/// `QuickInkBottomNavBar` to paint the active cell. Scan is *not* a
/// tab — it's a transient action launched from the FAB. `none` is a
/// sentinel for sub-screens (e.g. ScanDetail) that host the bar but
/// aren't themselves a destination — passing `.none` paints no
/// active cell.
public enum NavTab { case home, workspace, stories, settings, none }

/// The reserved space the bottom nav occupies on screens that own a
/// scroll surface. Padding callers should add at the bottom of their
/// scroll content so the last item isn't hidden behind the floating
/// bar. ~140pt covers the bar (~80) + the ⚡ FAB lift (~16) +
/// breathing room.
public let QuickInkBottomNavReservedHeight: CGFloat = 140

public struct QuickInkBottomNavBar: View {

    public let activeTab: NavTab
    public let onHome: () -> Void
    public let onWorkspace: () -> Void
    public let onScan: () -> Void
    /// Long-press on the ⚡ FAB — jumps the user directly into
    /// the Photo capture surface (QuickCaptureScreen with
    /// `initialMode: .photo`). Tap still goes to `onScan`, which
    /// opens whichever pill-selected mode the user last used.
    /// Defaults to a no-op so callers that don't care about the
    /// long-press path (legacy hosts, previews) keep working.
    public let onLongPressScan: () -> Void
    public let onStories: () -> Void
    public let onSettings: () -> Void
    /// Discoverability hint above the ⚡ FAB — renders the
    /// floating "Hold ⚡ for a quick photo" chip when `true`.
    /// The caller (MainShell) reads this from `PhotoFabHint`'s
    /// `@StateObject` (`!dismissed`): the chip shows on every
    /// launch until the user long-presses the FAB once, after
    /// which it stays dismissed permanently across launches.
    /// Defaults to `false` so legacy hosts / previews opt in
    /// explicitly. Spec §3.1 (the spec also gated this on
    /// "after the user has scanned at least once"; we dropped
    /// that gate so existing users upgrading into this build
    /// see the chip immediately rather than after one more
    /// scan — see `PhotoFabHint` docblock).
    public let showPhotoHint: Bool

    public init(
        activeTab: NavTab,
        onHome: @escaping () -> Void,
        onWorkspace: @escaping () -> Void,
        onScan: @escaping () -> Void,
        onLongPressScan: @escaping () -> Void = {},
        onStories: @escaping () -> Void,
        onSettings: @escaping () -> Void,
        showPhotoHint: Bool = false
    ) {
        self.activeTab = activeTab
        self.onHome = onHome
        self.onWorkspace = onWorkspace
        self.onScan = onScan
        self.onLongPressScan = onLongPressScan
        self.onStories = onStories
        self.onSettings = onSettings
        self.showPhotoHint = showPhotoHint
    }

    public var body: some View {
        let cardShape = RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous)

        // ZStack(.top) so the FAB renders as a SIBLING of the bar,
        // AFTER the bar's `.overlay(border)` modifier. With the FAB
        // inside the HStack, the bar's `.overlay` was painting the
        // hairline border on top of the lifted FAB at the
        // intersection along the bar's top edge — visually the FAB
        // had a stripe through it. Lifting the FAB out of the HStack
        // and rendering it as the ZStack's second (top) child fixes
        // the z-order without changing the FAB's visual position.
        //
        // Width / surface match with Android `QuickInkBottomNavBar.kt`:
        //  - Outer horizontal padding: s4 (matches Android)
        //  - Outer bottom padding:     s6 (matches Android — was s3,
        //                              made the iOS bar sit too close
        //                              to the home indicator)
        //  - HStack horizontal padding: 0 (was s1 which ate ~8pt of
        //                              total cell width; Android's
        //                              cells go edge-to-edge inside
        //                              the card, this restores that)
        //  - Background: opaque surface + 1pt border + single shadow,
        //                replacing the glass-morphism stack (thin
        //                material blur + 32% surface + gradient
        //                border + double shadow). The cleaner cream
        //                card matches Releaf and Android's shared
        //                aesthetic; the glass blur was an iOS-only
        //                divergence.
        ZStack(alignment: .top) {
            HStack(spacing: 0) {
                navIcon(systemName: "house", label: "Home", active: activeTab == .home, action: onHome)
                    .frame(maxWidth: .infinity)
                // 6pt instead of the default 8pt so the 9-char
                // "Workspace" label clears its slot. The other
                // tabs (4–8 chars) keep the s2 default.
                navIconAsset(
                    assetName: "IconNote",
                    label: "Workspace",
                    active: activeTab == .workspace,
                    horizontalPadding: 6,
                    action: onWorkspace
                )
                    .frame(maxWidth: .infinity)
                // Placeholder for the FAB column — keeps the HStack at
                // 5 equal cells so the flanking cells stay symmetric.
                Color.clear
                    .frame(maxWidth: .infinity)
                    .frame(height: 64)
                navIconAsset(assetName: "IconStory", label: "Stories", active: activeTab == .stories, action: onStories)
                    .frame(maxWidth: .infinity)
                navIcon(systemName: "gearshape", label: "Settings", active: activeTab == .settings, action: onSettings)
                    .frame(maxWidth: .infinity)
            }
            .padding(.vertical, QuickInkSpacing.s1)
            .background(QuickInkColors.surface, in: cardShape)
            .overlay(
                cardShape.strokeBorder(QuickInkColors.border, lineWidth: 1)
            )
            .shadow(color: QuickInkColors.ink.opacity(0.12), radius: 8, x: 0, y: 2)

            // Photo-hint chip rendered as a peer of the FAB so
            // it floats above the bar's top edge alongside the
            // lifted ⚡ disc. `.allowsHitTesting(false)` so the
            // chip never intercepts FAB taps even when overlap
            // is close — discoverability shouldn't block the
            // action it's pointing at.
            if showPhotoHint {
                photoHintChip
                    // FAB visual top sits at y ≈ -16 from the
                    // ZStack top edge (FAB is 64pt aligned to top,
                    // then shifted `.offset(y: -16)`). Push the
                    // chip a further ~52pt up so its bottom edge
                    // clears the FAB with breathing room.
                    .offset(y: -68)
                    .allowsHitTesting(false)
                    .transition(.opacity.combined(with: .scale(scale: 0.92)))
            }

            zapFab
        }
        .padding(.horizontal, QuickInkSpacing.s4)
        .padding(.bottom, QuickInkSpacing.s6)
        .animation(.easeInOut(duration: 0.25), value: showPhotoHint)
    }

    /// Floating chip — "Hold ⚡ for a quick photo." Cream pill
    /// with the same surface + hairline + shadow vocabulary the
    /// bar itself uses, so it reads as part of the same surface
    /// family rather than a foreign callout. No tail / caret in
    /// v1 — the proximity to the FAB carries the affordance.
    @ViewBuilder
    private var photoHintChip: some View {
        Text("Hold ⚡ for a quick photo")
            .font(.system(size: 12, weight: .medium))
            .foregroundStyle(QuickInkColors.ink)
            .padding(.horizontal, QuickInkSpacing.s3)
            .padding(.vertical, QuickInkSpacing.s2)
            .background(
                Capsule().fill(QuickInkColors.surface)
            )
            .overlay(
                Capsule().strokeBorder(QuickInkColors.border, lineWidth: 1)
            )
            .shadow(color: QuickInkColors.ink.opacity(0.18), radius: 6, x: 0, y: 2)
            .accessibilityLabel("Tip: hold the quick-capture button for a quick photo")
    }

    @ViewBuilder
    private func navIcon(
        systemName: String,
        label: String,
        active: Bool,
        /// Horizontal padding inside the chip's coral-soft pill.
        /// Defaults to `s2` (8pt); the Workspace tab passes a
        /// tighter value so the 9-char label clears its
        /// `frame(maxWidth: .infinity)` slot without clipping the
        /// trailing "e" on stock-width devices.
        horizontalPadding: CGFloat = QuickInkSpacing.s2,
        action: @escaping () -> Void
    ) -> some View {
        let tint = active ? QuickInkColors.accent : QuickInkColors.ink
        let bg   = active ? QuickInkColors.accentSoft : Color.clear
        Button(action: action) {
            VStack(spacing: 2) {
                Image(systemName: systemName)
                    .font(.system(size: 20))
                    .foregroundStyle(tint)
                Text(label)
                    // Tighter than the global caption token so the
                    // longest label ("Workspace") fits its
                    // `frame(maxWidth: .infinity)` slot on stock-
                    // width devices without wrapping OR clipping
                    // the trailing "e".
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(tint)
                    .lineLimit(1)
            }
            .padding(.horizontal, horizontalPadding)
            .padding(.vertical, QuickInkSpacing.s2)
            // The fill carries its own shadow so it only renders when
            // the pill is active (Color.clear casts no shadow). This
            // lifts the selected destination above the glass bar
            // without bumping icon contrast — mirrors the Android
            // pillShadow on the active NavIcon.
            .background(
                RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                    .fill(bg)
                    .shadow(
                        color: active ? QuickInkColors.ink.opacity(0.18) : .clear,
                        radius: 4,
                        x: 0,
                        y: 2
                    )
            )
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(label))
        .accessibilityAddTraits(active ? [.isSelected] : [])
    }

    /// Asset-backed nav icon — same shape as `navIcon` but renders a
    /// QuickInk vector asset (template-rendered, tinted via
    /// foregroundStyle). Used for the Library / Stories tabs which
    /// have brand-specific icons in `Assets.xcassets`.
    @ViewBuilder
    private func navIconAsset(
        assetName: String,
        label: String,
        active: Bool,
        /// See `navIcon(horizontalPadding:)`.
        horizontalPadding: CGFloat = QuickInkSpacing.s2,
        action: @escaping () -> Void
    ) -> some View {
        let tint = active ? QuickInkColors.accent : QuickInkColors.ink
        let bg   = active ? QuickInkColors.accentSoft : Color.clear
        Button(action: action) {
            VStack(spacing: 2) {
                Image(assetName, bundle: .module)
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 20, height: 20)
                    .foregroundStyle(tint)
                Text(label)
                    // Tighter than the global caption token so the
                    // longest label ("Workspace") fits its
                    // `frame(maxWidth: .infinity)` slot on stock-
                    // width devices without wrapping OR clipping
                    // the trailing "e".
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(tint)
                    .lineLimit(1)
            }
            .padding(.horizontal, horizontalPadding)
            .padding(.vertical, QuickInkSpacing.s2)
            .background(
                RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                    .fill(bg)
                    .shadow(
                        color: active ? QuickInkColors.ink.opacity(0.18) : .clear,
                        radius: 4,
                        x: 0,
                        y: 2
                    )
            )
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(label))
        .accessibilityAddTraits(active ? [.isSelected] : [])
    }

    /// The signature ⚡ Zap FAB — coral disc with a top→bottom
    /// gradient, lifted ~16pt above the card's top edge so it reads
    /// as a hovering brand mark. Delegates to `onScan` on tap and
    /// `onLongPressScan` on a 0.4s hold. Tap opens the user's last
    /// pill-selected mode (Document or Business Card); long-press
    /// jumps straight into the Photo surface — the canonical
    /// "I just want a photo, fast" shortcut from the spec.
    ///
    /// Implementation note: the disc uses a single
    /// `ExclusiveGesture(LongPress before Tap)` instead of
    /// stacking `.onLongPressGesture` + `.onTapGesture`. SwiftUI
    /// evaluates the OUTERMOST modifier first, which means a
    /// chained `.onLongPressGesture(...).onTapGesture(...)` puts
    /// tap on top and the tap recognizer consumes the touch
    /// sequence before long-press ever gets a chance to fire on a
    /// 0.4s hold. `ExclusiveGesture` is the documented combinator
    /// for "long-press wins, tap is the fallback for quick
    /// releases" and avoids the shadowing problem.
    @ViewBuilder
    private var zapFab: some View {
        let gradient = LinearGradient(
            colors: [QuickInkColors.accent, QuickInkColors.accentDeep],
            startPoint: .top,
            endPoint: .bottom
        )
        let longPress = LongPressGesture(minimumDuration: 0.4)
            .onEnded { _ in
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                onLongPressScan()
            }
        let tap = TapGesture()
            .onEnded {
                onScan()
            }
        ZStack {
            Circle()
                .fill(QuickInkColors.bg)
                .frame(width: 64, height: 64)
                .shadow(color: QuickInkColors.ink.opacity(0.22), radius: 10, x: 0, y: 5)
            Circle()
                .fill(gradient)
                .frame(width: 56, height: 56)
                .shadow(color: QuickInkColors.accent.opacity(0.38), radius: 16, x: 0, y: 8)
            Image(systemName: "bolt")
                .font(.system(size: 32, weight: .semibold))
                .foregroundStyle(QuickInkColors.textOnAccent)
        }
        .offset(y: -16)
        .contentShape(Circle())
        .gesture(longPress.exclusively(before: tap))
        .accessibilityLabel("Quick capture")
        .accessibilityHint("Double tap to scan, long press for photo")
    }
}
