/*
 * URLSessionDriveClient.swift
 *
 * Real Drive v3 REST client for iOS. Mirror of Android's
 * `OkHttpDriveClient.kt` — same endpoints, same upload strategy, same
 * `appProperties.releaf_root = true` stamp for reinstall-recovery.
 *
 * Built on `URLSession` with async/await. No SDK wrapper.
 *
 * Scope: `drive.file`. All `files.list` queries implicitly narrow to
 * files this OAuth client created.
 *
 * Endpoints:
 *   GET    /drive/v3/files?q=…             — lookup by name + parent
 *   POST   /drive/v3/files                  — create folder
 *   POST   /upload/drive/v3/files?…         — create file (multipart)
 *   PATCH  /upload/drive/v3/files/{id}?…    — replace file (multipart)
 *   GET    /drive/v3/files/{id}?alt=media   — download bytes
 *   PATCH  /drive/v3/files/{id}             — trash
 *
 * Upload size rule: < 5 MiB → multipart single round-trip (all JSON
 * payloads + the manifest). Resumable uploads for larger blobs (media
 * sync) are deferred to a later phase.
 */

import Foundation

public final class URLSessionDriveClient: DriveClient, @unchecked Sendable {

    private let session: URLSession

    public init(session: URLSession = URLSessionDriveClient.defaultSession()) {
        self.session = session
    }

    // MARK: - DriveClient

    public func ensureRootFolder(
        named name: String,
        accessToken: String
    ) async throws -> DriveFile {
        // 1) Try by appProperties stamp first — survives user renames.
        if let byStamp = try await queryFirst(
            q: "appProperties has { key='releaf_root' and value='true' } " +
               "and mimeType = '\(Self.folderMime)' and trashed = false",
            accessToken: accessToken
        ) {
            return byStamp
        }
        // 2) Try by name at root.
        if let byName = try await queryFirst(
            q: "name = '\(escapeForDrive(name))' and mimeType = '\(Self.folderMime)' " +
               "and 'root' in parents and trashed = false",
            accessToken: accessToken
        ) {
            return byName
        }
        // 3) Create, stamped.
        return try await createFolder(
            name: name,
            parentId: nil,
            accessToken: accessToken,
            appProperties: ["releaf_root": "true"]
        )
    }

    public func ensureFolder(
        named name: String,
        parentId: String,
        accessToken: String
    ) async throws -> DriveFile {
        if let existing = try await queryFirst(
            q: "name = '\(escapeForDrive(name))' and mimeType = '\(Self.folderMime)' " +
               "and '\(escapeForDrive(parentId))' in parents and trashed = false",
            accessToken: accessToken
        ) {
            return existing
        }
        return try await createFolder(
            name: name,
            parentId: parentId,
            accessToken: accessToken,
            appProperties: nil
        )
    }

    public func listChildren(
        of folderId: String,
        accessToken: String
    ) async throws -> [DriveFile] {
        try await queryAll(
            q: "'\(escapeForDrive(folderId))' in parents and trashed = false",
            accessToken: accessToken
        )
    }

    public func uploadJSON(
        _ data: Data,
        filename: String,
        parentId: String,
        accessToken: String
    ) async throws -> DriveFile {
        // PATCH an existing same-name child if present.
        if let existing = try await queryFirst(
            q: "name = '\(escapeForDrive(filename))' " +
               "and '\(escapeForDrive(parentId))' in parents and trashed = false",
            accessToken: accessToken
        ) {
            return try await updateFile(
                fileId: existing.id,
                data: data,
                contentType: "application/json",
                accessToken: accessToken,
                appProperties: filename == "manifest.json" ? ["releaf_root": "true"] : nil
            )
        }
        return try await createFile(
            name: filename,
            parentId: parentId,
            data: data,
            contentType: "application/json",
            accessToken: accessToken,
            appProperties: filename == "manifest.json" ? ["releaf_root": "true"] : nil
        )
    }

