/*
 * ContactsScreen.kt
 *
 * Top-level Contacts surface. Shows the unified app directory by
 * default; as the user types, the search field also surfaces
 * device contacts (gated by `READ_CONTACTS`).
 */

package app.releaf.mobile.features.contacts

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.data.contact.DirectoryContact
import app.releaf.mobile.data.contact.DirectoryContactSource
import app.releaf.mobile.ui.components.ScreenHeader
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

@Composable
fun ContactsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ContactsViewModel = viewModel(factory = ContactsViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onPermissionResult(granted) }
    var selectedContact by remember { mutableStateOf<DirectoryContact?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.s4)
                .padding(top = AppSpacing.s3, bottom = AppSpacing.s1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = AppColors.TextPrimary,
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onBack() },
            )
        }
        ScreenHeader(
            eyebrow   = "Contacts",
            title     = "Your directory",
            topPadding = AppSpacing.s1,
            titleStyle = AppTypography.EditorialTitleLight,
        )

        SearchField(
            query = state.query,
            onQueryChange = viewModel::updateQuery,
            onClear = viewModel::clearQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.s4)
                .padding(bottom = AppSpacing.s3),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = AppSpacing.s4,
                vertical   = AppSpacing.s2,
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        ) {
            // ── Section 1: app contacts ────────────────────────────
            item(key = "header_app") {
                SectionHeader(
                    title    = if (state.isSearching) "In your notes" else "All contacts",
                    subtitle = when {
                        state.isSearching && state.filteredAppContacts.isEmpty() ->
                            "No matches in notebooks or notepad."
                        state.isSearching ->
                            "Matches from notebooks + notepad entries."
                        else ->
                            "Everyone you've captured across notebooks and notepad."
                    },
                    badge    = "${state.filteredAppContacts.size}",
                )
            }
            if (state.isLoading) {
                item(key = "app_loading") {
                    Text(
                        "Loading…",
                        style = AppTypography.Meta,
                        color = AppColors.TextTertiary,
                        modifier = Modifier.padding(AppSpacing.s4),
                    )
                }
            } else if (state.filteredAppContacts.isEmpty()) {
                item(key = "app_empty") {
                    EmptyCard(
                        title = if (state.isSearching) "No matches" else "No contacts yet",
                        subtitle = if (state.isSearching) {
                            "Try searching by name, phone, or email."
                        } else {
                            "Contacts you add to notes or pages will show up here."
                        },
                    )
                }
            } else {
                items(state.filteredAppContacts, key = { it.id }) { contact ->
                    ContactRow(
                        contact = contact,
                        onClick = { selectedContact = contact },
                    )
                }
            }

            // ── Section 2: device contacts (search only) ───────────
            if (state.isSearching) {
                item(key = "header_device") {
                    SectionHeader(
                        title    = "Device contacts",
                        subtitle = when {
                            !state.devicePermissionGranted ->
                                "Let Releaf read your phone contacts to surface them here."
                            state.deviceContacts.isEmpty() ->
                                "No device matches for \u201C${state.query}\u201D."
                            else ->
                                "From your phone's address book."
                        },
                        badge    = if (state.devicePermissionGranted) "${state.deviceContacts.size}" else null,
                    )
                }
                if (!state.devicePermissionGranted) {
                    item(key = "permission_cta") {
                        PermissionCta(
                            onGrant = {
                                permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                            },
                        )
                    }
                } else if (state.deviceContacts.isNotEmpty()) {
                    items(state.deviceContacts, key = { it.id }) { contact ->
                        ContactRow(
                            contact = contact,
                            onClick = { selectedContact = contact },
                        )
                    }
                }
            }

            item(key = "tail_spacer") { Spacer(Modifier.height(AppSpacing.s10)) }
        }
    }

    val current = selectedContact
    if (current != null) {
        ContactDetailDialog(
            contact = current,
            onDismiss = { selectedContact = null },
        )
    }
}

// =================================================================== Search

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(AppColors.InputBg)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.pill))
            .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = AppColors.TextSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(AppSpacing.s2))
        Box(Modifier.weight(1f)) {
            // Placeholder sits behind the text field so taps still
            // reach the field. The BasicTextField fills the Box so
            // the tap target isn't zero-width on an empty query —
            // that was the reason tapping did nothing before.
            if (query.isEmpty()) {
                Text(
                    "Search contacts + phone book",
                    style = AppTypography.Body,
                    color = AppColors.TextTertiary,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                cursorBrush = SolidColor(AppAccent.primary),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Search,
                ),
                textStyle = AppTypography.Body.copy(color = AppColors.TextPrimary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Clear search",
                tint = AppColors.TextSecondary,
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onClear() },
            )
        }
    }
}

