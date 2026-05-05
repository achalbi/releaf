/*
 * ProfileDrawerOverlay.swift
 *
 * Side-panel drawer that slides in from the leading edge when the
 * user taps the home avatar. Mirror of Releaf's `HomeDrawerOverlay`
 * (apps/releaf/ios/Releaf/Features/Home/HomeScreen.swift) — same
 * structural pattern (banner header with avatar + name, cream menu
 * sheet with glyph + meta rows, dashed separators, full-width
 * sign-out footer) restyled in QuickInk's coral/cream/ink palette
 * instead of Releaf's forest/leaf branding.
 *
 * Two destinations land in the menu sheet itself:
 *   - Profile  → photo / phone / punchline editor
 *   - Settings → existing Settings surface (theme · sync · account)
 *
 * Sign out lives in the footer — same posture as Releaf, where the
 * earth-tone bottom strip is a single tappable Sign-out target.
 */

import SwiftUI
import ReleafCoreAuth
import ReleafCoreDesignSystem

struct ProfileDrawerOverlay: View {
    let displayName: String
    let email: String
    let profilePhotoUri: String
    let onClose: () -> Void
    let onOpenProfile: () -> Void
    /// Navigate to the Library tab. Mirrors the Android drawer's
    /// "Library" row added so the two platforms surface the same
    /// shortcut set from the avatar drawer.
    let onOpenLibrary: () -> Void
    /// Navigate to the Search tab. Same parity as `onOpenLibrary`.
    let onOpenSearch: () -> Void
    let onOpenSettings: () -> Void
    let onSignOut: () -> Void

    /// Top safe-area inset — read from `UIApplication` so the
    /// banner can bleed behind the status bar / notch while the
    /// avatar row stays inside the safe area. Same trick Releaf
    /// uses in its `HomeDrawerOverlay`.
    private var topInset: CGFloat {
        UIApplication.shared
            .connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first(where: \.isKeyWindow)?
            .safeAreaInsets.top ?? 44
    }

    private var bottomInset: CGFloat {
        UIApplication.shared
            .connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first(where: \.isKeyWindow)?
            .safeAreaInsets.bottom ?? 34
    }

    var body: some View {
        HStack(spacing: 0) {
            sheet
                // `.frame(width: 300)` (fixed) rather than
                // `.frame(maxWidth: 300)` (which caps but lets the
                // sheet shrink to its intrinsic content width). The
                // SwiftUI intrinsic-size path was rendering the iOS
                // drawer at ~250pt while Android's `.width(300.dp)`
                // pinned it at exactly 300dp — same value, different
                // measurement contract. Pin the iOS frame so the two
                // platforms hit the same drawer width.
                .frame(width: 300)
                .frame(maxHeight: .infinity)
                .background(QuickInkColors.surface)

            // Tap-to-dismiss scrim — fills the rest of the screen.
            Color.black.opacity(0.35)
                .onTapGesture(perform: onClose)
        }
        .edgesIgnoringSafeArea(.all)
    }

    // MARK: - Sheet

