/*
 * DriveClient.kt
 * Façade over Google Drive. Real implementation wraps the Drive v3 REST API
 * (either google-api-services-drive or plain OkHttp calls).
 */

package app.releaf.mobile.data.drive

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID

data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val parents: List<String> = emptyList(),
    val modifiedTime: Instant? = null,
) {
    val isFolder: Boolean get() = mimeType == "application/vnd.google-apps.folder"
}

sealed class DriveError(message: String) : Exception(message) {
    object Unauthenticated : DriveError("Unauthenticated")
    object RateLimited     : DriveError("Rate limited")
    object NotFound        : DriveError("Not found")
    object NotImplemented  : DriveError("Not implemented")
    class  Underlying(msg: String) : DriveError(msg)
}

interface DriveClient {
    suspend fun ensureRootFolder(name: String, accessToken: String): DriveFile
    suspend fun ensureFolder(name: String, parentId: String, accessToken: String): DriveFile
    suspend fun listChildren(folderId: String, accessToken: String): List<DriveFile>
    suspend fun uploadJson(data: ByteArray, filename: String, parentId: String, accessToken: String): DriveFile

    /**
     * Upload / overwrite a binary file (PDF, JPEG, etc.) with caller-
     * supplied MIME type. PATCHes an existing same-name child if
     * present so re-uploads don't fork into duplicates.
     */
    suspend fun uploadBinary(
        data: ByteArray,
        filename: String,
        contentType: String,
        parentId: String,
        accessToken: String,
    ): DriveFile
    suspend fun downloadBytes(fileId: String, accessToken: String): ByteArray
    suspend fun trash(fileId: String, accessToken: String)
}

/** In-memory stub — lets skeletons and previews run without network. */
class InMemoryDriveClient : DriveClient {
    private val mutex = Mutex()
    private val files  = mutableMapOf<String, DriveFile>()
    private val blobs  = mutableMapOf<String, ByteArray>()

    override suspend fun ensureRootFolder(name: String, accessToken: String): DriveFile = mutex.withLock {
        files.values.firstOrNull { it.name == name && it.parents.isEmpty() && it.isFolder }
            ?: DriveFile(UUID.randomUUID().toString(), name, "application/vnd.google-apps.folder")
                .also { files[it.id] = it }
    }

    override suspend fun ensureFolder(name: String, parentId: String, accessToken: String): DriveFile = mutex.withLock {
        files.values.firstOrNull { it.name == name && it.parents == listOf(parentId) && it.isFolder }
            ?: DriveFile(UUID.randomUUID().toString(), name, "application/vnd.google-apps.folder", listOf(parentId))
                .also { files[it.id] = it }
    }

    override suspend fun listChildren(folderId: String, accessToken: String): List<DriveFile> = mutex.withLock {
        files.values.filter { folderId in it.parents }
    }

    override suspend fun uploadJson(data: ByteArray, filename: String, parentId: String, accessToken: String): DriveFile =
        uploadBinary(data, filename, "application/json", parentId, accessToken)

    override suspend fun uploadBinary(
        data: ByteArray,
        filename: String,
        contentType: String,
        parentId: String,
        accessToken: String,
    ): DriveFile = mutex.withLock {
        val existing = files.values.firstOrNull { it.name == filename && it.parents == listOf(parentId) }
        if (existing != null) {
            blobs[existing.id] = data
            return@withLock existing
        }
        val file = DriveFile(UUID.randomUUID().toString(), filename, contentType, listOf(parentId))
        files[file.id] = file
        blobs[file.id] = data
        file
    }

    override suspend fun downloadBytes(fileId: String, accessToken: String): ByteArray = mutex.withLock {
        blobs[fileId] ?: throw DriveError.NotFound
    }

    override suspend fun trash(fileId: String, accessToken: String) { mutex.withLock {
        files.remove(fileId); blobs.remove(fileId)
    } }
}
