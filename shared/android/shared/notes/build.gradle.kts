/*
 * shared/android/shared/notes/build.gradle.kts
 *
 * Build config for the :shared:notes library module. Holds the
 * notepad data layer shared across apps:
 *   - NotepadEntry  (Room @Entity)
 *   - NotepadDao    (Room @Dao)
 *   - NotepadCategory (data type)
 *
 * Trimmed scope vs iOS counterpart (PR #4e):
 *   - NotepadRepository.kt STAYS in :apps:releaf. It depends on
 *     PageAttachments.kt parsers (in `data/notebook/` package) which
 *     are used across 14 Releaf files — moving them is a separate
 *     refactor. QuickInk writes its own thin repository wrapper over
 *     NotepadDao.
 *   - NotepadListViewModel.kt STAYS in :apps:releaf. It extends
 *     AndroidViewModel and casts applicationContext as ReleafApp;
 *     extracting it requires a DI refactor (constructor-injected
 *     dependencies instead of cast). Separate PR.
 *
 * PR #4h additions: two of the four iOS-counterpart editor views move
 * here now that :shared:designsystem ships:
 *   - editor/RichTextFormatBar.kt
 *   - editor/EditorModeToggle.kt
 *
 * Editor views still deferred:
 *   - editor/NotesEditorSheet.kt — imports `data.notebook.{Stroke,SubPage}`,
 *     which still live in the app target. Moves once the notebook
 *     data layer extracts.
 *   - There is no Android counterpart to iOS's `EntryDateRow.swift` —
 *     the entry-date control is inline inside `NotepadEditorScreen.kt`.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    // PR #4h — Compose editor views (RichTextFormatBar, EditorModeToggle)
    // moved into this module from the Releaf app target.
    alias(libs.plugins.compose.compiler)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace  = "app.releaf.shared.notes"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared:data"))  // Uuidv7 / IsoClock / FtsQuery

    // PR #4h — editor views (RichTextFormatBar, EditorModeToggle) reach
    // AppColors / AppTypography / AppSpacing through the design system
    // module that just landed.
    implementation(project(":shared:designsystem"))

    // Coroutines + Flow — DAO returns Flow<...> for observe* methods.
    implementation(libs.coroutines.android)

    // Room — NotepadEntry @Entity + NotepadDao @Dao definitions.
    // Per-app databases (ReleafDatabase, eventually QuickInkDatabase)
    // register the entity in their `entities = [...]` list.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // PR #4h — Compose deps for the editor views moved into this
    // module. Same surface the Releaf app uses; runtime + ui-graphics
    // come transitively through the BOM.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // RichTextFormatBar drives the rich-text state from this third-
    // party library; needed wherever RichTextFormatBar is compiled.
    implementation(libs.rich.editor.compose)
}