    public func downloadBytes(
        fileId: String,
        accessToken: String
    ) async throws -> Data {
        var url = URLComponents(string: "\(Self.apiBase)/files/\(fileId)")!
        url.queryItems = [URLQueryItem(name: "alt", value: "media")]
        var req = URLRequest(url: url.url!)
        req.httpMethod = "GET"
        req.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")

        let (data, resp) = try await session.data(for: req)
        try Self.validate(response: resp)
        return data
    }

    public func trash(fileId: String, accessToken: String) async throws {
        let url = URL(string: "\(Self.apiBase)/files/\(fileId)")!
        var req = URLRequest(url: url)
        req.httpMethod = "PATCH"
        req.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = Data("{\"trashed\":true}".utf8)
        let (_, resp) = try await session.data(for: req)
        try Self.validate(response: resp)
    }

    // MARK: - Internals

    private func queryFirst(q: String, accessToken: String) async throws -> DriveFile? {
        let results = try await queryAll(q: q, accessToken: accessToken, pageSize: 1)
        return results.first
    }

    private func queryAll(
        q: String,
        accessToken: String,
        pageSize: Int = 100
    ) async throws -> [DriveFile] {
        var out: [DriveFile] = []
        var pageToken: String? = nil
        repeat {
            var components = URLComponents(string: "\(Self.apiBase)/files")!
            var items: [URLQueryItem] = [
                URLQueryItem(name: "q", value: q),
                URLQueryItem(name: "spaces", value: "drive"),
                URLQueryItem(name: "pageSize", value: String(pageSize)),
                URLQueryItem(name: "fields", value: "nextPageToken, files(id,name,mimeType,parents,modifiedTime)"),
            ]
            if let t = pageToken {
                items.append(URLQueryItem(name: "pageToken", value: t))
            }
            components.queryItems = items
            var req = URLRequest(url: components.url!)
            req.httpMethod = "GET"
            req.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")

            let (data, resp) = try await session.data(for: req)
            try Self.validate(response: resp)
            let decoded = try JSONDecoder().decode(FileListResponse.self, from: data)
            out.append(contentsOf: decoded.files.map(\.asDriveFile))
            pageToken = decoded.nextPageToken
        } while pageToken != nil
        return out
    }

    private func createFolder(
        name: String,
        parentId: String?,
        accessToken: String,
        appProperties: [String: String]?
    ) async throws -> DriveFile {
        let meta = FileMetadata(
            name: name,
            mimeType: Self.folderMime,
            parents: parentId.map { [$0] },
            appProperties: appProperties
        )
        var components = URLComponents(string: "\(Self.apiBase)/files")!
        components.queryItems = [
            URLQueryItem(name: "fields", value: "id,name,mimeType,parents,modifiedTime"),
        ]
        var req = URLRequest(url: components.url!)
        req.httpMethod = "POST"
        req.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONEncoder().encode(meta)

        let (data, resp) = try await session.data(for: req)
        try Self.validate(response: resp)
        return try JSONDecoder().decode(FileResource.self, from: data).asDriveFile
    }

