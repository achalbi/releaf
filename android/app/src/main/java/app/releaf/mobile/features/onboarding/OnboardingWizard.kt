/*
 * OnboardingWizard.kt
 *
 * 10-step first-run tour. Presented modally over the signed-in shell
 * (auto on first launch, or on demand via the Home-screen widget).
 *
 * Visual + copy parity with docs/onboarding/source/_onboarding_wizard.html.erb
 * — "Inkcreate" has been renamed to "Releaf" in every string.
 *
 * Invariants:
 *   - Dismiss (skip / finish / CTA card tap) writes a timestamp to
 *     [OnboardingPreferences]; the auto-show check is `hasCompleted`.
 *   - Dots are tappable and jump to any step.
 *   - Back is hidden on step 1; step 10 shows two CTA cards + a centred
 *     Back/Let's go action row.
 */

package app.releaf.mobile.features.onboarding

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.releaf.mobile.ui.theme.AppAccent

enum class OnboardingCta { Notebook, Notepad }

@Composable
fun OnboardingWizard(
    onDismiss: () -> Unit,
    onCta: (OnboardingCta) -> Unit,
) {
    var step by remember { mutableStateOf(1) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        // Backdrop.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x8C1B1B1D)),
            contentAlignment = Alignment.Center,
        ) {
            // Modal card.
            Column(
                modifier = Modifier
                    .widthIn(max = 440.dp)
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .shadow(24.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(OnboardTokens.ModalBg)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 28.dp, end = 28.dp, top = 32.dp, bottom = 28.dp),
            ) {
                // Skip link (top-right).
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onDismiss() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "Skip ✕",
                            style = OnboardTokens.Skip,
                            color = OnboardTokens.TextSubtle,
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Dots(current = step, total = TOTAL_STEPS, onTap = { step = it })
                Spacer(Modifier.height(24.dp))

                StepContent(
                    step = step,
                    onBack   = { if (step > 1) step -= 1 },
                    onNext   = { if (step < TOTAL_STEPS) step += 1 else onDismiss() },
                    onFinish = onDismiss,
                    onCta    = { cta -> onCta(cta); onDismiss() },
                )
            }
        }
    }
}

private const val TOTAL_STEPS = 10

@Composable
private fun Dots(current: Int, total: Int, onTap: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        (1..total).forEach { i ->
            val active = i == current
            val done   = i < current
            val width by animateDpAsState(
                targetValue = if (active) 24.dp else 8.dp,
                animationSpec = tween(200),
                label = "dotWidth",
            )
            val targetColor = when {
                active -> AppAccent.primary
                done   -> AppAccent.primary.copy(alpha = 0.45f)
                else   -> OnboardTokens.BorderRest
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(width = width, height = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(targetColor)
                    .clickable { onTap(i) },
            )
        }
    }
}

@Composable
private fun StepContent(
    step: Int,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit,
    onCta: (OnboardingCta) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (step) {
            1  -> WelcomeIllustration()
            2  -> NotebooksIllustration()
            3  -> NotepadIllustration()
            4  -> PhotosIllustration()
            5  -> VoiceIllustration()
            6  -> TodoIllustration()
            7  -> ScanIllustration()
            8  -> MigrateIllustration()
            9  -> BackupIllustration()
            10 -> DoneIllustration()
        }
        Spacer(Modifier.height(20.dp))

        val content = CONTENT[step - 1]
        if (content.badge != null) {
            Text(
                content.badge,
                style = OnboardTokens.Badge.copy(
                    color = OnboardTokens.TextSubtle,
                ),
                color = OnboardTokens.TextSubtle,
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(
            content.headline,
            style = OnboardTokens.Headline,
            color = OnboardTokens.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            renderBody(content.body),
            style = OnboardTokens.Body,
            color = OnboardTokens.TextBody,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        if (step == TOTAL_STEPS) {
            CtaCards(onCta = onCta)
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                GhostButton(text = "← Back", onClick = onBack)
                Spacer(Modifier.size(10.dp))
                PrimaryButton(text = "Let's go ✓", onClick = onFinish)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (step > 1) {
                    GhostButton(text = "← Back", onClick = onBack)
                    Spacer(Modifier.size(10.dp))
                }
                PrimaryButton(
                    text = if (step == 1) "Get started →" else "Next →",
                    onClick = onNext,
                )
            }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(AppAccent.primary)
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(text, style = OnboardTokens.Button, color = Color.White)
    }
}

@Composable
private fun GhostButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.5.dp, OnboardTokens.BorderRest, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(text, style = OnboardTokens.Button, color = OnboardTokens.TextMuted)
    }
}

