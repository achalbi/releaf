/*
 * shared/android/shared/auth/build.gradle.kts
 *
 * Build config for the :shared:auth library module. Holds the
 * app-agnostic Google Sign-In integration: AuthStore (state holder
 * + EncryptedSharedPreferences token persistence), GoogleAuthClient
 * (protocol + stub), RealGoogleAuthClient (Credential Manager +
 * AuthorizationClient flow).
 *
 * GoogleSignInBinding.kt (the @Composable-side glue) STAYS in
 * :apps:releaf because it pulls `R.string.google_web_client_id` from
 * Releaf's resources — each app provides its own. QuickInk writes
 * its own QuickInkSignInBinding.kt that does the same against
 * QuickInk's R string.
 *
 * Mirror of iOS's ReleafCoreAuth target.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace  = "app.releaf.shared.auth"
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
    // Coroutines (suspend-based AuthClient API + StateFlow exposure).
    implementation(libs.coroutines.android)

    // EncryptedSharedPreferences — persistent OAuth token storage.
    implementation(libs.security.crypto)

    // Credential Manager + Google ID — the modern Sign In With Google
    // flow. Identity Services AuthorizationClient layered on top to
    // grant the drive.file scope.
    implementation(libs.credentials)
    implementation(libs.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)
}
