/*
 * DriveClientPath.kt
 *
 * Path-aware extension functions over [DriveClient]. The base interface
 * deals in parent-id + filename pairs; the v2 sync worker wants to say
 * "upload to notepad_entries/2026/04/abc123.json under the Releaf root"
 * and have intermediate folders created as needed.
 *
 * Kept as extensions rather than new interface methods so real and stub
 * DriveClient implementations don't have to re-implement path walking —
 * the semantics are defined once here and compose on top of
 * ensureRootFolder / ensureFolder / uploadJson / downloadBytes /
 * listChildren / trash.
 */

package app.releaf.mobile.data.drive

/**
 * Ensure every folder in [relativePath] exists under [rootFolderId],
 * creating any missing intermediates. Returns the Drive file id of the
 * leaf folder. `relativePath` uses `/` as separator.
 *
 *   ensurePath("notepad_entries/2026/04", rootId) → leaf folder id
 */
suspend fun DriveClient.ensurePath(
    relativePath: String,
    rootFolderId: String,
    accessToken: String,
): String {
    val segments = relativePath.split('/').filter { it.isNotEmpty() }
    var currentId = rootFolderId
    for (seg in segments) {
        currentId = ensureFolder(seg, currentId, accessToken).id
    }
    return currentId
}

/**
 * Upload a JSON payload to [relativePath] under [rootFolderId], creating
 * any missing intermediate folders. Replaces an existing file with the
 * same path.
 */
suspend fun DriveClient.uploadJsonAtPath(
    data: ByteArray,
    relativePath: String,
    rootFolderId: String,
    accessToken: String,
): DriveFile {
    val lastSlash = relativePath.lastIndexOf('/')
    val folderPath = if (lastSlash >= 0) relativePath.substring(0, lastSlash) else ""
    val filename   = if (lastSlash >= 0) relativePath.substring(lastSlash + 1) else relativePath
    val folderId = if (folderPath.isEmpty())
        rootFolderId
    else
        ensurePath(folderPath, rootFolderId, accessToken)
    return uploadJson(data, filename, folderId, accessToken)
}

/**
 * Upload binary bytes (PDF, JPEG, etc.) to [relativePath] under
 * [rootFolderId], creating missing intermediate folders. Replaces an
 * existing file with the same path.
 */
suspend fun DriveClient.uploadBinaryAtPath(
    data: ByteArray,
    contentType: String,
    relativePath: String,
    rootFolderId: String,
    accessToken: String,
): DriveFile {
    val lastSlash = relativePath.lastIndexOf('/')
    val folderPath = if (lastSlash >= 0) relativePath.substring(0, lastSlash) else ""
    val filename   = if (lastSlash >= 0) relativePath.substring(lastSlash + 1) else relativePath
    val folderId = if (folderPath.isEmpty())
        rootFolderId
    else
        ensurePath(folderPath, rootFolderId, accessToken)
    return uploadBinary(data, filename, contentType, folderId, accessToken)
}

/**
 * Download the bytes at [relativePath]. Returns null when any folder
 * along the path is missing, or when the leaf file doesn't exist —
 * matches the "not found" contract the sync worker expects.
 */
suspend fun DriveClient.downloadBytesAtPath(
    relativePath: String,
    rootFolderId: String,
    accessToken: String,
): ByteArray? {
    val segments = relativePath.split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) return null

    var currentId = rootFolderId
    for (i in 0 until segments.size - 1) {
        val name = segments[i]
        val folder = listChildren(currentId, accessToken)
            .firstOrNull { it.name == name && it.isFolder }
            ?: return null
        currentId = folder.id
    }
    val filename = segments.last()
    val file = listChildren(currentId, accessToken)
        .firstOrNull { it.name == filename && !it.isFolder }
        ?: return null
    return try {
        downloadBytes(file.id, accessToken)
    } catch (_: DriveError.NotFound) {
        null
    }
}

/**
 * Trash the file at [relativePath]. Returns `true` when the file was
 * found and trashed, `false` otherwise. No-op (returns false) when any
 * ancestor folder is missing.
 */
suspend fun DriveClient.trashAtPath(
    relativePath: String,
    rootFolderId: String,
    accessToken: String,
): Boolean {
    val segments = relativePath.split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) return false

    var currentId = rootFolderId
    for (i in 0 until segments.size - 1) {
        val name = segments[i]
        val folder = listChildren(currentId, accessToken)
            .firstOrNull { it.name == name && it.isFolder }
            ?: return false
        currentId = folder.id
    }
    val filename = segments.last()
    val file = listChildren(currentId, accessToken)
        .firstOrNull { it.name == filename && !it.isFolder }
        ?: return false
    return try {
        trash(file.id, accessToken)
        true
    } catch (_: DriveError) {
        false
    }
}
