/*
 * LocationPermissionScreen.kt
 *
 * Onboarding step 3/4 — asks the user for `ACCESS_COARSE_LOCATION`
 * so the scan + import flows can attach the city / area to each
 * capture's Details card. Tapping "Allow location" launches the
 * system permission contract; the flow advances on whichever way
 * the user resolves the dialog. A "Skip for now" affordance lets
 * users move on without granting — they can flip the toggle back
 * on later in Settings, which will trigger the system prompt
 * lazily at first scan.
 *
 * Mirror of iOS `LocationPermissionScreen.swift`. Reuses
 * `OnboardingScaffold` so the visual rhythm + page-indicator dots
 * match the other steps.
 */

package app.quickink.mobile.features.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkSpacing

@Composable
fun LocationPermissionScreen(onContinue: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    // Disables the CTA between the launch + the result so a double-
    // tap doesn't queue two dialogs. The contract's callback fires
    // synchronously on the result; we flip back to enabled only if
    // we don't auto-advance (we always do — both grant and deny
    // continue the flow).
    var isRequesting by remember { mutableStateOf(false) }

    // Permission contract for ACCESS_COARSE_LOCATION. Coarse is what
    // the scan flow needs (city / sub-locality precision), and asking
    // for coarse-only avoids the multi-step "precise vs approximate"
    // chooser fine-location triggers on Android 12+.
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // Either grant or deny advances — denied users can re-grant
        // later from Settings. We don't show an "Are you sure?" gate
        // because permission can always be re-asked.
        isRequesting = false
        onContinue()
    }

    OnboardingScaffold(
        title      = "Where in the world?",
        subtitle   = "QuickInk attaches the area and city to each scan so you can find them by place later. You can change this anytime in Settings.",
        ctaLabel   = if (isRequesting) "Requesting…" else "Allow location",
        stepIndex  = 2,
        onContinue = {
            isRequesting = true
            launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        },
    ) {
        LocationIllustration()
    }

    // "Skip" escape hatch — lets users continue without granting.
    // Sits as an overlay at the bottom of the screen, anchored
    // beneath the OnboardingScaffold's coral CTA.
    Box(
        modifier         = Modifier.padding(bottom = QuickInkSpacing.s5),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            text     = "Skip for now",
            style    = type.label,
            color    = colors.inkSoft,
            modifier = Modifier
                .clickable(onClick = onContinue)
                .padding(QuickInkSpacing.s2),
        )
    }
}

/**
 * Coral location-pin glyph wrapped in a soft accent circle. Sized to
 * match `CameraIllustration`'s visual weight so the two onboarding
 * permission steps read as a pair. Mirror of iOS
 * `LocationIllustration` in `LocationPermissionScreen.swift`.
 */
@Composable
private fun LocationIllustration() {
    val colors = LocalQuickInkColors.current
    Box(
        modifier         = Modifier
            .size(220.dp)
            .clip(CircleShape)
            .background(colors.accentSoft),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector        = Icons.Outlined.LocationOn,
            contentDescription = null,
            tint               = colors.accent,
            modifier           = Modifier.size(96.dp),
        )
    }
}
