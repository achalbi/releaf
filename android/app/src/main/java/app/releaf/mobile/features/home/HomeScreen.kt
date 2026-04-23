/*
 * HomeScreen.kt
 * Signed-in home. Lists notebooks from DriveRepository (fake in skeleton).
 * Each notebook row opens the notebook detail screen via `onOpenNotebook`.
 */

package app.releaf.mobile.features.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.auth.GoogleAuthSession
import app.releaf.mobile.data.domain.Notebook
import app.releaf.mobile.features.onboarding.OnboardingQuickGuideCard
import app.releaf.mobile.ui.components.AppButton
import app.releaf.mobile.ui.components.AppButtonVariant
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.mobile.ui.theme.Card

@Composable
fun HomeScreen(
    session: GoogleAuthSession,
    onOpenNotebook: (String) -> Unit,
    onSignOut: () -> Unit,
    onShowOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val scroll = rememberScrollState()

    Box(modifier.fillMaxSize()) {
        when (val s = state) {
            HomeUiState.Idle, HomeUiState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppAccent.primary)
                }
            }
            is HomeUiState.Failed -> {
                Column(
                    Modifier.fillMaxSize().padding(AppSpacing.s4),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(s.message, style = AppTypography.Body, color = AppColors.TextSecondary)
                    Spacer(Modifier.height(AppSpacing.s3))
                    AppButton(
                        "Try again",
                        onClick = viewModel::load,
                        variant = AppButtonVariant.Secondary,
                        fillWidth = false,
                    )
                }
            }
            is HomeUiState.Loaded -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(AppSpacing.s4),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.s6),
                ) {
                    Header(session = session, onSignOut = onSignOut)
                    OnboardingQuickGuideCard(onShowIntro = onShowOnboarding)
                    // Theme picker lives at the top of Home so
                    // appearance tweaks are one tap away without
                    // digging into Settings.
                    app.releaf.mobile.ui.components.ThemePickerSection()
                    s.notebooks.forEach { nb ->
                        NotebookRow(nb, onClick = { onOpenNotebook(nb.id) })
                    }
                    Spacer(Modifier.height(AppSpacing.s10))
                }
            }
        }
    }
}

@Composable
private fun Header(session: GoogleAuthSession, onSignOut: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
        Text("NOTEBOOKS", style = AppTypography.Eyebrow, color = AppAccent.primary)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                greeting(session),
                style = AppTypography.EditorialTitle,
                color = AppColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Sign out",
                style = AppTypography.Button,
                color = AppAccent.primary,
                modifier = Modifier.clickable { onSignOut() },
            )
        }
    }
}

private fun greeting(session: GoogleAuthSession): String {
    val name = session.displayName?.trim().orEmpty()
    return if (name.isNotEmpty()) "Hi, $name" else "Good morning"
}

@Composable
private fun NotebookRow(notebook: Notebook, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
            Text(notebook.title, style = AppTypography.SectionTitle, color = AppColors.TextPrimary)
            notebook.description?.let {
                Text(it, style = AppTypography.Body, color = AppColors.TextSecondary)
            }
            Text(metaLine(notebook), style = AppTypography.Meta, color = AppColors.TextSecondary)
        }
    }
}

private fun metaLine(n: Notebook): String {
    val chapters = "${n.chapterCount} chapter${if (n.chapterCount == 1) "" else "s"}"
    val pages    = "${n.pageCount} page${if (n.pageCount == 1) "" else "s"}"
    return "$chapters · $pages"
}
