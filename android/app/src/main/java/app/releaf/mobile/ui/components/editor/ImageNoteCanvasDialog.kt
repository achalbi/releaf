/*
 * ImageNoteCanvasDialog.kt
 *
 * Edge-to-edge fullscreen drawing surface for sub-pages backed by an
 * imported image (a photo "imported to notes" or a PDF page imported
 * via the scan viewer). The imported page is shown fitted to the area
 * above the drawing toolbar — no scrolling required — so the user
 * always sees the whole page they're marking up.
 *
 * Reuses the existing drawing primitives:
 *   - `DrawingOverlay` for stroke capture + rendering
 *   - `DrawingToolbar` for pen / eraser / nib / thickness controls
 *   - `Stroke` + `PenConfig` so the strokes saved here are the same
 *     shape the in-place editor stores on the SubPage
 *
 * **Coordinate parity with the inline editor.** Strokes are persisted
 * in the inline editor's coordinate space (the page-box dp size used
 * by `SubPageEditorPager`). The fullscreen view renders the image at
 * a different rendered size — usually smaller for tall portraits, since
 * the screen height bounds it — so a stroke drawn at, say, the visual
 * middle of the fullscreen image must be rescaled before being saved,
 * otherwise it lands in the wrong spot when the user returns to the
 * inline view. We compute that scale once per recomposition (from the
 * rendered fullscreen image bounds vs. the inline canvas width), then:
 *   - scale persisted strokes DOWN before handing them to the overlay
 *     (so they render at the right place on the smaller surface)
 *   - scale fresh strokes UP before forwarding to `onStrokesChange`
 *     (so they're stored in the canonical inline space)
 * Stroke thicknesses are NOT scaled — `widthDp` is a visual choice the
 * user makes in dp, and dp is device-independent; rescaling it would
 * make a 4dp pen look thicker or thinner in one view than the other.
 *
 * Strokes are pushed back to the caller via [onStrokesChange] every
 * time the user finishes a gesture, so dismissing the dialog never
 * loses work — the caller (SubPageEditorPager) is wired to the same
 * `viewModel.updateSubPageStrokes` callback the inline overlay uses.
 */

package app.releaf.mobile.ui.components.editor

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.releaf.mobile.data.notebook.Stroke
import app.releaf.mobile.data.notebook.SubPage
import app.releaf.mobile.ui.theme.AppSpacing
import coil.compose.rememberAsyncImagePainter