@Composable
private fun CtaCards(onCta: (OnboardingCta) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CtaCard(icon = "📓", leadingLine = "Create a", trailingLine = "Notebook") {
            onCta(OnboardingCta.Notebook)
        }
        CtaCard(icon = "📅", leadingLine = "Open today's", trailingLine = "Notepad") {
            onCta(OnboardingCta.Notepad)
        }
    }
}

@Composable
private fun CtaCard(
    icon: String,
    leadingLine: String,
    trailingLine: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(OnboardTokens.CardBg)
            .border(2.dp, OnboardTokens.BorderRest, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(icon, fontSize = 22.sp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                leadingLine,
                style = OnboardTokens.CtaLabel,
                color = OnboardTokens.TextPrimary,
            )
            Text(
                trailingLine,
                style = OnboardTokens.CtaLabel.copy(fontWeight = FontWeight.Bold),
                color = OnboardTokens.TextPrimary,
            )
        }
        Text("→", fontSize = 16.sp, color = AppAccent.primary)
    }
}

// Step copy. Rendered by [StepContent]. `**…**` is picked up by
// [renderBody] and emitted as bold.
private data class StepCopy(val badge: String?, val headline: String, val body: String)

private val CONTENT = listOf(
    StepCopy(
        badge = null,
        headline = "Welcome to Releaf",
        body = "Your workspace for projects, daily notes, voice recordings, scanned documents, and action items — all in one place.",
    ),
    StepCopy(
        badge = "Step 1 of 9",
        headline = "Notebooks for projects",
        body = "Create a notebook for each project. Organise it into **chapters**, then add **pages** with rich notes, photos, and voice recordings — everything in context.",
    ),
    StepCopy(
        badge = "Step 2 of 9",
        headline = "Notepad for daily capture",
        body = "The Notepad gives each day its own **page**. Jot quick thoughts, record voice notes, scan documents, or build a to-do list — without creating a notebook and chapter first.",
    ),
    StepCopy(
        badge = "Step 3 of 9",
        headline = "Photos, your way",
        body = "Add photos directly to any page. Choose your preferred **capture quality** in Settings — balance between image clarity and storage size to suit your workflow.",
    ),
    StepCopy(
        badge = "Step 4 of 9",
        headline = "Voice notes",
        body = "Record a voice note on any page with one tap. Play it back inline, or generate a **transcription on demand** to turn speech into searchable text.",
    ),
    StepCopy(
        badge = "Step 5 of 9",
        headline = "To-do lists",
        body = "Add a checklist to any page. Set a **reminder** on an item to get notified at the right time, or **promote it to a Task** when it needs tracking across your workspace.",
    ),
    StepCopy(
        badge = "Step 6 of 9",
        headline = "Scan → PDF in seconds",
        body = "Point your camera at any document. Releaf detects the edges, lets you crop and enhance it, then saves it as a PDF attached to your page.",
    ),
    StepCopy(
        badge = "Step 7 of 9",
        headline = "Move pages into a Notebook",
        body = "When a Notepad page grows into something worth keeping, move it. Tap **Migrate to Notebook** on any Notepad page to place it into the right chapter — structure added, nothing lost.",
    ),
    StepCopy(
        badge = "Step 8 of 9",
        headline = "Backed up to Google Drive",
        body = "Connect your Google Drive in Settings and Releaf will **back up your data automatically** — notes, voice recordings, photos, and scanned documents all kept safe in your own Drive.",
    ),
    StepCopy(
        badge = "Step 9 of 9",
        headline = "You're all set!",
        body = "Start where it makes sense for you. Everything else will become clear as you go.",
    ),
)

// Minimal `**bold**` inline renderer — the body copy uses that syntax
// verbatim and the web source emits a single <strong> per segment, so
// a literal toggle is enough without pulling in a markdown dep.
private fun renderBody(source: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    var bold = false
    while (i < source.length) {
        if (i + 1 < source.length && source[i] == '*' && source[i + 1] == '*') {
            if (bold) {
                pop()
            } else {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            }
            bold = !bold
            i += 2
        } else {
            append(source[i])
            i += 1
        }
    }
    if (bold) pop()
}
