/*
 * SmartCollectionAppearance.kt
 *
 * Visual palette + icon mapping for the smart-collection card.
 * `SmartCollectionEntity.icon` is a free-form TEXT slug — the
 * brief's design system uses Tabler icon names like `"ti-receipt"`,
 * but the in-app surface ships Material icons, so this file
 * keeps a small Material-flavored palette and resolves slugs to
 * `ImageVector`s with a stable fallback.
 *
 * Mirror of `SmartCollectionAppearance.swift` (iOS).
 *
 * Color authoring reuses [WorkspaceFolderPalette] so smart
 * collections and folders speak the same visual dialect.
 */

package app.quickink.mobile.features.workspace

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.PriorityHigh
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Work
import androidx.compose.ui.graphics.vector.ImageVector

/** One icon choice in the editor + the slug stored on disk. */
internal data class SmartCollectionIconOption(
    val slug: String,
    val icon: ImageVector,
)

/**
 * Editor's icon palette. The first entry is the implicit default
 * for new collections (and the fallback for any unknown slug a
 * cross-device payload might carry). 10 icons keeps the picker
 * scrollable inside the dialog without paging.
 */
internal val SmartCollectionIconPalette: List<SmartCollectionIconOption> = listOf(
    SmartCollectionIconOption("sparkle",   Icons.Outlined.AutoAwesome),
    SmartCollectionIconOption("star",      Icons.Outlined.Star),
    SmartCollectionIconOption("receipt",   Icons.Outlined.Receipt),
    SmartCollectionIconOption("doc",       Icons.Outlined.Description),
    SmartCollectionIconOption("folder",    Icons.Outlined.Folder),
    SmartCollectionIconOption("idea",      Icons.Outlined.Lightbulb),
    SmartCollectionIconOption("work",      Icons.Outlined.Work),
    SmartCollectionIconOption("check",     Icons.Outlined.CheckCircle),
    SmartCollectionIconOption("bookmark",  Icons.Outlined.Bookmark),
    SmartCollectionIconOption("flag",      Icons.Outlined.PriorityHigh),
)

/**
 * Resolve a stored icon slug to an [ImageVector]. Returns the
 * palette's first entry for null / unknown values so a fresh
 * collection's card never renders an empty tile.
 */
internal fun iconVectorForSlug(slug: String?): ImageVector =
    SmartCollectionIconPalette.firstOrNull { it.slug == slug }?.icon
        ?: SmartCollectionIconPalette.first().icon
