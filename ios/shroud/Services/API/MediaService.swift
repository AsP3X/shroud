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
        // Server limit is 25 MiB (encrypted blob). Fail early with a clear message.
        let maxBytes = 25 * 1024 * 1024
        guard data.count <= maxBytes else {
            let mb = max(1, data.count / 1_048_576)
            throw APIError.server(
                code: "VALIDATION_ERROR",
                message: "This media is too large after encryption (\(mb) MB). Try a shorter video or lower photo quality.",
                statusCode: 400
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