    private func createFile(
        name: String,
        parentId: String,
        data: Data,
        contentType: String,
        accessToken: String,
        appProperties: [String: String]?
    ) async throws -> DriveFile {
        let meta = FileMetadata(
            name: name,
            mimeType: contentType,
            parents: [parentId],
            appProperties: appProperties
        )
        var components = URLComponents(string: "\(Self.uploadBase)/files")!
        components.queryItems = [
            URLQueryItem(name: "uploadType", value: "multipart"),
            URLQueryItem(name: "fields", value: "id,name,mimeType,parents,modifiedTime"),
        ]
        var req = URLRequest(url: components.url!)
        req.httpMethod = "POST"
        req.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")

        let boundary = "releaf-multipart-\(UUID().uuidString)"
        req.setValue("multipart/related; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        req.httpBody = try buildMultipart(meta: meta, data: data, contentType: contentType, boundary: boundary)

        let (respData, resp) = try await session.data(for: req)
        try Self.validate(response: resp)
        return try JSONDecoder().decode(FileResource.self, from: respData).asDriveFile
    }

    private func updateFile(
        fileId: String,
        data: Data,
        contentType: String,
        accessToken: String,
        appProperties: [String: String]?
    ) async throws -> DriveFile {
        let meta = FileMetadata(
            name: nil,
            mimeType: contentType,
            parents: nil,
            appProperties: appProperties
        )
        var components = URLComponents(string: "\(Self.uploadBase)/files/\(fileId)")!
        components.queryItems = [
            URLQueryItem(name: "uploadType", value: "multipart"),
            URLQueryItem(name: "fields", value: "id,name,mimeType,parents,modifiedTime"),
        ]
        var req = URLRequest(url: components.url!)
        req.httpMethod = "PATCH"
        req.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")

        let boundary = "releaf-multipart-\(UUID().uuidString)"
        req.setValue("multipart/related; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        req.httpBody = try buildMultipart(meta: meta, data: data, contentType: contentType, boundary: boundary)

        let (respData, resp) = try await session.data(for: req)
        try Self.validate(response: resp)
        return try JSONDecoder().decode(FileResource.self, from: respData).asDriveFile
    }

    private func buildMultipart(
        meta: FileMetadata,
        data: Data,
        contentType: String,
        boundary: String
    ) throws -> Data {
        let metaData = try JSONEncoder().encode(meta)
        var body = Data()
        body.append(Data("--\(boundary)\r\n".utf8))
        body.append(Data("Content-Type: application/json; charset=UTF-8\r\n\r\n".utf8))
        body.append(metaData)
        body.append(Data("\r\n--\(boundary)\r\n".utf8))
        body.append(Data("Content-Type: \(contentType)\r\n\r\n".utf8))
        body.append(data)
        body.append(Data("\r\n--\(boundary)--\r\n".utf8))
        return body
    }

    // MARK: - Helpers

    private static func validate(response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse else {
            throw DriveError.underlying("Non-HTTP response")
        }
        switch http.statusCode {
        case 200...299: return
        case 401: throw DriveError.unauthenticated
        case 404: throw DriveError.notFound
        default:  throw DriveError.underlying("HTTP \(http.statusCode)")
        }
    }

    private func escapeForDrive(_ s: String) -> String {
        s.replacingOccurrences(of: "\\", with: "\\\\")
         .replacingOccurrences(of: "'", with: "\\'")
    }

    public static func defaultSession() -> URLSession {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 120
        config.waitsForConnectivity = false
        return URLSession(configuration: config)
    }

    public static let apiBase    = "https://www.googleapis.com/drive/v3"
    public static let uploadBase = "https://www.googleapis.com/upload/drive/v3"
    public static let folderMime = "application/vnd.google-apps.folder"
}

// MARK: - Wire types

private struct FileMetadata: Encodable {
    let name: String?
    let mimeType: String?
    let parents: [String]?
    let appProperties: [String: String]?

    enum CodingKeys: String, CodingKey {
        case name, mimeType, parents, appProperties
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        if let name = name { try c.encode(name, forKey: .name) }
        if let mimeType = mimeType { try c.encode(mimeType, forKey: .mimeType) }
        if let parents = parents { try c.encode(parents, forKey: .parents) }
        if let ap = appProperties { try c.encode(ap, forKey: .appProperties) }
    }
}

private struct FileResource: Decodable {
    let id: String
    let name: String?
    let mimeType: String?
    let parents: [String]?
    let modifiedTime: String?

    var asDriveFile: DriveFile {
        let date: Date?
        if let t = modifiedTime {
            date = ISO8601DateFormatter().date(from: t)
        } else {
            date = nil
        }
        return DriveFile(
            id: id,
            name: name ?? "",
            mimeType: mimeType ?? "",
            parents: parents ?? [],
            modifiedTime: date
        )
    }
}

private struct FileListResponse: Decodable {
    let nextPageToken: String?
    let files: [FileResource]
}
