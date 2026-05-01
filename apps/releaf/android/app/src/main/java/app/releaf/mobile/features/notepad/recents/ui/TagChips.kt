package app.releaf.mobile.features.notepad.recents.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.releaf.mobile.features.notepad.recents.model.Tag
import app.releaf.mobile.features.notepad.recents.model.TagFilter
import app.releaf.mobile.features.notepad.recents.theme.BgChip
import app.releaf.mobile.features.notepad.recents.theme.Green800
import app.releaf.mobile.features.notepad.recents.theme.TextOnDark
import app.releaf.mobile.features.notepad.recents.theme.Type

/** Horizontal scrolling chip row: All / Home / Work / Recipes / Personal. */
@Composable
fun TagChips(
    selected: TagFilter,
    onSelected: (TagFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items: List<TagFilter> = buildList {
        add(TagFilter.All)
        addAll(Tag.values().map { TagFilter.Single(it) })
    }

    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items) { item ->
            val isActive = item == selected
            Chip(
                label = item.label(),
                isActive = isActive,
                onClick = { onSelected(item) },
            )
        }
    }
}

private fun TagFilter.label(): String = when (this) {
    is TagFilter.All -> "All"
    is TagFilter.Single -> tag.display
}

@Composable
private fun Chip(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (isActive) Green800 else BgChip
    val fg = if (isActive) TextOnDark else Green800
    // Brief: only Regular and Medium. Active = Medium for emphasis.
    val weight = if (isActive) FontWeight.Medium else FontWeight.Normal

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = Type.Chip.copy(color = fg, fontWeight = weight),
        )
    }
}
