/*
 * OkHttpDriveClient.kt
 *
 * Real Drive v3 REST client. Implements the `DriveClient` protocol
 * against OkHttp — no SDK wrapper, just raw REST calls with
 * `Authorization: Bearer <accessToken>` on every request.
 *
 * Scope: `drive.file`. Every query implicitly narrows to the app's
 * own files; a `name = '…'` lookup that never matched before will
 * just return zero results without leaking other Drive content.
 *
 * Endpoints used:
 *   GET    /drive/v3/files?q=…             — lookup by name + parent
 *   POST   /drive/v3/files                  — create folder
 *   POST   /upload/drive/v3/files?…         — create file (multipart)
 *   PATCH  /upload/drive/v3/files/{id}?…    — replace file (multipart)
 *   GET    /drive/v3/files/{id}?alt=media   — download bytes
 *   PATCH  /drive/v3/files/{id}             — trash (via {trashed:true})
 *
 * Upload strategy:
 *   - Small payloads (<5 MiB) → `uploadType=multipart` — single round trip,
 *     body carries metadata + bytes. All v2 JSON payloads + the manifest
 *     fall here.
 *   - Larger payloads (>=5 MiB) → `uploadType=resumable`. Deferred to a
 *     later phase; today we fall back to multipart and let Drive accept
 *     up to ~10 MiB payloads. Media blob sync (photos/voice/scans) is
 *     out of scope for the first cut.
 *
 * Rate limits: Drive v3 allows 1000 queries per 100 seconds per user
 * by default. 429, plus 403 responses whose body carries a
 * rate/quota reason, are surfaced as [DriveError.RateLimited];
 * the worker retries those without showing a re-auth banner.
 *
 * `appProperties.releaf_root = true`:
 *   On every root-folder create (or upsert of `manifest.json`), we stamp
 *   `appProperties: { releaf_root: "true" }` on the file. Per
 *   `docs/DRIVE_SCHEMA.md` §"`drive.file` scope + reinstall", this is
 *   how a reinstall + re-auth locates the pre-existing tree when the
 *   user has moved or renamed `Releaf/`.
 */

package app.releaf.mobile.data.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.time.Instant
import java.util.concurrent.TimeUnit

