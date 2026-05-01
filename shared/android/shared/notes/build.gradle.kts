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
 *   - All Compose-using editor views STAY (need :shared:designsystem
 *     first, which ships in PR #4h).
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
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

    // Coroutines + Flow — DAO returns Flow<...> for observe* methods.
    implementation(libs.coroutines.android)

    // Room — NotepadEntry @Entity + NotepadDao @Dao definitions.
    // Per-app databases (ReleafDatabase, eventually QuickInkDatabase)
    // register the entity in their `entities = [...]` list.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
}
