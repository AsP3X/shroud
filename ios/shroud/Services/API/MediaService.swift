import Foundation

/// Media upload/download via API presigns + direct blob HTTP to Nebular/stub.
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

    /// PUT encrypted bytes to a presigned upload URL (not the API host).
    func upload(data: Data, to uploadURLString: String) async throws {
        guard let url = URL(string: uploadURLString), url.scheme != "stub" else {
            throw APIError.transport("Media storage is not configured on the server.")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        request.httpBody = data
        let (_, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200 ... 299).contains(http.statusCode) else {
            let code = (response as? HTTPURLResponse)?.statusCode ?? -1
            throw APIError.transport("Media upload failed (HTTP \(code)).")
        }
    }

    /// GET encrypted bytes from a presigned download URL.
    func download(from downloadURLString: String) async throws -> Data {
        guard let url = URL(string: downloadURLString), url.scheme != "stub" else {
            throw APIError.transport("Media storage is not configured on the server.")
        }
        let (data, response) = try await URLSession.shared.data(from: url)
        guard let http = response as? HTTPURLResponse, (200 ... 299).contains(http.statusCode) else {
            let code = (response as? HTTPURLResponse)?.statusCode ?? -1
            throw APIError.transport("Media download failed (HTTP \(code)).")
        }
        return data
    }
}
