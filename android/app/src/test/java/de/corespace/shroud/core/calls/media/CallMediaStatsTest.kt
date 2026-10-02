package de.corespace.shroud.core.calls.media

import de.corespace.shroud.core.calls.signal.CallSdp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audio level and the DTLS fingerprint, read the way the web client reads `getStats`. */
class CallMediaStatsTest {
    @Test
    fun audioLevelComesFromTheMediaSourceAndStaysInRange() {
        val rows = listOf(
            StatRow("track-1", "track", mapOf("audioLevel" to 0.9)),
            StatRow("src-1", "media-source", mapOf("audioLevel" to "0.25")),
        )
        assertEquals(0.25f, audioLevel(rows))
        assertEquals(0.5f, parseLevel(0.5))
        assertEquals(1f, parseLevel(1.4))
        assertEquals(0f, parseLevel(-0.2))
        assertEquals(0.5f, parseLevel("0.5"))
        assertNull(parseLevel("loud"))
        assertNull(parseLevel(Double.NaN))
        assertNull(audioLevel(emptyList()))
        assertEquals(0.9f, audioLevel(listOf(StatRow("track-1", "track", mapOf("audioLevel" to 0.9)))))
    }

    @Test
    fun theRemoteCertificateFingerprintMatchesTheSealedDescription() {
        val fingerprint = "AB:CD:EF:01"
        val rows = listOf(
            StatRow("T0", "transport", mapOf("remoteCertificateId" to "C0")),
            StatRow("C0", "certificate", mapOf("fingerprint" to fingerprint, "fingerprintAlgorithm" to "SHA-256")),
        )
        assertEquals(fingerprint, remoteFingerprint(rows))
        assertTrue(CallSdp.matches("ab:cd:ef:01", remoteFingerprint(rows)!!))
        assertNull(
            remoteFingerprint(
                listOf(
                    StatRow("T0", "transport", mapOf("remoteCertificateId" to "C0")),
                    StatRow("C0", "certificate", mapOf("fingerprint" to fingerprint, "fingerprintAlgorithm" to "sha-1")),
                ),
            ),
        )
        assertNull(remoteFingerprint(listOf(StatRow("T0", "transport", emptyMap()))))
        assertNull(
            remoteFingerprint(
                listOf(
                    StatRow("T0", "transport", mapOf("remoteCertificateId" to "C0")),
                    StatRow("C0", "certificate", mapOf("fingerprint" to "  ")),
                ),
            ),
        )
    }
}
