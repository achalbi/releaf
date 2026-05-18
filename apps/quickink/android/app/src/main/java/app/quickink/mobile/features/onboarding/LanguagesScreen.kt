/*
 * LanguagesScreen.kt
 *
 * Onboarding step 4/4 — lets the user pick the languages they'll
 * speak in voice notes. Drives the transcription pipeline: with one
 * language picked we hand it to Whisper directly; with multiple,
 * Whisper auto-detects and we constrain to the allowlist on read.
 *
 * Defaults are seeded by `TranscriptionLanguages.defaultAllowlist`
 * (device locale + English) so a user in India sees Hindi + English
 * already checked. The user must keep at least one chip selected
 * before the Continue CTA enables — a zero-pick state breaks the
 * downstream LID pipeline.
 *
 * The picked set lives on `OnboardingState.selectedLanguageCodes`
 * during the flow and gets committed into
 * `profile_settings.transcription_languages` once SignIn succeeds
 * and we know the user id.
 *
 * Mirror of iOS `LanguagesScreen.swift`.
 */

package app.quickink.mobile.features.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.data.voicenote.TranscriptionLanguage
import app.quickink.mobile.data.voicenote.TranscriptionLanguages
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkSpacing

@Composable
fun LanguagesScreen(
    state: OnboardingState,
    onContinue: () -> Unit,
) {
    val selectedCodes = state.selectedLanguageCodes

    OnboardingScaffold(
        title      = "What will you",
        titleAccent = "speak?",
        subtitle   = "Pick the languages you'll speak in voice notes — we'll transcribe each recording in one of them. You can change this in Settings later.",
        ctaLabel   = "Continue",
        stepIndex  = 3,
        totalSteps = 4,
        onContinue = onContinue,
    ) {
        LanguagesChipGrid(
            selectedCodes = selectedCodes,
            onToggle      = { language ->
                val next = selectedCodes.toMutableSet()
                if (language.code in next) {
                    if (next.size > 1) next.remove(language.code)
                } else {
                    next.add(language.code)
                }
                state.selectedLanguageCodes = next
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguagesChipGrid(
    selectedCodes: Set<String>,
    onToggle: (TranscriptionLanguage) -> Unit,
) {
    Column(
        modifier            = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s5),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FlowRow(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2, Alignment.CenterHorizontally),
            verticalArrangement   = Arrangement.spacedBy(QuickInkSpacing.s2),
        ) {
            TranscriptionLanguages.supported.forEach { language ->
                LanguageOnboardingChip(
                    language = language,
                    selected = language.code in selectedCodes,
                    onClick  = { onToggle(language) },
                )
            }
        }
    }
}

@Composable
private fun LanguageOnboardingChip(
    language: TranscriptionLanguage,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val shape = RoundedCornerShape(percent = 50)
    val bg = if (selected) colors.accent else Color.White.copy(alpha = 0.85f)
    val borderColor = if (selected) colors.accent else colors.accent.copy(alpha = 0.25f)
    val textColor = if (selected) colors.textOnAccent else colors.ink
    val nativeColor =
        if (selected) colors.textOnAccent.copy(alpha = 0.85f) else colors.inkSoft
    Column(
        modifier = Modifier
            .clip(shape)
            .background(bg, shape)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text       = language.englishName,
            style      = type.body.copy(fontWeight = FontWeight.Medium, fontSize = 13.sp),
            color      = textColor,
        )
        if (language.nativeName != language.englishName) {
            Text(
                text     = language.nativeName,
                fontSize = 11.sp,
                color    = nativeColor,
            )
        }
    }
}