// =================================================================== Section bits

@Composable
private fun SectionHeader(title: String, subtitle: String, badge: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AppSpacing.s2, bottom = AppSpacing.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title.uppercase(), style = AppTypography.Eyebrow, color = AppAccent.primary)
            Text(subtitle, style = AppTypography.Meta, color = AppColors.TextSecondary)
        }
        if (badge != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(AppColors.NeutralSoft)
                    .padding(horizontal = AppSpacing.s3, vertical = 5.dp),
            ) {
                Text(badge, style = AppTypography.Tag, color = AppColors.TextPrimary)
            }
        }
    }
}

@Composable
private fun ContactRow(
    contact: DirectoryContact,
    onClick: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .clickable { onClick() }
            .padding(AppSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(contact)
        Spacer(Modifier.width(AppSpacing.s3))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(contact.name, style = AppTypography.SectionTitle, color = AppColors.TextPrimary)
            contact.organization?.let {
                Text(it, style = AppTypography.Meta, color = AppColors.TextSecondary)
            }
            val meta = listOfNotNull(contact.phone, contact.email).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(meta, style = AppTypography.Meta, color = AppColors.TextSecondary)
            }
        }
        if (contact.source == DirectoryContactSource.App && contact.appOccurrences > 0) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(AppColors.SuccessSoft)
                    .padding(horizontal = AppSpacing.s3, vertical = 4.dp),
            ) {
                Text(
                    "${contact.appOccurrences}×",
                    style = AppTypography.Tag,
                    color = AppColors.GreenText,
                )
            }
        } else if (contact.source == DirectoryContactSource.Device) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(AppColors.NeutralSoft)
                    .padding(horizontal = AppSpacing.s3, vertical = 4.dp),
            ) {
                Text(
                    "Phone",
                    style = AppTypography.Tag,
                    color = AppColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun Avatar(contact: DirectoryContact) {
    val initial = contact.name.firstOrNull()?.uppercase() ?: "?"
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(
                if (contact.source == DirectoryContactSource.App) AppAccent.soft
                else AppColors.NeutralSoft
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            style = AppTypography.SectionTitle,
            color = if (contact.source == DirectoryContactSource.App)
                AppAccent.primary else AppColors.TextPrimary,
        )
    }
}

@Composable
private fun PermissionCta(onGrant: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .padding(AppSpacing.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.s1)) {
            Text(
                "Show device contacts",
                style = AppTypography.SectionTitle,
                color = AppColors.TextPrimary,
            )
            Text(
                "Releaf only reads them for this search session.",
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(AppRadius.pill))
                .background(AppColors.ActionPrimary)
                .clickable { onGrant() }
                .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s3),
        ) {
            Text("Enable", style = AppTypography.Button, color = AppColors.OnPrimary)
        }
    }
}

@Composable
private fun ContactDetailDialog(
    contact: DirectoryContact,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(contact.name, style = AppTypography.SectionTitle, color = AppColors.TextPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
                contact.organization?.let {
                    Text(it, style = AppTypography.Body, color = AppColors.TextSecondary)
                }
                contact.phone?.let { phone ->
                    DetailField(label = "Phone", value = phone)
                }
                contact.email?.let { email ->
                    DetailField(label = "Email", value = email)
                }
                if (contact.source == DirectoryContactSource.App && contact.appOccurrences > 0) {
                    Text(
                        "Captured in ${contact.appOccurrences} " +
                            if (contact.appOccurrences == 1) "note." else "notes.",
                        style = AppTypography.Meta,
                        color = AppColors.TextTertiary,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
                contact.phone?.let { phone ->
                    androidx.compose.material3.TextButton(
                        onClick = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_DIAL,
                                android.net.Uri.parse("tel:$phone"),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                        },
                    ) { Text("Call", color = AppAccent.primary) }
                }
                contact.email?.let { email ->
                    androidx.compose.material3.TextButton(
                        onClick = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_SENDTO,
                                android.net.Uri.parse("mailto:$email"),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                        },
                    ) { Text("Email", color = AppAccent.primary) }
                }
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text("Close", color = AppColors.TextSecondary)
                }
            }
        },
        containerColor = AppColors.CardSolid,
    )
}

@Composable
private fun DetailField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), style = AppTypography.Eyebrow, color = AppColors.TextSecondary)
        Text(value, style = AppTypography.Body, color = AppColors.TextPrimary)
    }
}

@Composable
private fun EmptyCard(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .padding(AppSpacing.s6),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s1),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = AppTypography.SectionTitle, color = AppColors.TextPrimary)
        Text(
            subtitle,
            style = AppTypography.Meta,
            color = AppColors.TextTertiary,
        )
    }
}
