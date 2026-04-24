/*
 * PageDetailScreenVariant1.kt
 * Editorial single-page view — colored breadcrumb header, prose body
 * with tag pills and a pull-quote block, and a floating action bar.
 * Shares [PageDetailViewModel] with the classic screen.
 */

package app.releaf.mobile.features.page

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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.data.domain.Note
import app.releaf.mobile.data.domain.Page
import app.releaf.mobile.data.domain.Photo
import app.releaf.mobile.ui.components.ShelfPalette
import app.releaf.mobile.ui.components.ShelfTheme
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

@Composable
fun PageDetailScreenVariant1(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ShelfPageViewModel = viewModel(factory = ShelfPageViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()

    Box(modifier.fillMaxSize().background(AppColors.Canvas)) {
        when (val s = state) {
            ShelfPageUiState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = app.releaf.mobile.ui.theme.AppAccent.primary)
                }
            }
            is ShelfPageUiState.Failed -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(s.message, style = AppTypography.Body, color = AppColors.TextSecondary)
                }
            }
            is ShelfPageUiState.Loaded -> Loaded(page = s.page, onBack = onBack)
        }
    }
}

@Composable
private fun Loaded(page: Page, onBack: () -> Unit) {
    val palette = ShelfTheme.palette("green")
    val scroll  = rememberScrollState()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Header(palette = palette, onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(horizontal = AppSpacing.s5)
                    .padding(top = AppSpacing.s5, bottom = AppSpacing.s10 + AppSpacing.s6),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s4),
            ) {
                Text(
                    text = (page.capturedOn ?: "").uppercase(),
                    style = AppTypography.Eyebrow,
                    color = AppColors.ThemeGreenDeep,
                )
                Text(
                    text = page.title,
                    color = AppColors.TextPrimary,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                )
                if (page.tags.isNotEmpty()) {
                    TagRow(tags = page.tags)
                }

                val quote = page.notes.firstOrNull { it.body.startsWith("NOTE TO SELF") }
                val prose = page.notes.filter { !it.body.startsWith("NOTE TO SELF") }
                prose.forEach { ProseParagraph(it) }
                if (quote != null) PullQuote(quote, palette = palette)
                if (page.photos.isNotEmpty()) PhotoGrid(page.photos, palette)
            }
        }

        ActionBar(
            pageIndex = 3,
            pageCount = 6,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun Header(palette: ShelfPalette, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.background)
            .padding(horizontal = AppSpacing.s5, vertical = AppSpacing.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = palette.onBackground,
            modifier = Modifier
                .size(20.dp)
                .clickable { onBack() },
        )
        Spacer(Modifier.width(AppSpacing.s2))
        Text("PLANT LOG / CH. 07", style = AppTypography.Eyebrow, color = palette.onBackground,
             modifier = Modifier.weight(1f))
        Text("PAGE 03 / 06", style = AppTypography.Eyebrow, color = palette.onBackground)
    }
}

@Composable
private fun TagRow(tags: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
        tags.forEachIndexed { index, tag ->
            val accent = index < 2
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(if (accent) AppColors.SuccessSoft else AppColors.NeutralSoft)
                    .padding(horizontal = AppSpacing.s3, vertical = 5.dp),
            ) {
                Text(
                    text = tag,
                    style = AppTypography.Tag,
                    color = if (accent) AppColors.GreenText else AppColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun ProseParagraph(note: Note) {
    Text(
        text = note.body,
        color = AppColors.TextPrimary,
        fontSize = 17.sp,
        fontFamily = FontFamily.Serif,
        lineHeight = 24.sp,
    )
}

@Composable
private fun PullQuote(note: Note, palette: ShelfPalette) {
    val parts = note.body.split("\n", limit = 2)
    val header = parts.firstOrNull() ?: "NOTE TO SELF"
    val body   = parts.getOrNull(1).orEmpty()
    Row(
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        modifier = Modifier
            .padding(vertical = AppSpacing.s2)
            .height(IntrinsicSize.Min),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(palette.background)
        )
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
            Text(header, style = AppTypography.Eyebrow, color = AppColors.ThemeGreenDeep)
            Text(
                text = body,
                color = AppColors.TextPrimary,
                fontSize = 17.sp,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                lineHeight = 24.sp,
            )
        }
    }
}

@Composable
private fun PhotoGrid(photos: List<Photo>, palette: ShelfPalette) {
    // Fixed-height 2-up row of tiles. Not using LazyVerticalGrid since
    // the parent Column is vertically scrollable and we only render
    // a small number of photos for the variant-1 editorial layout.
    val pairs = photos.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
        pairs.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
                row.forEach { photo ->
                    PhotoTile(photo = photo, palette = palette,
                              modifier = Modifier.weight(1f).height(180.dp))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PhotoTile(photo: Photo, palette: ShelfPalette, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.md))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(palette.accentSoft, palette.background),
                ),
            ),
    ) {
        photo.caption?.let {
            Text(
                text = it,
                style = AppTypography.Tag,
                color = palette.onBackground,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(AppSpacing.s3),
            )
        }
    }
}

@Composable
private fun ActionBar(pageIndex: Int, pageCount: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(AppColors.Canvas)
            .padding(horizontal = AppSpacing.s5)
            .padding(top = AppSpacing.s3, bottom = AppSpacing.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s4)) {
            Icon(Icons.Filled.Menu, contentDescription = null,
                 tint = AppColors.TextPrimary, modifier = Modifier.size(18.dp))
            Icon(Icons.Outlined.Image, contentDescription = null,
                 tint = AppColors.TextPrimary, modifier = Modifier.size(18.dp))
            Icon(Icons.Filled.Add, contentDescription = null,
                 tint = AppColors.TextPrimary, modifier = Modifier.size(18.dp))
        }

        Spacer(Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            repeat(pageCount) { i ->
                val isActive = i == pageIndex - 1
                Box(
                    modifier = Modifier
                        .size(if (isActive) 10.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (isActive) AppColors.ThemeGreenPrimary
                            else          AppColors.TextPrimary.copy(alpha = 0.85f),
                        ),
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(AppRadius.pill))
                .background(AppColors.ActionPrimary)
                .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Next", style = AppTypography.Button, color = AppColors.OnPrimary)
            Spacer(Modifier.width(AppSpacing.s2))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = AppColors.OnPrimary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
