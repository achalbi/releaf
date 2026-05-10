/*
 * ProfileDrawerOverlay.kt
 *
 * Side-panel drawer that slides in from the leading edge when the
 * user taps the home avatar. Mirror of Releaf's `HomeDrawerContent`
 * (apps/releaf/android/app/.../features/home/HomeScreen.kt) — same
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

package app.quickink.mobile.features.home

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.R
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkSpacing
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest

/**
 * Slide-in profile drawer. Hosted by [HomeScreen] as a fullscreen
 * sibling of the main content; visibility is animated by the parent
 * (the inner views render at full opacity). Tapping the right-side
 * scrim invokes [onClose]; the inner sheet is fixed at 300dp wide.
 *
 * Menu order:
 *   1. Profile   — photo / phone / punchline editor.
 *   2. Library   — full scan gallery (same destination as the
 *                  bottom-nav Library tab; redundant on purpose so
 *                  the user can reach it without dismissing the
 *                  drawer first).
 *   3. Search    — OCR search (same destination as the bottom-nav
 *                  Search tab).
 *   4. Settings  — theme / sync / account.
 *
 * Library + Search use the same brand drawable assets as the bottom
 * nav (`R.drawable.ic_note` / `R.drawable.ic_search`) so the icons
 * match across the two surfaces. Profile + Settings keep their
 * Material vector icons because the bottom nav doesn't surface
 * Profile and uses a different (Material) Settings icon too.
 */
@Composable
fun ProfileDrawerOverlay(
    displayName: String,
    email: String,
    profilePhotoUri: String,
    onClose: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
) {
    val colors = LocalQuickInkColors.current

    Row(modifier = Modifier.fillMaxSize()) {
        // Sheet (300dp panel, cream surface).
        Column(
            modifier = Modifier
                .width(300.dp)
                .fillMaxHeight()
                .background(colors.surface),
        ) {
            BannerHeader(
                displayName     = displayName,
                email           = email,
                profilePhotoUri = profilePhotoUri,
            )

            Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Spacer(Modifier.height(QuickInkSpacing.s3))

                DrawerRow(
                    glyphTint  = colors.accent,
                    glyphIcon  = Icons.Filled.Person,
                    label      = "Profile",
                    meta       = "photo · phone · punchline",
                    onClick    = onOpenProfile,
                )
                DashedInkSeparator()
                DrawerRowAsset(
                    glyphTint   = colors.accent,
                    drawableId  = R.drawable.ic_note,
                    label       = "Library",
                    meta        = "all your scans · grid · search",
                    onClick     = onOpenLibrary,
                )
                DashedInkSeparator()
                DrawerRowAsset(
                    glyphTint   = colors.accent,
                    drawableId  = R.drawable.ic_search,
                    label       = "Search",
                    meta        = "find by OCR text · category",
                    onClick     = onOpenSearch,
                )
                DashedInkSeparator()
                DrawerRow(
                    glyphTint  = colors.accentDeep,
                    glyphIcon  = Icons.Filled.Settings,
                    label      = "Settings",
                    meta       = "theme · sync · account",
                    onClick    = onOpenSettings,
                )
            }

            SignOutFooter(onSignOut = onSignOut)
        }

        // Tap-to-dismiss scrim — fills the rest of the screen.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(onClick = onClose),
        )
    }
}

// ================================================================== Banner header

/**
 * Deep coral banner with faint ruled-paper lines drawn behind a
 * centered avatar + name + email column. Avatar bumped from 44dp →
 * 88dp (2×) so it reads as the banner's anchor rather than a leading
 * chip; banner height bumped from 175 → 240dp to fit the larger
 * avatar + stacked text without crowding. Mirror of iOS
 * [ProfileDrawerOverlay.swift]'s `bannerHeader`.
 */
