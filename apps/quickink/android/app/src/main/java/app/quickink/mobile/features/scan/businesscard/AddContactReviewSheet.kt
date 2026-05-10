/*
 * AddContactReviewSheet.kt
 *
 * Modal bottom sheet that shows the [BusinessCardExtractor] output as
 * editable form fields before the user hands the result to the
 * system contact-create intent. Lets the user fix mis-classifications
 * inline (e.g. swap a name and a designation that the OCR layout
 * confused) without bouncing through the system Contacts app first.
 *
 * On Save we hand the (possibly-edited) values back through
 * `onConfirm` and the parent fires the contact intent. On Cancel we
 * just dismiss — nothing persists.
 */

package app.quickink.mobile.features.scan.businesscard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.releaf.shared.scan.businesscard.ExtractedContact

/**
 * Editable representation of the extracted contact. Phones / emails /
 * websites collapse to comma-separated strings in the form so the
 * user can edit them inline; the launcher splits them back out
 * before firing the system intent.
 */
data class EditableContact(
    val name: String,
    val designation: String,
    val company: String,
    val phones: String,
    val emails: String,
    val websites: String,
    val address: String,
) {
    companion object {
        fun from(c: ExtractedContact): EditableContact = EditableContact(
            name        = c.name.orEmpty(),
            designation = c.designation.orEmpty(),
            company     = c.company.orEmpty(),
            phones      = c.phones.joinToString(", "),
            emails      = c.emails.joinToString(", "),
            websites    = c.websites.joinToString(", "),
            address     = c.address.orEmpty(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactReviewSheet(
    extracted: ExtractedContact,
    onDismiss: () -> Unit,
    onConfirm: (EditableContact) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var form by remember(extracted) { mutableStateOf(EditableContact.from(extracted)) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = colors.surface,
        contentColor     = colors.ink,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
        ) {
            // Header.
            Text(
                text  = "Review contact",
                style = type.heading,
                color = colors.ink,
            )
            Text(
                text  = "We pulled the fields below from the scan. Edit any " +
                        "that look wrong, then save to add to your contacts.",
                style = type.meta,
                color = colors.inkSoft,
            )

            // Confidence indicator — gives the user a heads-up when
            // the extraction is shaky and the form's likely to need
            // edits before saving.
            ConfidencePill(extracted.confidence)

            Spacer(modifier = Modifier.height(QuickInkSpacing.s2))

            FieldEditor("Name",        form.name)        { form = form.copy(name        = it) }
            FieldEditor("Designation", form.designation) { form = form.copy(designation = it) }
            FieldEditor("Company",     form.company)     { form = form.copy(company     = it) }
            FieldEditor("Phones",      form.phones,      hint = "Comma-separated") { form = form.copy(phones   = it) }
            FieldEditor("Emails",      form.emails,      hint = "Comma-separated") { form = form.copy(emails   = it) }
            FieldEditor("Websites",    form.websites,    hint = "Comma-separated") { form = form.copy(websites = it) }
            FieldEditor("Address",     form.address,     multiLine = true)         { form = form.copy(address  = it) }

            Spacer(modifier = Modifier.height(QuickInkSpacing.s2))

            // Action row — Cancel / Save.
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = colors.muted)
                }
                Spacer(modifier = Modifier.padding(horizontal = QuickInkSpacing.s2))
                TextButton(onClick = { onConfirm(form) }) {
                    Text("Save to contacts", color = colors.accent)
                }
            }

            Spacer(modifier = Modifier.height(QuickInkSpacing.s4))
        }
    }
}

@Composable
private fun ConfidencePill(confidence: Double) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    // Pill shows a coloured progress bar + a label. Green/orange/
    // ink mapping based on a coarse three-bucket confidence scale.
    val (label, tint) = when {
        confidence >= 0.7 -> "High confidence"   to colors.success
        confidence >= 0.4 -> "Medium confidence" to colors.warning
        else              -> "Low confidence — please review" to colors.danger
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.sm))
            .background(colors.borderSoft)
            .padding(QuickInkSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Text(text = label, style = type.caption, color = tint)
            Text(
                text  = "${(confidence * 100).toInt()}%",
                style = type.caption,
                color = colors.inkSoft,
            )
        }
        LinearProgressIndicator(
            progress = { confidence.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color     = tint,
            trackColor = colors.bg,
        )
    }
}

@Composable
private fun FieldEditor(
    label: String,
    value: String,
    hint: String? = null,
    multiLine: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s1)) {
        Text(text = label.uppercase(), style = type.eyebrow, color = colors.muted)
        OutlinedTextField(
            value         = value,
            onValueChange = onValueChange,
            modifier      = Modifier.fillMaxWidth(),
            singleLine    = !multiLine,
            minLines      = if (multiLine) 2 else 1,
            textStyle     = type.body,
            placeholder   = if (hint != null) {
                { Text(hint, style = type.meta, color = colors.muted) }
            } else null,
        )
    }
}
