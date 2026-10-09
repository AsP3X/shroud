package de.corespace.shroud.core.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class UsernameHashTest {
    @Test
    fun argon2idMatchesThePinnedVector() {
        assertEquals(
            "4b4IohXsRZvTapVS+ZJWMkcBP5keMgo8hGWaLN+boI4=",
            UsernameHash.argon2id("alice", UsernameKdfParams.TEST),
        )
    }

    @Test
    fun sha256StaysTheFingerprint() {
        assertEquals("K9gGyX8OAK8aH8Myj6djqSaXI8jbj6xPk69x2xhtbpA=", UsernameHash.digest("alice"))
    }
}
