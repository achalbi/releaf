/*
 * DriveClientPath.swift
 *
 * Path-aware helpers over `DriveClient`. Mirror of Android's
 * `DriveClientPath.kt` — extension functions that compose on top of
 * the base protocol (ensureRootFolder / ensureFolder / uploadJSON /
 * downloadBytes / listChildren / trash) so neither the real REST
 * client nor the in-memory stub needs extra methods.
 */

import Foundation

public extension DriveClient {

    /// Ensure every folder in [relativePath] exists under [rootFolderId],
    /// creating any missing intermediates. Returns the Drive file id of
    /// the leaf folder.
    func ensurePath(
        _ relativePath: String,
        rootFolderId: String,
        accessToken: String
    ) async throws -> String {
        let segments = relativePath.split(separator: "/").map(String.init)
        var currentId = rootFolderId
        for seg in segments {
            let f = try await ensureFolder(named: seg, parentId: currentId, accessToken: accessToken)
            currentId = f.id
        }
        return currentId
    }

    /// Upload a JSON payload to [relativePath] under [rootFolderId],
    /// creating missing intermediate folders. Replaces an existing file
    /// with the same path.
    @discardableResult
    func uploadJSONAtPath(
        _ data: Data,
        relativePath: String,
        rootFolderId: String,
        accessToken: String
    ) async throws -> DriveFile {
        let segments = relativePath.split(separator: "/").map(String.init)
        guard !segments.isEmpty else {
            throw DriveError.underlying("uploadJSONAtPath: empty path")
        }
        let filename = segments.last!
        let folderSegments = segments.dropLast()
        let folderId: String
        if folderSegments.isEmpty {
            folderId = rootFolderId
        } else {
            folderId = try await ensurePath(
                folderSegments.joined(separator: "/"),
                rootFolderId: rootFolderId,
                accessToken: accessToken
            )
        }
        return try await uploadJSON(data, filename: filename, parentId: folderId, accessToken: accessToken)
    }

    /// Upload binary bytes (PDF, JPEG, etc.) to [relativePath] under
    /// [rootFolderId], creating missing intermediate folders. Replaces
    /// an existing file with the same path.
    @discardableResult
    func uploadBinaryAtPath(
        _ data: Data,
        contentType: String,
        relativePath: String,
        rootFolderId: String,
        accessToken: String
    ) async throws -> DriveFile {
        let segments = relativePath.split(separator: "/").map(String.init)
        guard !segments.isEmpty else {
            throw DriveError.underlying("uploadBinaryAtPath: empty path")
        }
        let filename = segments.last!
        let folderSegments = segments.dropLast()
        let folderId: String
        if folderSegments.isEmpty {
            folderId = rootFolderId
        } else {
            folderId = try await ensurePath(
                folderSegments.joined(separator: "/"),
                rootFolderId: rootFolderId,
                accessToken: accessToken
            )
        }
        return try await uploadBinary(
            data,
            filename: filename,
            contentType: contentType,
            parentId: folderId,
            accessToken: accessToken
        )
    }

    /// Download the bytes at [relativePath]. Returns nil when any
    /// folder along the path is missing or when the leaf file doesn't
    /// exist.
    func downloadBytesAtPath(
        _ relativePath: String,
        rootFolderId: String,
        accessToken: String
    ) async throws -> Data? {
        let segments = relativePath.split(separator: "/").map(String.init)
        guard !segments.isEmpty else { return nil }

        var currentId = rootFolderId
        for i in 0..<(segments.count - 1) {
            let name = segments[i]
            let children = try await listChildren(of: currentId, accessToken: accessToken)
            guard let folder = children.first(where: { $0.name == name && $0.isFolder }) else {
                return nil
            }
            currentId = folder.id
        }
        let filename = segments.last!
        let children = try await listChildren(of: currentId, accessToken: accessToken)
        guard let file = children.first(where: { $0.name == filename && !$0.isFolder }) else {
            return nil
        }
        do {
            return try await downloadBytes(fileId: file.id, accessToken: accessToken)
        } catch DriveError.notFound {
            return nil
        }
    }

    /// Trash the file at [relativePath]. Returns true when found and
    /// trashed, false otherwise.
    @discardableResult
    func trashAtPath(
        _ relativePath: String,
        rootFolderId: String,
        accessToken: String
    ) async throws -> Bool {
        let segments = relativePath.split(separator: "/").map(String.init)
        guard !segments.isEmpty else { return false }

        var currentId = rootFolderId
        for i in 0..<(segments.count - 1) {
            let name = segments[i]
            let children = try await listChildren(of: currentId, accessToken: accessToken)
            guard let folder = children.first(where: { $0.name == name && $0.isFolder }) else {
                return false
            }
            currentId = folder.id
        }
        let filename = segments.last!
        let children = try await listChildren(of: currentId, accessToken: accessToken)
        guard let file = children.first(where: { $0.name == filename && !$0.isFolder }) else {
            return false
        }
        do {
            try await trash(fileId: file.id, accessToken: accessToken)
            return true
        } catch {
            return false
        }
    }
}
