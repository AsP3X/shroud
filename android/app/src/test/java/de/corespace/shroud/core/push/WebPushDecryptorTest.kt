package de.corespace.shroud.core.push

import de.corespace.shroud.core.crypto.Primitives
import de.corespace.shroud.core.push.unifiedpush.P256
import de.corespace.shroud.core.push.unifiedpush.WebPushDecryptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * RFC 8291 Appendix A. The same ciphertext is what `web_push.rs`
 * `matches_the_rfc_8291_example` seals from the RFC's ECDH secret, user-agent public key,
 * application-server public key, auth secret and salt. The user-agent private key is the one in
 * the RFC (the server test cannot import it). One open covers both vectors.
 */
class WebPushDecryptorTest {
    @Test
    fun aGeneratedSubscriptionKeyIsAUsableP256Pair() {
        val keys = P256.generate()
        assertEquals(32, keys?.privateKey?.size)
        assertEquals(65, keys?.publicKey?.size)
        assertEquals(0x04, keys?.publicKey?.get(0)?.toInt())
        assertEquals(16, keys?.auth?.size)
        val secret = P256.sharedSecret(keys!!.privateKey, keys.publicKey)
        assertEquals(32, secret?.size)
    }

    @Test
    fun rfc8291AppendixAAndTheServerVector() {
        val plain = WebPushDecryptor.open(body(), uaPrivate(), uaPublic(), auth())
        assertEquals(PLAINTEXT, plain?.toString(Charsets.UTF_8))
    }

    @Test
    fun wrongAuthSecretFailsClosed() {
        val auth = auth()
        auth[auth.size - 1] = (auth[auth.size - 1].toInt() xor 0x01).toByte()
        assertNull(WebPushDecryptor.open(body(), uaPrivate(), uaPublic(), auth))
    }

    @Test
    fun truncatedRecordFailsClosed() {
        val body = body()
        assertNull(WebPushDecryptor.open(body.copyOf(body.size - 1), uaPrivate(), uaPublic(), auth()))
        assertNull(WebPushDecryptor.open(body.copyOf(30), uaPrivate(), uaPublic(), auth()))
    }

    @Test
    fun badPaddingFailsClosed() {
        val body = body()
        val asPublic = body.copyOfRange(21, 21 + 65)
        val secret = P256.sharedSecret(uaPrivate(), asPublic)!!
        val salt = body.copyOfRange(0, 16)
        val prefix = "WebPush: info\u0000".toByteArray(Charsets.US_ASCII)
        val info = prefix + uaPublic() + asPublic
        val ikm = Primitives.hkdf(secret, auth(), info, 32)
        val cek = Primitives.hkdf(ikm, salt, "Content-Encoding: aes128gcm\u0000".toByteArray(Charsets.US_ASCII), 16)
        val nonce = Primitives.hkdf(ikm, salt, "Content-Encoding: nonce\u0000".toByteArray(Charsets.US_ASCII), 12)
        val bad = PLAINTEXT.toByteArray(Charsets.UTF_8) + byteArrayOf(0x01)
        val sealed = Primitives.aesGcmSeal(cek, nonce, bad).copyOfRange(12, Primitives.aesGcmSeal(cek, nonce, bad).size)
        val forged = body.copyOfRange(0, 21 + 65) + sealed
        assertNull(WebPushDecryptor.open(forged, uaPrivate(), uaPublic(), auth()))
        ikm.fill(0)
        cek.fill(0)
        nonce.fill(0)
        secret.fill(0)
    }

    private fun body(): ByteArray = b64(BODY)

    private fun uaPrivate(): ByteArray = b64(UA_PRIVATE)

    private fun uaPublic(): ByteArray = b64(UA_PUBLIC)

    private fun auth(): ByteArray = b64(AUTH)

    private fun b64(value: String): ByteArray {
        val pad = (4 - value.length % 4) % 4
        return Base64.getUrlDecoder().decode(value + "=".repeat(pad))
    }

    private companion object {
        const val PLAINTEXT = "When I grow up, I want to be a watermelon"
        const val BODY =
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"
        const val UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94"
        const val UA_PUBLIC =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
        const val AUTH = "BTBZMqHH6r4Tts7J_aSIgg"
    }
}