    private var sheet: some View {
        VStack(alignment: .leading, spacing: 0) {
            bannerHeader

            VStack(alignment: .leading, spacing: 0) {
                Spacer().frame(height: QuickInkSpacing.s3)

                DrawerRow(
                    glyphTint: QuickInkColors.accent,
                    glyphSymbol: "person.crop.circle",
                    label: "Profile",
                    meta:  "photo · phone · punchline",
                    action: onOpenProfile
                )
                DashedInkSeparator()
                DrawerRow(
                    glyphTint: QuickInkColors.accent,
                    glyphSymbol: "doc.text",
                    label: "Library",
                    meta:  "all your scans · grid · search",
                    action: onOpenLibrary
                )
                DashedInkSeparator()
                DrawerRow(
                    glyphTint: QuickInkColors.accent,
                    glyphSymbol: "magnifyingglass",
                    label: "Search",
                    meta:  "find by OCR text · category",
                    action: onOpenSearch
                )
                DashedInkSeparator()
                DrawerRow(
                    glyphTint: QuickInkColors.accentDeep,
                    glyphSymbol: "gearshape",
                    label: "Settings",
                    meta:  "theme · sync · account",
                    action: onOpenSettings
                )
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Spacer(minLength: 0)

            footer
        }
    }

    // MARK: - Banner header

    /// 240pt + top-inset deep banner: coral-deep background with a
    /// subtle ruled-paper line pattern drawn behind a centered avatar
    /// + name + email column. The earlier layout placed the avatar
    /// on the leading edge with the name to its right; this revision
    /// stacks them on the centre axis with the avatar 2× the original
    /// size (44 → 88pt) so it reads as a "passport-style" identity
    /// card rather than a leading-aligned chip. Banner height bumped
    /// 175 → 240pt to accommodate the larger avatar + the stacked
    /// text without crowding.
    private var bannerHeader: some View {
        ZStack {
            QuickInkColors.accentDeep
            // Faint ruled-paper lines drawn over the coral, so the
            // banner reads as ink-on-page instead of a flat color.
            Canvas { ctx, size in
                let w = size.width
                let h = size.height
                let lineCount = 11
                for i in 1...lineCount {
                    let y = h * CGFloat(i) / CGFloat(lineCount + 1)
                    var line = Path()
                    line.move(to: CGPoint(x: 0, y: y))
                    line.addLine(to: CGPoint(x: w, y: y))
                    ctx.stroke(
                        line,
                        with: .color(QuickInkColors.textOnAccent.opacity(0.10)),
                        style: StrokeStyle(lineWidth: 0.7, lineCap: .round)
                    )
                }
                // Stylised inkblot in the bottom-right — a tiny brand
                // stamp. Kept off-axis so it doesn't fight the
                // centered avatar above.
                let markRect = CGRect(
                    x: w - 56,
                    y: h - 40,
                    width: 32,
                    height: 24
                )
                var mark = Path()
                mark.addRoundedRect(in: markRect, cornerSize: CGSize(width: 8, height: 8))
                ctx.fill(
                    mark,
                    with: .color(QuickInkColors.textOnAccent.opacity(0.10))
                )
            }

            // Centered avatar + name + email column. Anchored to the
            // top of the banner (just below the status-bar inset)
            // to match the Android mirror's posture.
            //
            // VStack spacing widened from s2 → s4 so the larger 132pt
            // avatar gets visible breathing room before the name —
            // tight 8pt spacing made the heading kiss the avatar's
            // bottom edge.
            VStack(spacing: QuickInkSpacing.s4) {
                avatarView
                VStack(spacing: 2) {
                    Text(displayName.isEmpty ? "QuickInk" : displayName)
                        .font(QuickInkText.heading)
                        .foregroundStyle(QuickInkColors.textOnAccent)
                        .lineLimit(1)
                        .multilineTextAlignment(.center)
                    if !email.isEmpty {
                        Text(email)
                            .font(QuickInkText.meta)
                            .foregroundStyle(QuickInkColors.textOnAccent.opacity(0.80))
                            .lineLimit(1)
                            .multilineTextAlignment(.center)
                    }
                }
            }
            .padding(.horizontal, QuickInkSpacing.s4)
            .padding(.top, topInset + QuickInkSpacing.s4)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
        .frame(height: 240 + topInset)
        .clipped()
    }

    /// Avatar 132pt — bumped again from 88 to give it real presence
    /// as the banner's anchor. Inner glyph + initial sizes scaled
    /// proportionally so the figure stays well-balanced inside the
    /// larger ring (the figure still occupies ~60% of the disc, not
    /// floating inside an oversized halo).
    @ViewBuilder
    private var avatarView: some View {
        ZStack {
            Circle()
                .fill(QuickInkColors.accentSoft)
                .frame(width: 132, height: 132)
            if let img = avatarUIImage {
                Image(uiImage: img)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 132, height: 132)
                    .clipShape(Circle())
            } else if let initial = displayNameInitial {
                Text(initial)
                    .font(QuickInkFont.serif(54, weight: .light))
                    .foregroundStyle(QuickInkColors.accent)
            } else {
                Image(systemName: "person.crop.circle.fill")
                    .font(.system(size: 84))
                    .foregroundStyle(QuickInkColors.accent)
            }
        }
        .overlay(
            Circle().stroke(QuickInkColors.textOnAccent.opacity(0.6), lineWidth: 1)
        )
    }

    private var avatarUIImage: UIImage? {
        guard !profilePhotoUri.isEmpty else { return nil }
        let path: String? = {
            if let url = URL(string: profilePhotoUri), url.isFileURL { return url.path }
            return profilePhotoUri
        }()
        guard let path else { return nil }
        return UIImage(contentsOfFile: path)
    }

    private var displayNameInitial: String? {
        let trimmed = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let first = trimmed.first else { return nil }
        return String(first).uppercased()
    }

    // MARK: - Footer (sign-out strip)

    /// 80pt + bottom-inset accentDeep strip with a small leading
    /// chevron + "Sign out" label. Tap anywhere on the strip signs
    /// out. Mirror of Releaf's earth-brown footer.
    private var footer: some View {
        Button(action: onSignOut) {
            ZStack(alignment: .top) {
                QuickInkColors.accentDeep

                // Decorative ink-stroke "tear" along the top edge —
                // QuickInk's substitute for Releaf's grass blades.
                // A simple horizontal squiggle drawn in cream.
                Canvas { ctx, size in
                    let w = size.width
                    let h = size.height
                    var path = Path()
                    let segments = 18
                    let amp: CGFloat = 1.5
                    path.move(to: CGPoint(x: 0, y: h))
                    for i in 0...segments {
                        let x = w * CGFloat(i) / CGFloat(segments)
                        let y = h - amp - amp * (i.isMultiple(of: 2) ? 0 : 1.4)
                        path.addLine(to: CGPoint(x: x, y: y))
                    }
                    path.addLine(to: CGPoint(x: w, y: h))
                    path.closeSubpath()
                    ctx.fill(path, with: .color(QuickInkColors.surface.opacity(0.9)))
                }
                .frame(height: 6)
                .frame(maxWidth: .infinity)

                HStack(spacing: QuickInkSpacing.s3) {
                    Image(systemName: "arrow.right")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(QuickInkColors.textOnAccent)
                        .frame(width: 20, height: 20)
                    Text("Sign out")
                        .font(QuickInkText.body)
                        .foregroundStyle(QuickInkColors.textOnAccent)
                    Spacer()
                }
                .padding(.horizontal, QuickInkSpacing.s5)
                .padding(.top, 28)
                .padding(.bottom, bottomInset)
                .frame(maxHeight: .infinity, alignment: .top)
            }
            .frame(height: 80 + bottomInset)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Drawer row (glyph + label + metadata)

private struct DrawerRow: View {
    let glyphTint: Color
    let glyphSymbol: String
    let label: String
    let meta: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .center, spacing: QuickInkSpacing.s3) {
                // Coral disc holding an SF Symbol — QuickInk's
                // counterpart to Releaf's hand-drawn leaf glyph.
                ZStack {
                    Circle()
                        .fill(glyphTint.opacity(0.18))
                        .frame(width: 30, height: 30)
                    Image(systemName: glyphSymbol)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(glyphTint)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(label)
                        .font(QuickInkText.body)
                        .foregroundStyle(QuickInkColors.ink)
                    Text(meta)
                        .font(QuickInkText.meta)
                        .foregroundStyle(QuickInkColors.inkSoft)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, QuickInkSpacing.s5)
            .padding(.vertical, QuickInkSpacing.s3)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Dashed separator

private struct DashedInkSeparator: View {
    var body: some View {
        Canvas { ctx, size in
            let y = size.height / 2
            var line = Path()
            line.move(to: CGPoint(x: 0, y: y))
            line.addLine(to: CGPoint(x: size.width, y: y))
            ctx.stroke(
                line,
                with: .color(QuickInkColors.border),
                style: StrokeStyle(
                    lineWidth: 1.0,
                    lineCap: .round,
                    dash: [6, 5]
                )
            )
        }
        .frame(height: 1)
        .padding(.horizontal, QuickInkSpacing.s5)
    }
}