class OkHttpDriveClient(
    private val http: OkHttpClient = DefaultHttpClient,
    private val json: Json = DriveJson,
) : DriveClient {

    // ---------------------------------------------------------------
    // Folder / file lookup
    // ---------------------------------------------------------------

    override suspend fun ensureRootFolder(name: String, accessToken: String): DriveFile =
        withContext(Dispatchers.IO) {
            val segments = name
                .split('/')
                .filter { it.isNotBlank() }
            require(segments.isNotEmpty()) { "Empty root folder name" }

            // Single-segment name → preserve existing Releaf behaviour
            // (appProperties stamp survives user renames, etc.).
            if (segments.size == 1) {
                val single = segments[0]
                val byStamp = queryFirst(
                    q = "appProperties has { key='releaf_root' and value='true' } " +
                        "and mimeType = '$FOLDER_MIME' and trashed = false",
                    accessToken = accessToken,
                )
                if (byStamp != null) return@withContext byStamp

                val byName = queryFirst(
                    q = "name = '${single.escapeForDrive()}' and mimeType = '$FOLDER_MIME' " +
                        "and 'root' in parents and trashed = false",
                    accessToken = accessToken,
                )
                if (byName != null) return@withContext byName

                return@withContext createFolder(
                    name = single,
                    parentId = null,
                    accessToken = accessToken,
                    extraAppProperties = mapOf("releaf_root" to "true"),
                )
            }

            // Nested slash-separated path (e.g. "Thoughtbasics/QuickInk").
            // Walk by name, no appProperties stamp — multi-app namespaces
            // share the outer folder, so a Releaf-specific stamp would
            // wrongly match.
            var current: DriveFile? = null
            segments.forEachIndexed { index, segment ->
                current = if (index == 0) {
                    val byName = queryFirst(
                        q = "name = '${segment.escapeForDrive()}' and mimeType = '$FOLDER_MIME' " +
                            "and 'root' in parents and trashed = false",
                        accessToken = accessToken,
                    )
                    byName ?: createFolder(
                        name = segment,
                        parentId = null,
                        accessToken = accessToken,
                        extraAppProperties = null,
                    )
                } else {
                    val parentId = current?.id
                        ?: error("Path walk lost parent at segment $index")
                    ensureFolder(
                        name = segment,
                        parentId = parentId,
                        accessToken = accessToken,
                    )
                }
            }
            current ?: error("Unreachable: empty path walk")
        }

    override suspend fun ensureFolder(
        name: String,
        parentId: String,
        accessToken: String,
    ): DriveFile = withContext(Dispatchers.IO) {
        val existing = queryFirst(
            q = "name = '${name.escapeForDrive()}' and mimeType = '$FOLDER_MIME' " +
                "and '${parentId.escapeForDrive()}' in parents and trashed = false",
            accessToken = accessToken,
        )
        existing ?: createFolder(
            name = name,
            parentId = parentId,
            accessToken = accessToken,
            extraAppProperties = null,
        )
    }

    override suspend fun listChildren(
        folderId: String,
        accessToken: String,
    ): List<DriveFile> = withContext(Dispatchers.IO) {
        queryAll(
            q = "'${folderId.escapeForDrive()}' in parents and trashed = false",
            accessToken = accessToken,
        )
    }

    // ---------------------------------------------------------------
    // Upload / download
    // ---------------------------------------------------------------

    override suspend fun uploadJson(
        data: ByteArray,
        filename: String,
        parentId: String,
        accessToken: String,
    ): DriveFile = withContext(Dispatchers.IO) {
        // Look for an existing same-name child; if found, PATCH it.
        val existing = queryFirst(
            q = "name = '${filename.escapeForDrive()}' " +
                "and '${parentId.escapeForDrive()}' in parents and trashed = false",
            accessToken = accessToken,
        )
        if (existing != null) {
            updateFile(
                fileId = existing.id,
                data = data,
                contentType = "application/json",
                accessToken = accessToken,
                // Stamp appProperties.releaf_root on the manifest on every
                // write — idempotent and re-asserts after a manual user fix.
                appProperties = if (filename == "manifest.json") mapOf("releaf_root" to "true") else null,
            )
        } else {
            createFile(
                name = filename,
                parentId = parentId,
                data = data,
                contentType = "application/json",
                accessToken = accessToken,
                appProperties = if (filename == "manifest.json") mapOf("releaf_root" to "true") else null,
            )
        }
    }

    override suspend fun uploadBinary(
        data: ByteArray,
        filename: String,
        contentType: String,
        parentId: String,
        accessToken: String,
    ): DriveFile = withContext(Dispatchers.IO) {
        val existing = queryFirst(
            q = "name = '${filename.escapeForDrive()}' " +
                "and '${parentId.escapeForDrive()}' in parents and trashed = false",
            accessToken = accessToken,
        )
        if (existing != null) {
            updateFile(
                fileId = existing.id,
                data = data,
                contentType = contentType,
                accessToken = accessToken,
                appProperties = null,
            )
        } else {
            createFile(
                name = filename,
                parentId = parentId,
                data = data,
                contentType = contentType,
                accessToken = accessToken,
                appProperties = null,
            )
        }
    }

    override suspend fun downloadBytes(
        fileId: String,
        accessToken: String,
    ): ByteArray = withContext(Dispatchers.IO) {
        val url = "$API_BASE/files/${fileId}?alt=media".toHttpUrl()
        val req = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $accessToken")
            .build()
        http.newCall(req).execute().useSafely { resp ->
            if (resp.code == 404) throw DriveError.NotFound
            resp.authOrRateLimitError()?.let { throw it }
            if (!resp.isSuccessful) throw DriveError.Underlying("download failed: ${resp.code}")
            resp.body?.bytes() ?: throw DriveError.Underlying("empty body on download")
        }
    }

    override suspend fun trash(fileId: String, accessToken: String) {
        withContext(Dispatchers.IO) {
            val url = "$API_BASE/files/${fileId}".toHttpUrl()
            val body = """{"trashed": true}""".toRequestBody(JSON_MEDIA)
            val req = Request.Builder()
                .url(url)
                .patch(body)
                .header("Authorization", "Bearer $accessToken")
                .build()
            http.newCall(req).execute().useSafely { resp ->
                if (resp.code == 404) throw DriveError.NotFound
                resp.authOrRateLimitError()?.let { throw it }
                if (!resp.isSuccessful) throw DriveError.Underlying("trash failed: ${resp.code}")
            }
        }
    }

    // ---------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------

    private suspend fun queryFirst(q: String, accessToken: String): DriveFile? {
        val all = queryAll(q, accessToken, pageSize = 1)
        return all.firstOrNull()
    }

    private fun queryAll(
        q: String,
        accessToken: String,
        pageSize: Int = 100,
    ): List<DriveFile> {
        val out = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val urlBuilder = "$API_BASE/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", q)
                .addQueryParameter("spaces", "drive")
                .addQueryParameter("pageSize", pageSize.toString())
                .addQueryParameter(
                    "fields",
                    "nextPageToken, files(id,name,mimeType,parents,modifiedTime)",
                )
            if (pageToken != null) urlBuilder.addQueryParameter("pageToken", pageToken)
            val req = Request.Builder()
                .url(urlBuilder.build())
                .get()
                .header("Authorization", "Bearer $accessToken")
                .build()
            http.newCall(req).execute().useSafely { resp ->
                resp.authOrRateLimitError()?.let { throw it }
                if (!resp.isSuccessful) throw DriveError.Underlying("query failed: ${resp.code}")
                val body = resp.body?.string() ?: throw DriveError.Underlying("empty query response")
                val decoded = json.decodeFromString(FileListResponse.serializer(), body)
                out += decoded.files.map { it.toDomain() }
                pageToken = decoded.nextPageToken
            }
        } while (pageToken != null)
        return out
    }

    private fun createFolder(
        name: String,
        parentId: String?,
        accessToken: String,
        extraAppProperties: Map<String, String>?,
    ): DriveFile {
        val meta = FileMetadata(
            name = name,
            mimeType = FOLDER_MIME,
            parents = parentId?.let { listOf(it) },
            appProperties = extraAppProperties,
        )
        val body = json.encodeToString(FileMetadata.serializer(), meta)
            .toRequestBody(JSON_MEDIA)
        val url = "$API_BASE/files".toHttpUrl().newBuilder()
            .addQueryParameter("fields", "id,name,mimeType,parents,modifiedTime")
            .build()
        val req = Request.Builder()
            .url(url)
            .post(body)
            .header("Authorization", "Bearer $accessToken")
            .build()
        return http.newCall(req).execute().useSafely { resp ->
            resp.authOrRateLimitError()?.let { throw it }
            if (!resp.isSuccessful) throw DriveError.Underlying("createFolder failed: ${resp.code}")
            val respBody = resp.body?.string() ?: throw DriveError.Underlying("empty createFolder response")
            json.decodeFromString(FileResource.serializer(), respBody).toDomain()
        }
    }

    private fun createFile(
        name: String,
        parentId: String,
        data: ByteArray,
        contentType: String,
        accessToken: String,
        appProperties: Map<String, String>?,
    ): DriveFile {
        val meta = FileMetadata(
            name = name,
            mimeType = contentType,
            parents = listOf(parentId),
            appProperties = appProperties,
        )
        val multipart = MultipartBody.Builder()
            .setType(MULTIPART_RELATED)
            .addPart(
                json.encodeToString(FileMetadata.serializer(), meta).toRequestBody(JSON_MEDIA)
            )
            .addPart(data.toRequestBody(contentType.toMediaType()))
            .build()

        val url = "$UPLOAD_BASE/files".toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", "id,name,mimeType,parents,modifiedTime")
            .build()
        val req = Request.Builder()
            .url(url)
            .post(multipart)
            .header("Authorization", "Bearer $accessToken")
            .build()
        return http.newCall(req).execute().useSafely { resp ->
            resp.authOrRateLimitError()?.let { throw it }
            if (!resp.isSuccessful) throw DriveError.Underlying("createFile failed: ${resp.code}")
            val respBody = resp.body?.string() ?: throw DriveError.Underlying("empty createFile response")
            json.decodeFromString(FileResource.serializer(), respBody).toDomain()
        }
    }

    private fun updateFile(
        fileId: String,
        data: ByteArray,
        contentType: String,
        accessToken: String,
        appProperties: Map<String, String>?,
    ): DriveFile {
        val meta = FileMetadata(
            mimeType = contentType,
            appProperties = appProperties,
        )
        val multipart = MultipartBody.Builder()
            .setType(MULTIPART_RELATED)
            .addPart(
                json.encodeToString(FileMetadata.serializer(), meta).toRequestBody(JSON_MEDIA)
            )
            .addPart(data.toRequestBody(contentType.toMediaType()))
            .build()

        val url = "$UPLOAD_BASE/files/$fileId".toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", "id,name,mimeType,parents,modifiedTime")
            .build()
        val req = Request.Builder()
            .url(url)
            .patch(multipart)
            .header("Authorization", "Bearer $accessToken")
            .build()
        return http.newCall(req).execute().useSafely { resp ->
            resp.authOrRateLimitError()?.let { throw it }
            if (resp.code == 404) throw DriveError.NotFound
            if (!resp.isSuccessful) throw DriveError.Underlying("updateFile failed: ${resp.code}")
            val respBody = resp.body?.string() ?: throw DriveError.Underlying("empty updateFile response")
            json.decodeFromString(FileResource.serializer(), respBody).toDomain()
        }
    }

    private fun Response.authOrRateLimitError(): DriveError? = when (code) {
        401 -> DriveError.Unauthenticated
        429 -> DriveError.RateLimited
        403 -> classifyForbidden()
        else -> null
    }

    private fun Response.classifyForbidden(): DriveError {
        val body = runCatching { peekBody(4096).string() }.getOrDefault("")
        val reasons = runCatching {
            json.decodeFromString(GoogleErrorResponse.serializer(), body)
                .error
                ?.errors
                ?.mapNotNull { it.reason }
                .orEmpty()
        }.getOrDefault(emptyList())

        return if (reasons.any { it in RATE_LIMIT_REASONS }) {
            DriveError.RateLimited
        } else {
            DriveError.Unauthenticated
        }
    }

    companion object {
        const val API_BASE    = "https://www.googleapis.com/drive/v3"
        const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val MULTIPART_RELATED = "multipart/related".toMediaType()

        val DriveJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        val DefaultHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()

        private val RATE_LIMIT_REASONS = setOf(
            "rateLimitExceeded",
            "userRateLimitExceeded",
            "dailyLimitExceeded",
            "quotaExceeded",
            "sharingRateLimitExceeded",
        )
    }
}

