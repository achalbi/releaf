/*
 * DriveClient.swift
 * Protocol describing the subset of Google Drive we need.
 *
 * Real implementation: wrap `GoogleAPIClientForREST_Drive`
 * (https://github.com/google/google-api-objectivec-client-for-rest)
 * or call the REST API directly with URLSession.
 */

import Foundation

public struct DriveFile: Equatable, Sendable {
    public let id: String
    public let name: String
    public let mimeType: String
    public let parents: [String]
    public let modifiedTime: Date?

    public init(id: String, name: String, mimeType: String, parents: [String] = [], modifiedTime: Date? = nil) {
        self.id = id
        self.name = name
        self.mimeType = mimeType
        self.parents = parents
        self.modifiedTime = modifiedTime
    }

    public var isFolder: Bool { mimeType == "application/vnd.google-apps.folder" }
}

public enum DriveError: Error, Equatable {
    case unauthenticated
    case notFound
    case notImplemented
    case underlying(String)
}

/// Tiny façade over Drive. Everything is async; auth is passed in per-call
/// so the client doesn't need its own token lifecycle.
public protocol DriveClient: AnyObject, Sendable {
    /// Find or create the top-level user-visible folder (default name: "Inkcreate").
    func ensureRootFolder(named name: String, accessToken: String) async throws -> DriveFile

    /// Find or create a named folder under `parentId`.
    func ensureFolder(named name: String, parentId: String, accessToken: String) async throws -> DriveFile

    /// List direct children of `folderId`.
    func listChildren(of folderId: String, accessToken: String) async throws -> [DriveFile]

    /// Upload / overwrite a small JSON file. `filename` is the Drive file name.
    func uploadJSON(_ data: Data, filename: String, parentId: String, accessToken: String) async throws -> DriveFile

    /// Download a file's bytes.
    func downloadBytes(fileId: String, accessToken: String) async throws -> Data

    /// Move a file to the Drive trash.
    func trash(fileId: String, accessToken: String) async throws
}

/// In-memory stub — lets skeletons + previews render without real Drive calls.
public final class InMemoryDriveClient: DriveClient, @unchecked Sendable {
    private var files: [String: DriveFile] = [:]
    private var blobs: [String: Data] = [:]
    private let queue = DispatchQueue(label: "releaf.InMemoryDriveClient")

    public init() {}

    public func ensureRootFolder(named name: String, accessToken: String) async throws -> DriveFile {
        queue.sync {
            if let existing = files.values.first(where: { $0.name == name && $0.parents.isEmpty && $0.isFolder }) {
                return existing
            }
            let file = DriveFile(id: UUID().uuidString, name: name, mimeType: "application/vnd.google-apps.folder")
            files[file.id] = file
            return file
        }
    }

    public func ensureFolder(named name: String, parentId: String, accessToken: String) async throws -> DriveFile {
        queue.sync {
            if let existing = files.values.first(where: { $0.name == name && $0.parents == [parentId] && $0.isFolder }) {
                return existing
            }
            let file = DriveFile(id: UUID().uuidString, name: name, mimeType: "application/vnd.google-apps.folder", parents: [parentId])
            files[file.id] = file
            return file
        }
    }

    public func listChildren(of folderId: String, accessToken: String) async throws -> [DriveFile] {
        queue.sync { files.values.filter { $0.parents.contains(folderId) } }
    }

    public func uploadJSON(_ data: Data, filename: String, parentId: String, accessToken: String) async throws -> DriveFile {
        queue.sync {
            if let existing = files.values.first(where: { $0.name == filename && $0.parents == [parentId] }) {
                blobs[existing.id] = data
                return existing
            }
            let file = DriveFile(id: UUID().uuidString, name: filename, mimeType: "application/json", parents: [parentId])
            files[file.id] = file
            blobs[file.id] = data
            return file
        }
    }

    public func downloadBytes(fileId: String, accessToken: String) async throws -> Data {
        try queue.sync {
            guard let data = blobs[fileId] else { throw DriveError.notFound }
            return data
        }
    }

    public func trash(fileId: String, accessToken: String) async throws {
        queue.sync {
            files.removeValue(forKey: fileId)
            blobs.removeValue(forKey: fileId)
        }
    }
}
