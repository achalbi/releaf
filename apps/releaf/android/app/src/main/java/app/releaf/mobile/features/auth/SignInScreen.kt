/*
 * SignInScreen.kt
 * Signed-out landing page. Four sections stacked vertically:
 *   - Top: brand lockup (leaf + wordmark + tagline + hero headline +
 *          one-line value prop).
 *   - BrandProductHero: notebook + phone-scan mockups inside a card.
 *   - BrandLoopSummary: three Write / Erase / Repeat info rows.
 *   - Bottom: sign-in button + Drive-scope reassurance.
 *
 * Top padding is `AppSpacing.s10 + AppSpacing.s6` (64dp) so the hero breathes under
 * the status bar before the brand lockup begins. The page is now back
 * to vertical scrolling because the BrandLoopSummary pushes total
 * content past a single viewport on most phones; without scroll the
 * sign-in button would be clipped on smaller devices.
 */

package app.releaf.mobile.features.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.ui.components.AppButton
import app.releaf.mobile.ui.components.ReleafLogo
import app.releaf.mobile.ui.components.ReleafLogoRow
import app.releaf.mobile.ui.components.ReleafLogoSize
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

private val BrandGreen = Color(0xFF0B3F26)
private val BrandGreenLight = Color(0xFF7AA874)
private val Paper = Color(0xFFEDE4CF)
private val PaperLine = Color(0xFFCBBF9E)

@Composable
fun SignInScreen(
    state: AuthState,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Canvas + dot-grid come from the parent `ReleafCanvas` wrapper;
    // don't repaint here or it'll cover the texture.
    Box(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.s5)
            .padding(top = AppSpacing.s10 + AppSpacing.s6, bottom = AppSpacing.s6),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s5),
            horizontalAlignment = Alignment.Start,
        ) {
            LandingHeader()
            BrandProductHero()
            BrandLoopSummary()
            ActionArea(state = state, onSignIn = onSignIn)
        }
    }
}

@Composable
private fun LandingHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
        ReleafLogoRow(
            size = ReleafLogoSize.Lg,
            leafGradientStart = BrandGreenLight,
            leafGradientEnd = BrandGreen,
            wordmarkColor = BrandGreen,
        )
        Text(
            text = "WRITE. ERASE. REPEAT.",
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 0.14.em,
            ),
            color = BrandGreen,
        )
        Text(
            text = "Your ideas deserve\nmore than one life.",
            style = TextStyle(
                fontFamily = FontFamily.Serif,
                fontSize = 28.sp,
                lineHeight = 32.sp,
            ),
            color = AppColors.TextPrimary,
        )
        Text(
            text = "Reusable notebook + smart app companion.",
            style = AppTypography.Body,
            color = AppColors.TextSecondary,
        )
    }
}

@Composable
private fun BrandProductHero() {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, shape)
            .padding(AppSpacing.s3),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        NotebookMockup(
            modifier = Modifier
                .weight(1f)
                .height(168.dp),
        )
        PhoneScanMockup(
            modifier = Modifier
                .width(96.dp)
                .height(160.dp),
        )
    }
}

@Composable
private fun BrandLoopSummary() {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, shape)
            .padding(AppSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s4),
    ) {
        Text(
            text = "BUILT FOR THE LOOP",
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.08.em,
            ),
            color = BrandGreen,
        )
        LoopRow(
            title = "Write",
            copy = "Capture thoughts, plans, and pages.",
            icon = Icons.Outlined.Edit,
        )
        LoopRow(
            title = "Erase",
            copy = "Wipe clean with water and start again.",
            icon = Icons.Outlined.WaterDrop,
        )
        LoopRow(
            title = "Repeat",
            copy = "Digitize, search, and share anytime.",
            icon = Icons.Outlined.Refresh,
        )
    }
}

@Composable
private fun NotebookMockup(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(BrandGreen)
            .padding(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Paper)
                    .padding(11.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Plan better tomorrow",
                        style = TextStyle(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                        color = BrandGreen,
                    )
                    repeat(4) { index ->
                        Box(
                            Modifier
                                .fillMaxWidth(if (index == 2) 0.7f else 0.94f)
                                .height(2.5.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(PaperLine),
                        )
                    }
                }
            }
            Text(
                text = "Reusable notebook",
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                color = AppColors.OnAccent,
            )
        }
        ReleafLogo(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 30.dp),
            size = 26.dp,
            filled = true,
            fillGradientStart = BrandGreenLight,
            fillGradientEnd = BrandGreen,
            strokeWidth = 1.dp,
        )
    }
}

@Composable
private fun PhoneScanMockup(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF101610))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(Color.Black.copy(alpha = 0.5f)),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(AppColors.Canvas)
                .padding(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    text = "Project Plan",
                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    color = BrandGreen,
                )
                repeat(3) { index ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(BrandGreen),
                        )
                        Box(
                            modifier = Modifier
                                .width(if (index == 2) 38.dp else 56.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(0xFFD8CBB3)),
                        )
                    }
                }
            }
            ReleafLogo(
                modifier = Modifier
                    .align(Alignment.BottomEnd),
                size = 28.dp,
                filled = true,
                fillGradientStart = Color(0xFFB6D3AA),
                fillGradientEnd = BrandGreenLight,
                strokeWidth = 1.dp,
            )
        }
        Text(
            text = "Scan. Save. Share.",
            style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold),
            color = AppColors.OnAccent,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ActionArea(state: AuthState, onSignIn: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        AppButton(
            text = "Sign in with Google",
            onClick = onSignIn,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state is AuthState.Failed) {
            Text(
                state.message,
                style = AppTypography.Meta,
                color = AppColors.Danger,
            )
        }

        Text(
            "Releaf only sees files it creates in your Drive.",
            style = AppTypography.Meta,
            color = AppColors.TextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ── LoopRow helper ──────────────────────────────────────────────────
// Used by BrandLoopSummary to render a single Write / Erase / Repeat
// row. Takes a Material `ImageVector` so callers can pass any standard
// icon — we use `Icons.Outlined.Edit / WaterDrop / Refresh` from
// material-icons-extended.

@Composable
private fun LoopRow(title: String, copy: String, icon: ImageVector) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(AppColors.GreenSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = BrandGreen,
                modifier = Modifier.size(23.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                color = BrandGreen,
            )
            Text(
                text = copy,
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )
        }
    }
}
