/*
 * AttachmentStorage.kt
 *
 * Single spot that owns where photo/scan bytes live on disk:
 *   <app filesDir>/<appFolderName>/attachments/<UUIDv7>.<ext>
 *
 * The editor sections use this for two things:
 *   1. COPY-IN: ML Kit's document scanner writes its PDF + JPEG to its
 *      own cache directory. Those URIs work for the current session but
 *      evaporate when ML Kit rotates its cache — so the ScansSection
 *      copies them here first and stores the resulting file:// URI on
 *      the attachment.
 *   2. DELETE: when the user removes a scan attachment the VM asks us
 *      to delete the backing file. (Photos from PickVisualMedia stay in
 *      MediaStore — the persistable-URI grant gets released separately.)
 *
 * No per-attachment cleanup on app startup — files are orphaned only if
 * the database write that would reference them failed, and we accept
 * that small leak in favour of a single write path.
 *
 * PR #4b changes:
 *   - Moved into :shared:data (was apps/releaf/.../data/common/).
 *   - Folder name is now parameterized via `appFolderName` (defaults to
 *     "releaf" so existing call sites keep working). QuickInk sets it
 *     to "quickink" once at process start.
 *   - URI ↔ File helpers (toFile/toUri) inlined to keep this module
 *     dep-free — they were one-liners over the Android SDK.
 */

package app.releaf.mobile.data.common

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File

object AttachmentStorage {

    /**
     * App-specific subfolder under filesDir. Each app sets this once
     * at startup. Defaults to "releaf" so existing Releaf call sites
     * work unchanged; QuickInk's app entry point overrides at init.
     *
     * Static-mutable shape is deliberate — process-wide constant in
     * practice (set once, read everywhere). A class with DI would be
     * more correct but force every call site to thread an instance,
     * which buys nothing for a single-app process.
     */
    @Volatile
    var appFolderName: String = "releaf"

    /**
     * Directory for app-owned attachment bytes. Created lazily the first
     * time a caller asks. Sub-directory under filesDir so `adb shell
     * run-as` listings stay readable during dogfood.
     */
    fun directory(context: Context): File {
        val dir = File(context.filesDir, "$appFolderName/attachments")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Copies the bytes pointed to by `source` into our own files
     * directory and returns a `file://` URI to the local copy. `ext`
     * is the filename suffix to use (no leading dot). Returns null on
     * IO failure — caller decides what to surface to the user.
     */
    fun copyIntoStorage(context: Context, source: Uri, ext: String): Uri? {
        val dest = File(directory(context), "${Uuidv7.generate()}.$ext")
        val resolver: ContentResolver = context.contentResolver
        return runCatching {
            resolver.openInputStream(source)?.use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            Uri.fromFile(dest)
        }.getOrNull()
    }

    /**
     * If `uri` is a `file://` URI pointing at a file we own, delete the
     * file. `content://` URIs (MediaStore photos) are out of scope here
     * — those are released via `ContentResolver.releasePersistableUriPermission`
     * in the VM.
     */
    fun deleteIfLocal(uri: String) {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return
        if (parsed.scheme != "file") return
        val path = parsed.path ?: return
        runCatching {
            File(path).takeIf { it.exists() }?.delete()
        }
    }
}