@Composable
fun ImageNoteCanvasDialog(
    subPage: SubPage,
    onStrokesChange: (List<Stroke>) -> Unit,
    onDismiss: () -> Unit,
) {
    val bgImage = subPage.backgroundImageUri ?: return
    val parsed = runCatching { Uri.parse(bgImage) }.getOrNull() ?: return

    // Drawing-toolbar state. Dialog-local so opening / dismissing the
    // dialog doesn't bleed pen settings back into the inline editor;
    // saveable so a rotation mid-session keeps the user's nib + color.
    var drawingMode by rememberSaveable(stateSaver = DrawingModeSaver) {
        mutableStateOf(DrawingMode.Pen)
    }
    var drawColor by rememberSaveable(stateSaver = DrawingColorSaver) {
        mutableStateOf(DrawingPalette[0])
    }
    var drawOpacity by rememberSaveable { mutableStateOf(1f) }
    var drawWidth by rememberSaveable { mutableStateOf(DrawingThicknesses[1].widthDp) }
    var drawNib by rememberSaveable { mutableStateOf(Stroke.NIB_BALLPOINT) }

    val penConfig = PenConfig(
        color    = drawColor,
        opacity  = drawOpacity,
        widthDp  = drawWidth,
        nib      = drawNib,
    )

    Dialog(
        onDismissRequest = onDismiss,
        // usePlatformDefaultWidth=false paints the dialog edge-to-edge
        // — combined with no status-bar padding on the image, the
        // page touches the device's physical top edge.
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress      = true,
            dismissOnClickOutside   = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            // Vertical layout reserves the bottom row for the drawing
            // toolbar so the image area never sits underneath it.
            Column(modifier = Modifier.fillMaxSize()) {
                BoxWithConstraints(
                    modifier          = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment  = Alignment.Center,
                ) {
                    val containerW = maxWidth
                    val containerH = maxHeight
                    val painter    = rememberAsyncImagePainter(model = parsed)

                    // Resolve the image's intrinsic aspect. Until Coil
                    // decodes (usually a single frame because the inline
                    // view warmed the cache), fall back to the
                    // container's aspect so the canvas is the entire
                    // available area — strokes drawn during that brief
                    // window scale by 1:1 and remain coherent.
                    val sz = painter.intrinsicSize
                    val imageAspect = if (sz != Size.Unspecified
                        && sz.width > 0f && sz.height > 0f
                    ) sz.width / sz.height else containerW.value / containerH.value

                    // Compute the image's rendered bounds inside the
                    // container under `ContentScale.Fit` semantics.
                    // Whichever of width / height is the binding
                    // constraint, the other is derived from the aspect.
                    val containerAspect = containerW.value / containerH.value
                    val renderedW: Dp
                    val renderedH: Dp
                    if (imageAspect >= containerAspect) {
                        renderedW = containerW
                        renderedH = containerW / imageAspect
                    } else {
                        renderedH = containerH
                        renderedW = containerH * imageAspect
                    }

                    // Inline canvas width = pager width = screen width
                    // minus the s4 horizontal padding `SubPageEditorPager`
                    // applies. Stroke coordinates in the database are
                    // in this dp space — see header doc.
                    val inlineCanvasW = (containerW - AppSpacing.s4 * 2)
                        .coerceAtLeast(1.dp)
                    val scaleRatio = inlineCanvasW.value / renderedW.value

                    // Existing strokes were saved in inline coords;
                    // rescale them DOWN for display on this (typically
                    // smaller) surface.
                    val displayedStrokes = remember(subPage.strokes, scaleRatio) {
                        if (scaleRatio == 1f) {
                            subPage.strokes
                        } else {
                            subPage.strokes.map { stroke ->
                                stroke.copy(
                                    points = stroke.points.map { it / scaleRatio },
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.size(renderedW, renderedH)) {
                        androidx.compose.foundation.Image(
                            painter            = painter,
                            contentDescription = null,
                            // The Box is already image-shaped, so
                            // FillBounds fills it without distortion.
                            contentScale       = ContentScale.FillBounds,
                            modifier           = Modifier.matchParentSize(),
                        )
                        DrawingOverlay(
                            strokes         = displayedStrokes,
                            mode            = drawingMode,
                            penConfig       = penConfig,
                            onStrokesChange = { newDisplayedStrokes ->
                                // Convert back to the inline coord
                                // space before persisting. Round-trip
                                // is exact enough for one user gesture
                                // — float drift on a single multiply +
                                // divide is well under a pixel.
                                val saved = if (scaleRatio == 1f) {
                                    newDisplayedStrokes
                                } else {
                                    newDisplayedStrokes.map { stroke ->
                                        stroke.copy(
                                            points = stroke.points.map { it * scaleRatio },
                                        )
                                    }
                                }
                                onStrokesChange(saved)
                            },
                            modifier        = Modifier.matchParentSize(),
                        )
                    }
                }

                // Drawing toolbar pinned to the bottom — owns its
                // navigation-bar inset directly so the system bar
                // doesn't bite into its controls.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(WindowInsets.navigationBars.asPaddingValues()),
                ) {
                    DrawingToolbar(
                        mode            = drawingMode,
                        onModeChange    = { drawingMode = it },
                        color           = drawColor,
                        onColorChange   = { drawColor = it },
                        opacity         = drawOpacity,
                        onOpacityChange = { drawOpacity = it },
                        widthDp         = drawWidth,
                        onWidthChange   = { drawWidth = it },
                        nib             = drawNib,
                        onNibChange     = { drawNib = it },
                        onClose         = onDismiss,
                    )
                }
            }

            // Close button overlays the image area (outside the
            // Column so it floats above pixels rather than carving a
            // row out of the layout). `statusBars` inset keeps it
            // clear of the notch / system clock.
            val statusPadding = WindowInsets.statusBars.asPaddingValues()
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(statusPadding)
                    .padding(AppSpacing.s3)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint               = Color.White,
                    modifier           = Modifier.size(20.dp),
                )
            }
        }
    }
}
