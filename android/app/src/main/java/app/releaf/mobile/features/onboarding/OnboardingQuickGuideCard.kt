/*
 * OnboardingQuickGuideCard.kt
 *
 * Persistent "Quick Guide" widget rendered at the top of the signed-in
 * Home screen so users can replay the onboarding wizard whenever they
 * want. Visually mirrors the guide card in the design spec (cream bg,
 * coral QUICK GUIDE eyebrow, pill-shaped "Show intro" button).
 */

package app.releaf.mobile.features.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import app.releaf.mobile.ui.theme.AppAccent

@Composable
fun OnboardingQuickGuideCard(onShowIntro: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(OnboardTokens.ModalBg)
            .border(1.dp, OnboardTokens.BorderRest, RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "✦",
                    fontSize = 12.sp,
                    color = AppAccent.primary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.padding(horizontal = 3.dp))
                Text(
                    "QUICK GUIDE",
                    style = OnboardTokens.Badge,
                    color = AppAccent.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "New to Releaf? See how it works.",
                style = OnboardTokens.CtaLabel.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                ),
                color = OnboardTokens.TextPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "A 60-second walkthrough experience.",
                style = OnboardTokens.CtaLabel,
                color = OnboardTokens.TextMuted,
            )
        }
        Spacer(Modifier.padding(horizontal = 8.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(9999.dp))
                .background(AppAccent.primary)
                .clickable { onShowIntro() }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("▶", color = Color.White, fontSize = 10.sp)
            Spacer(Modifier.padding(horizontal = 3.dp))
            Text(
                "Show intro",
                style = OnboardTokens.Button,
                color = Color.White,
            )
        }
    }
}
