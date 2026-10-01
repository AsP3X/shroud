package de.corespace.shroud.core.realtime

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The socket's clocks (api-realtime §11.6–11.7; plan §1.7.3). Tests pass their own only to
 * shorten nothing — the backoff tests run on virtual time with these defaults.
 *
 * @property clientPing OkHttp's own ping, `RealtimeClient.swift:41` (`keepaliveInterval` 25 s):
 *   the server closes a socket silent for 75 s (`ws.rs:31`), and only a ping of ours notices a
 *   dead one. A missing pong fails the socket into the reconnect path.
 * @property maxBackoff the longest wait between reconnects: `min(30, 2^attempt)` seconds,
 *   `RealtimeClient.swift:265-266` (web `realtime.ts:143` uses the same formula).
 * @property maxAttempt the attempt counter's cap, `RealtimeClient.swift:264`.
 * @property rateLimitedAttemptFloor after `auth.error RATE_LIMITED` the counter is raised to at
 *   least this, so an account over the server's socket cap (`MAX_WS_PER_USER`,
 *   `realtime/mod.rs:32`) retries every 30 s instead of every second — the `auth.ok` before the
 *   error had reset it (api-realtime §11.7, decision §17-4; plan C31).
 */
data class RealtimeTiming(
    val clientPing: Duration = 25.seconds,
    val maxBackoff: Duration = 30.seconds,
    val maxAttempt: Int = 8,
    val rateLimitedAttemptFloor: Int = 5,
) {
    /**
     * The wait before reconnect attempt [attempt] (the counter's value *before* the increment):
     * 1, 2, 4, 8, 16, 30, 30 … s (`RealtimeClient.swift:263-266`).
     */
    fun backoff(attempt: Int): Duration {
        val exponent = attempt.coerceIn(0, 30)
        val seconds = (1L shl exponent).seconds
        return if (seconds < maxBackoff) seconds else maxBackoff
    }
}