// ---- wire types (internal to the file; minimal shape) ----

@Serializable
private data class FileMetadata(
    @SerialName("name")            val name: String? = null,
    @SerialName("mimeType")        val mimeType: String? = null,
    @SerialName("parents")         val parents: List<String>? = null,
    @SerialName("appProperties")   val appProperties: Map<String, String>? = null,
)

@Serializable
private data class FileResource(
    @SerialName("id")           val id: String,
    @SerialName("name")         val name: String = "",
    @SerialName("mimeType")     val mimeType: String = "",
    @SerialName("parents")      val parents: List<String>? = null,
    @SerialName("modifiedTime") val modifiedTime: String? = null,
) {
    fun toDomain(): DriveFile = DriveFile(
        id = id,
        name = name,
        mimeType = mimeType,
        parents = parents.orEmpty(),
        modifiedTime = modifiedTime?.let(Instant::parse),
    )
}

@Serializable
private data class FileListResponse(
    @SerialName("nextPageToken") val nextPageToken: String? = null,
    @SerialName("files")         val files: List<FileResource> = emptyList(),
)

@Serializable
private data class GoogleErrorResponse(
    @SerialName("error") val error: GoogleErrorBody? = null,
)

@Serializable
private data class GoogleErrorBody(
    @SerialName("errors") val errors: List<GoogleErrorItem> = emptyList(),
)

@Serializable
private data class GoogleErrorItem(
    @SerialName("reason") val reason: String? = null,
)

// ---- helpers ----

private inline fun <T> Response.useSafely(block: (Response) -> T): T = use(block)

/**
 * Escape a string value for embedding inside a Drive q-parameter
 * expression. Drive's q-syntax uses single quotes as string delimiters;
 * escape quotes + backslashes per Drive docs.
 */
private fun String.escapeForDrive(): String =
    replace("\\", "\\\\").replace("'", "\\'")
