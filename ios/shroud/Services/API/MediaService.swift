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
    func uploadContent(mediaID: UUID, data: Data, token: String) async throws {
        try await client.putRaw(
            path: "media/\(mediaID.uuidString.lowercased())/content",
            body: data,
            contentType: "application/octet-stream",
            bearerToken: token
        )
    }

    /// GET encrypted bytes from `media/{id}/content` (API, authenticated).
    func downloadContent(mediaID: UUID, token: String) async throws -> Data {
        try await client.getRaw(
            path: "media/\(mediaID.uuidString.lowercased())/content",
            bearerToken: token
        )
    }
}
