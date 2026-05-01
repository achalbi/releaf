/*
 * HomeScreen.kt
 *
 * QuickInk's camera-first Home. Per QUICKINK_PROPOSAL.md §6.4 the
 * home opens directly to the document scanner — there's no
 * intermediate dashboard, the value prop is "tap once and you're
 * scanning." For Slice 3 the home is just a single big Scan CTA;
 * recent captures + library navigation come in Slice 4.
 *
 * The scanner is launched via `rememberDocumentScannerLauncher`
 * from `:shared:scan` (rather than auto-launching on first
 * appear). Auto-launch — "tapping the home tab opens the scanner"
 * — comes with the bottom-nav wiring in Slice 6.
 *
 * Mirror of iOS `HomeScreen.swift`.
 */

package app.quickink.mobile.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.quickink.mobile.features.onboarding.OnboardingPrimaryButton
import app.quickink.mobile.features.scan.ScanFlowController
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.shared.scan.rememberDocumentScannerLauncher

@Composable
fun HomeScreen(
    controller: ScanFlowController,
    onOpenNotes: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scannerLauncher = rememberDocumentScannerLauncher(
        onResult = { result -> controller.onScanComplete(result) },
        onError  = { /* TODO — surface a toast or inline error UI */ },
    )

    // Camera-first auto-launch — opens the scanner once on the
    // first time Home becomes visible per app launch (per
    // QUICKINK_PROPOSAL.md §6.4). `rememberSaveable` so config
    // changes (rotation, dark-mode flip) don't re-trigger the
    // scanner; survives across recompositions for the lifetime
    // of this Activity.
    var hasAutoLaunched by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!hasAutoLaunched) {
            hasAutoLaunched = true
            scannerLauncher.launch()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Canvas),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top bar — Notes + Settings icons in the top-right.
        // Mirror of iOS HomeScreen's toolbar; richer toolbar
        // (search etc.) is later.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.s2, vertical = AppSpacing.s2),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = onOpenNotes) {
                Icon(
                    imageVector  = Icons.AutoMirrored.Filled.ListAlt,
                    contentDescription = "Notes",
                    tint         = AppColors.TextPrimary,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector  = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint         = AppColors.TextPrimary,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Icon(
            imageVector  = Icons.Filled.DocumentScanner,
            contentDescription = null,
            tint         = AppColors.ThemeGreenPrimary,
            modifier     = Modifier.size(96.dp),
        )

        Spacer(Modifier.size(AppSpacing.s5))

        Text(
            text  = "Scan a document",
            style = AppTypography.PageTitle,
            color = AppColors.TextPrimary,
        )

        Spacer(Modifier.size(AppSpacing.s2))

        Text(
            text     = "Capture pages, search the text, never lose them.",
            style    = AppTypography.Body,
            color    = AppColors.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = AppSpacing.s5),
        )

        Spacer(Modifier.weight(1f))

        // Reuse the onboarding CTA shape — same coral pill, same
        // padding, lives in :features:onboarding because that's
        // where it landed first; promotes to a shared place if
        // more screens adopt it.
        OnboardingPrimaryButton(
            label   = "Scan",
            onClick = { scannerLauncher.launch() },
        )

        Spacer(Modifier.size(AppSpacing.s5))
    }
}
