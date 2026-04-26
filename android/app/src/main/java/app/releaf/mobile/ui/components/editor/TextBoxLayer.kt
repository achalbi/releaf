/*
 * TextBoxLayer.kt
 *
 * Free-form text overlay on a sub-page. Each [TextBox] is absolutely
 * positioned at the dp coordinates captured when the user tapped an
 * empty spot in `DrawingMode.Text`. Tapping an existing text box
 * re-focuses it for editing; clearing all characters deletes the
 * box on blur.
 *
 * Pointer input:
 *   - Text mode   → tap on empty area creates a new box at that
 *                   position; each existing box is individually
 *                   tappable to focus.
 *   - Other modes → no pointer input; strokes (DrawingOverlay) and
 *                   the rich-text editor beneath stay reachable.
 *
 * Coordinate space matches [Stroke] — dp, origin at the sub-page
 * card's top-left.
 */

package app.releaf.mobile.ui.components.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.releaf.mobile.data.common.Uuidv7
import app.releaf.mobile.data.notebook.TextBox
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.LocalFontWeight

/** Max horizontal size a single text box grows to before wrapping. */
private val MaxBoxWidth = 240.dp

@Composable
fun TextBoxLayer(
    textBoxes: List<TextBox>,
    mode: DrawingMode,
    penConfig: PenConfig,
    fontSpForWidth: (widthDp: Float) -> Float,
    onChange: (List<TextBox>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val pxPerDp = density.density

    // Which box is currently being edited. null = none. Transient —
    // doesn't need to survive config changes because the actual text
    // is flushed back to `textBoxes` on blur.
    var focusedId by remember { mutableStateOf<String?>(null) }

    // Fresh closures for the background tap handler, captured through
    // rememberUpdatedState so the `pointerInput` that's re-keyed only
    // on `mode` still sees the latest callback + latest list.
    val latestBoxes by rememberUpdatedState(textBoxes)
    val latestOnChange by rememberUpdatedState(onChange)
    val latestConfig by rememberUpdatedState(penConfig)
    val latestSize by rememberUpdatedState(fontSpForWidth)

    // Live color recolor: while a box is focused, re-tinting the
    // toolbar's color / opacity repaints the active box immediately
    // so the swatch picker works for text the same way it works for
    // pen strokes. Keyed on the color/opacity pair so we don't rewrite
    // the list every recomposition.
    val focusedColorArgb = penConfig.color.copy(alpha = penConfig.opacity).toArgb()
    LaunchedEffect(focusedId, focusedColorArgb) {
        val id = focusedId ?: return@LaunchedEffect
        val current = latestBoxes.firstOrNull { it.id == id } ?: return@LaunchedEffect
        if (current.color != focusedColorArgb) {
            latestOnChange(
                latestBoxes.map {
                    if (it.id == id) it.copy(color = focusedColorArgb) else it
                },
            )
        }
    }

    // Background-tap handler: active in Text mode only. Children
    // (existing boxes) consume their own taps via `.clickable`, so
    // this only fires on empty areas — which is what we want.
    val bgModifier = if (mode == DrawingMode.Text) {
        Modifier.pointerInput(mode) {
            detectTapGestures { offset ->
                val cfg = latestConfig
                val argb = cfg.color.copy(alpha = cfg.opacity).toArgb()
                val newBox = TextBox(
                    id     = Uuidv7.generate(),
                    xDp    = offset.x / pxPerDp,
                    yDp    = offset.y / pxPerDp,
                    text   = "",
                    color  = argb,
                    fontSp = latestSize(cfg.widthDp),
                )
                focusedId = newBox.id
                latestOnChange(latestBoxes + newBox)
            }
        }
    } else {
        Modifier
    }

    Box(modifier = modifier.fillMaxSize().then(bgModifier)) {
        textBoxes.forEach { box ->
            TextBoxItem(
                box            = box,
                isFocusTarget  = focusedId == box.id,
                isInteractive  = mode == DrawingMode.Text,
                onFocusRequested = { focusedId = box.id },
                onTextChange   = { newText ->
                    latestOnChange(
                        latestBoxes.map {
                            if (it.id == box.id) it.copy(text = newText) else it
                        },
                    )
                },
                onBlur         = {
                    // Auto-remove empty boxes on blur so accidental
                    // taps don't leave invisible artifacts behind.
                    val current = latestBoxes.firstOrNull { it.id == box.id }
                    if (current != null && current.text.isBlank()) {
                        latestOnChange(latestBoxes.filterNot { it.id == box.id })
                    }
                    if (focusedId == box.id) focusedId = null
                },
            )
        }
    }
}

@Composable
private fun TextBoxItem(
    box: TextBox,
    isFocusTarget: Boolean,
    isInteractive: Boolean,
    onFocusRequested: () -> Unit,
    onTextChange: (String) -> Unit,
    onBlur: () -> Unit,
) {
    val focusRequester = remember(box.id) { FocusRequester() }
    var hasFocus by remember(box.id) { mutableStateOf(false) }

    // Snap keyboard to this box when the caller marks it the focus
    // target (fresh-tap or re-tap). Runs once per id→focus-target
    // transition so a subsequent recomp doesn't re-request focus
    // while the user is mid-edit.
    LaunchedEffect(isFocusTarget) {
        if (isFocusTarget) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    // Blur hand-off. If the caller has already moved focus elsewhere
    // we still want an empty-box cleanup pass; DisposableEffect covers
    // the "navigated away while editing" case.
    val latestBlur by rememberUpdatedState(onBlur)
    DisposableEffect(box.id) {
        onDispose { if (hasFocus) latestBlur() }
    }

    val textColor = Color(box.color)
    val borderColor = if (hasFocus) AppAccent.primary else Color.Transparent
    val textStyle = TextStyle(
        color      = textColor,
        fontSize   = box.fontSp.sp,
        fontWeight = LocalFontWeight.current,
    )

    // No card-chrome around the text — we want the typed characters
    // to read as free-form marks on the page, not a boxed widget.
    // The click target still needs a footprint in interactive mode
    // so re-tapping an existing box focuses it, hence the transparent
    // clickable.
    Box(
        modifier = Modifier
            .offset(x = box.xDp.dp, y = box.yDp.dp)
            .widthIn(max = MaxBoxWidth)
            .then(
                if (isInteractive) Modifier.clickable(onClick = onFocusRequested)
                else Modifier,
            ),
    ) {
        // In Text mode we render an editable BasicTextField so the
        // user can type / re-edit. In non-Text modes we render a
        // plain Text — that way the box has no pointerInput and
        // touches pass straight through to the DrawingOverlay beneath
        // (can't stroke over a text field's selection handles).
        if (isInteractive) {
            BasicTextField(
                value         = box.text,
                onValueChange = onTextChange,
                textStyle     = textStyle,
                cursorBrush   = SolidColor(AppAccent.primary),
                modifier      = Modifier
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        val was = hasFocus
                        hasFocus = state.isFocused
                        if (was && !state.isFocused) {
                            latestBlur()
                        }
                    },
            )
        } else {
            Text(
                text  = box.text,
                style = textStyle,
            )
        }
    }
}