@Composable
private fun BannerHeader(
    displayName: String,
    email: String,
    profilePhotoUri: String,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.accentDeep),
    ) {
        // Faint ruled lines + a tiny "ink mark" behind the avatar
        // column — the QuickInk equivalent of Releaf's tree
        // silhouettes. Line count bumped 9 → 11 to keep the same
        // visual cadence in the taller (240dp) banner.
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(240.dp),
        ) {
            val w = size.width
            val h = size.height
            val lineCount = 11
            for (i in 1..lineCount) {
                val y = h * i.toFloat() / (lineCount + 1).toFloat()
                drawLine(
                    color = Color.White.copy(alpha = 0.10f),
                    start = Offset(0f, y),
                    end   = Offset(w, y),
                    strokeWidth = 1f,
                    cap = StrokeCap.Round,
                )
            }
            // Stylised inkblot — soft squircle in the bottom-right.
            // Kept off-axis so it doesn't fight the centered avatar.
            val markPath = Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        left   = w - 56f,
                        top    = h - 40f,
                        right  = w - 24f,
                        bottom = h - 16f,
                        radiusX = 8f,
                        radiusY = 8f,
                    )
                )
            }
            drawPath(markPath, Color.White.copy(alpha = 0.10f))
        }

        // Centered avatar + name + email column. Sits inside the
        // status-bar inset so the avatar clears the notch / clock.
        //
        // Outer Column spacing widened from s2 → s4 so the 132dp
        // avatar gets visible breathing room before the name —
        // tight 8dp spacing made the heading kiss the avatar's
        // bottom edge. Inner Column hosts name + email so they stay
        // close to each other without the whole stack inheriting the
        // outer spacing.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = QuickInkSpacing.s4)
                .padding(top = QuickInkSpacing.s4),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s4),
        ) {
            // Avatar — coral-soft disc with photo / initial / glyph.
            // 132dp = 3× the original 44dp so it reads as the
            // banner's anchor. Inner glyph + initial sizes scaled
            // accordingly (~60% of the disc) so the figure doesn't
            // float in an oversized halo.
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .clip(CircleShape)
                    .background(colors.accentSoft)
                    .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                // First-initial / AccountCircle fallback. Used directly
                // when there's no profile photo AND as the loading /
                // error slot for SubcomposeAsyncImage below — a stale
                // URI pointing at a deleted file (profilePhotoUri
                // persists in SharedPreferences across reinstalls)
                // would otherwise leave the cream disc empty, which
                // is exactly the bug this fallback fixes.
                val fallback: @Composable () -> Unit = {
                    val initial = displayName.trim().firstOrNull()?.uppercase()
                    if (initial != null) {
                        // Explicit baseline-based positioning. The
                        // line-height + Alignment.Center approach was
                        // unreliable — Compose's "trimmed line box"
                        // still includes descender space below the
                        // baseline (where 'g' / 'p' tails would go),
                        // so a single capital "A" (no descender) gets
                        // centred too high inside that box, leaving
                        // the visible glyph below the disc's optical
                        // centre. paddingFromBaseline pins the
                        // baseline at a known y so the cap-height
                        // midline (the visible glyph centroid for
                        // capital letters) lands exactly on the disc
                        // geometric centre.
                        //
                        // Math (132dp disc, 54sp font, Roboto-ish
                        // cap-height ≈ 0.71 × fontSize ≈ 38dp):
                        //   - disc centre y = 66dp
                        //   - A's visual centre = baseline − 19dp
                        //   - baseline = 66 + 19 = 85dp from disc top
                        //   - bottom = 132 − 85 = 47dp
                        // Both top and bottom are specified so the
                        // modifier height = 132dp = disc, which means
                        // contentAlignment.Center can't shift the
                        // baseline off the computed position.
                        Text(
                            text     = initial,
                            color    = colors.accent,
                            fontSize = 54.sp,
                            textAlign = TextAlign.Center,
                            style    = TextStyle(
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                            ),
                            modifier = Modifier.paddingFromBaseline(
                                top    = 85.dp,
                                bottom = 47.dp,
                            ),
                        )
                    } else {
                        Icon(
                            imageVector       = Icons.Filled.AccountCircle,
                            contentDescription = null,
                            tint              = colors.accent,
                            modifier          = Modifier.size(84.dp),
                        )
                    }
                }
                if (profilePhotoUri.isNotEmpty()) {
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(Uri.parse(profilePhotoUri))
                            // Same cache-disabled posture as the home
                            // header avatar — file is overwritten in
                            // place on each pick, so caching staleness
                            // is the bug to avoid.
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .diskCachePolicy(CachePolicy.DISABLED)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier.fillMaxSize().clip(CircleShape),
                        loading = { fallback() },
                        error   = { fallback() },
                    )
                } else {
                    fallback()
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text  = displayName.ifBlank { "QuickInk" },
                    style = type.heading,
                    color = colors.textOnAccent,
                )
                if (email.isNotBlank()) {
                    Text(
                        text  = email,
                        style = type.meta,
                        color = colors.textOnAccent.copy(alpha = 0.80f),
                    )
                }
            }
        }
    }
}

