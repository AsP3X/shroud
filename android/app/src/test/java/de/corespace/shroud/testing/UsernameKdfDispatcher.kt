package de.corespace.shroud.testing

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.QueueDispatcher
import mockwebserver3.RecordedRequest
import java.util.concurrent.TimeUnit

/**
 * Answers `GET /auth/username-kdf` with the pinned test parameters and leaves every other
 * request on the queue, so [MockWebServer.enqueue] still feeds register and login.
 */
class UsernameKdfDispatcher : QueueDispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
        if (request.url.encodedPath.endsWith("/auth/username-kdf")) {
            return MockResponse(code = 200, body = USERNAME_KDF_JSON)
        }
        return super.dispatch(request)
    }

    companion object {
        const val USERNAME_KDF_JSON =
            """{"algorithm":"argon2id","version":19,"salt":"ABEiM0RVZneImaq7zN3u/w==","memory_kib":65536,"iterations":8,"parallelism":1,"output_bytes":32}"""
    }
}

fun MockWebServer.serveUsernameKdf() {
    dispatcher = UsernameKdfDispatcher()
}

/** The next request that is not the public username-KDF fetch. That fetch is answered by the dispatcher and still recorded. */
fun MockWebServer.takeApiRequest(timeout: Long = 5, unit: TimeUnit = TimeUnit.SECONDS): RecordedRequest {
    while (true) {
        val request = takeRequest(timeout, unit) ?: error("timed out waiting for an API request")
        if (!request.url.encodedPath.endsWith("/auth/username-kdf")) return request
    }
}
