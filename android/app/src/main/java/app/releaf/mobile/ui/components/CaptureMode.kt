/*
 * CaptureMode.kt
 *
 * The 7 capture flavors Releaf supports. Lives in the design system
 * because it's intrinsically a UI concept — the tab bar, quick-capture
 * sheet, and page-detail sections all render off it.
 *
 * Shape mirrors the Inkcreate mobile DS (title / subtitle / icon).
 * `label` is kept as a back-compat alias for older call sites.
 */

package app.releaf.mobile.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.ui.graphics.vector.ImageVector

enum class CaptureMode(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
) {
    // Overview is the page's "everything at a glance" tab; the leaf
    // is Releaf's brand glyph (see BRAND_BRIEF.md), so the overview
    // tab carries it. Active state fills the leaf onto the
    // accent-palette square; inactive state shows the same glyph in
    // the default text color.
    Overview("Overview",       "All sections at a glance", Icons.Filled.Eco),
    Photos  ("Photos",         "Camera or upload",         Icons.Filled.CameraAlt),
    Voice   ("Voice note",     "Record audio",             Icons.Filled.Mic),
    Todo    ("To-do",          "Quick checklist item",     Icons.Filled.Checklist),
    Scans   ("Scan document",  "Capture a document page",  Icons.Filled.DocumentScanner),
    Contacts("Contact",        "Phone, email, website",    Icons.Filled.PersonOutline),
    Location("Location",       "Tag current GPS",          Icons.Filled.LocationOn);

    /** Back-compat alias for pre-port call sites. */
    val label: String get() = title
}