// ================================================================== Drawer row

@Composable
private fun DrawerRow(
    glyphTint: Color,
    glyphIcon: ImageVector,
    label: String,
    meta: String,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        // Coral disc with the row's glyph — QuickInk's counterpart to
        // Releaf's hand-drawn leaf glyph.
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(glyphTint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector       = glyphIcon,
                contentDescription = null,
                tint              = glyphTint,
                modifier          = Modifier.size(16.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = label, style = type.body, color = colors.ink)
            Text(text = meta,  style = type.meta, color = colors.inkSoft)
        }
    }
}

/**
 * Drawable-asset variant of [DrawerRow]. Same row layout (coral disc
 * glyph + label + meta), but the icon is a `painterResource` from
 * `res/drawable/ic_*.xml` rather than a Material `ImageVector`. Used
 * by the Library / Search rows so their glyphs match the QuickInk
 * brand assets the bottom nav already uses.
 */
@Composable
private fun DrawerRowAsset(
    glyphTint: Color,
    drawableId: Int,
    label: String,
    meta: String,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(glyphTint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter            = painterResource(id = drawableId),
                contentDescription = null,
                tint               = glyphTint,
                modifier           = Modifier.size(16.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = label, style = type.body, color = colors.ink)
            Text(text = meta,  style = type.meta, color = colors.inkSoft)
        }
    }
}

// ================================================================== Dashed separator

@Composable
private fun DashedInkSeparator() {
    val colors = LocalQuickInkColors.current
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s5)
            .height(1.dp),
    ) {
        val y = size.height / 2f
        drawLine(
            color = colors.border,
            start = Offset(0f, y),
            end   = Offset(size.width, y),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f),
            cap = StrokeCap.Round,
        )
    }
}

// ================================================================== Sign-out footer

/**
 * Full-width accentDeep strip with a chevron + "Sign out" label. Tap
 * anywhere on the strip signs out. Mirror of Releaf's earth-brown
 * footer with grass blades — here the decorative strip along the top
 * is a cream "torn paper" edge instead of grass.
 */
@Composable
private fun SignOutFooter(onSignOut: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.accentDeep)
            .clickable(onClick = onSignOut),
    ) {
        // Cream "torn paper" zigzag along the top edge — a
        // QuickInk-flavoured replacement for Releaf's grass blades.
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
        ) {
            val w = size.width
            val h = size.height
            val segments = 18
            val amp = 1.5f
            val path = Path().apply {
                moveTo(0f, h)
                for (i in 0..segments) {
                    val x = w * i.toFloat() / segments.toFloat()
                    val y = h - amp - amp * (if (i % 2 == 0) 0f else 1.4f)
                    lineTo(x, y)
                }
                lineTo(w, h)
                close()
            }
            drawPath(path, colors.surface.copy(alpha = 0.9f))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .padding(horizontal = QuickInkSpacing.s5)
                .padding(top = 12.dp)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
        ) {
            // Stroked "→" chevron drawn in cream — same posture as
            // Releaf's leaf-stem chevron.
            Canvas(modifier = Modifier.size(20.dp)) {
                val w = size.width
                val h = size.height
                drawLine(
                    color = colors.textOnAccent,
                    start = Offset(w * 0.20f, h * 0.50f),
                    end   = Offset(w * 0.85f, h * 0.50f),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = colors.textOnAccent,
                    start = Offset(w * 0.55f, h * 0.25f),
                    end   = Offset(w * 0.85f, h * 0.50f),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = colors.textOnAccent,
                    start = Offset(w * 0.55f, h * 0.75f),
                    end   = Offset(w * 0.85f, h * 0.50f),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round,
                )
            }
            Text(
                text  = "Sign out",
                style = type.body,
                color = colors.textOnAccent,
            )
        }
    }
}
