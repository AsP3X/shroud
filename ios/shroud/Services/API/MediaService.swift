import Foundation

/// Media upload/download via the Shroud API (encrypted blobs stay behind Bearer auth).
struct MediaService: Sendable {
    private var client: APIClient { .makeConfiguredClient() }

    func createUpload(
        sizeBytes: Int,
        contentType: String = "application/octet-stream",
        token: String
    ) async throws -> CreateMediaUploadResponse {
        try await client.post(
            "media/uploads",
            body: CreateMediaUploadRequest(
                sizeBytes: Int64(sizeBytes),
                contentType: contentType
            ),
            as: CreateMediaUploadResponse.self,
            bearerToken: token
        )
    }

    func createDownload(mediaID: UUID, token: String) async throws -> MediaDownloadResponse {
        struct Empty: Encodable {}
        return try await client.post(
            "media/\(mediaID.uuidString.lowercased())/download",
            body: Empty(),
            as: MediaDownloadResponse.self,
            bearerToken: token
        )
    }

    /// PUT encrypted bytes to `media/{id}/content` (API, authenticated).
    ///
    /// `onProgress` (0…1) drives the outbound bubble's ring; omit it for fire-and-forget sends.
    func uploadContent(
        mediaID: UUID,
        data: Data,
        token: String,
        onProgress: (@Sendable (Double) -> Void)? = nil
    ) async throws {
        // Server limit is 2 GiB (encrypted blob). Fail early with a clear message.
        let maxBytes = VideoMedia.maxSealedBytes
        guard data.count <= maxBytes else {
            let mb = max(1, data.count / 1_048_576)
            throw APIError.server(
                code: "VALIDATION_ERROR",
                message: "This media is too large after encryption (\(mb) MB). Try a shorter video or lower photo quality.",
                statusCode: 400,
                reason: nil
            )
        }
        let path = "media/\(mediaID.uuidString.lowercased())/content"
        if let onProgress {
            try await client.putRaw(
                path: path,
                body: data,
                contentType: "application/octet-stream",
                bearerToken: token,
                onProgress: onProgress
            )
        } else {
            try await client.putRaw(
                path: path,
                body: data,
                contentType: "application/octet-stream",
                bearerToken: token
            )
        }
    }

    /// PUT a sealed file (SHRF1) to `media/{id}/content`, streamed from disk.
    ///
    /// Human: The content type stays `application/octet-stream`, so the server never learns
    /// that a blob is a PDF or an APK.
    func uploadContent(
        mediaID: UUID,
        fileURL: URL,
        token: String,
        onProgress: (@Sendable (Double) -> Void)? = nil
    ) async throws {
        let size = FileBlob.fileSize(at: fileURL) ?? 0
        guard size > 0, size <= Int64(VideoMedia.maxSealedBytes) else {
            throw APIError.server(
                code: "VALIDATION_ERROR",
                message: "This file is too large to send.",
                statusCode: 400,
                reason: nil
            )
        }
        try await client.putFile(
            path: "media/\(mediaID.uuidString.lowercased())/content",
            fileURL: fileURL,
            contentType: "application/octet-stream",
            bearerToken: token,
            onProgress: onProgress
        )
    }

    /// GET a sealed file into a temporary file the caller then owns (moves or deletes).
    func downloadContentToFile(
        mediaID: UUID,
        token: String,
        onProgress: (@Sendable (Double) -> Void)? = nil
    ) async throws -> URL {
        try await client.downloadFile(
            path: "media/\(mediaID.uuidString.lowercased())/content",
            bearerToken: token,
            onProgress: onProgress
        )
    }

    /// GET encrypted bytes from `media/{id}/content` (API, authenticated).
    ///
    /// `onProgress` (0…1) drives the download ring on the media bubble.
    func downloadContent(
        mediaID: UUID,
        token: String,
        onProgress: (@Sendable (Double) -> Void)? = nil
    ) async throws -> Data {
        let path = "media/\(mediaID.uuidString.lowercased())/content"
        if let onProgress {
            return try await client.getRaw(path: path, bearerToken: token, onProgress: onProgress)
        }
        return try await client.getRaw(path: path, bearerToken: token)
    }
}
