package de.corespace.shroud.core.push.unifiedpush

import de.corespace.shroud.core.crypto.Primitives
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64

/**
 * RFC 8291 `aes128gcm` (RFC 8188) on BouncyCastle's lightweight API. One record, padding delimiter
 * `0x02`. Returns null on every failure (truncated header, bad key id, record longer than `rs`,
 * bad tag, bad padding). Never logs the body or the keys.
 */
object WebPushDecryptor {
    private const val MAX_RECORD_SIZE = 4096
    private const val PUBLIC_KEY_BYTES = 65
    private const val HEADER_FIXED = 21

    fun open(body: ByteArray, uaPrivate: ByteArray, uaPublic: ByteArray, auth: ByteArray): ByteArray? {
        if (uaPrivate.size != 32 || uaPublic.size != PUBLIC_KEY_BYTES || auth.size != 16) return null
        if (body.size < HEADER_FIXED) return null
        var secret: ByteArray? = null
        var ikm: ByteArray? = null
        var cek: ByteArray? = null
        var nonce: ByteArray? = null
        var opened: ByteArray? = null
        return try {
            val salt = body.copyOfRange(0, 16)
            val rs = u32(body, 16)
            val idLen = body[20].toInt() and 0xFF
            if (idLen != PUBLIC_KEY_BYTES || rs < 18 || rs > MAX_RECORD_SIZE) return null
            val header = HEADER_FIXED + idLen
            if (body.size < header + 17) return null
            val asPublic = body.copyOfRange(21, header)
            val record = body.copyOfRange(header, body.size)
            if (record.size > rs) return null
            secret = P256.sharedSecret(uaPrivate, asPublic) ?: return null
            if (secret.all { it == 0.toByte() }) return null
            val prefix = "WebPush: info\u0000".toByteArray(Charsets.US_ASCII)
            val info = ByteArray(prefix.size + uaPublic.size + asPublic.size)
            prefix.copyInto(info)
            uaPublic.copyInto(info, prefix.size)
            asPublic.copyInto(info, prefix.size + uaPublic.size)
            ikm = Primitives.hkdf(secret, auth, info, 32)
            cek = Primitives.hkdf(ikm, salt, "Content-Encoding: aes128gcm\u0000".toByteArray(Charsets.US_ASCII), 16)
            nonce = Primitives.hkdf(ikm, salt, "Content-Encoding: nonce\u0000".toByteArray(Charsets.US_ASCII), 12)
            opened = Primitives.aesGcmOpen(cek, nonce + record)
            unpad(opened)
        } catch (_: Exception) {
            null
        } finally {
            secret?.fill(0)
            ikm?.fill(0)
            cek?.fill(0)
            nonce?.fill(0)
            opened?.fill(0)
        }
    }

    /** Last record: trailing zeros, then the delimiter `0x02`. Anything else is not ours. */
    private fun unpad(plain: ByteArray): ByteArray? {
        var i = plain.size - 1
        while (i >= 0 && plain[i] == 0.toByte()) i--
        if (i < 0 || plain[i] != 0x02.toByte()) return null
        return plain.copyOf(i)
    }

    private fun u32(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)
}

/** P-256 subscription keys. Drawn from BouncyCastle's DRBG (`SecureRandom` provider `BC`). */
internal object P256 {
    private val x9 = CustomNamedCurves.getByName("secp256r1")
    private val domain = x9?.let { ECDomainParameters(it.curve, it.g, it.n, it.h) }
    private val random: SecureRandom by lazy { SecureRandom.getInstance("DEFAULT", BouncyCastleProvider()) }

    fun sharedSecret(privateKey: ByteArray, peerPublic: ByteArray): ByteArray? {
        val curve = x9 ?: return null
        val params = domain ?: return null
        if (privateKey.size != 32 || peerPublic.size != 65 || peerPublic[0] != 0x04.toByte()) return null
        return try {
            val d = BigInteger(1, privateKey)
            if (d.signum() <= 0 || d >= curve.n) return null
            val point = curve.curve.decodePoint(peerPublic)
            if (point.isInfinity) return null
            val agreement = ECDHBasicAgreement()
            agreement.init(ECPrivateKeyParameters(d, params))
            fixed32(agreement.calculateAgreement(ECPublicKeyParameters(point, params)))
        } catch (_: Exception) {
            null
        }
    }

    fun generate(): Keys? {
        val params = domain ?: return null
        return try {
            val generator = ECKeyPairGenerator()
            generator.init(ECKeyGenerationParameters(params, random))
            val pair = generator.generateKeyPair()
            val priv = pair.private as ECPrivateKeyParameters
            val pub = pair.public as ECPublicKeyParameters
            val scalar = fixed32(priv.d)
            val encoded = pub.q.getEncoded(false)
            if (encoded.size != 65) return null
            Keys(scalar, encoded, random(16))
        } catch (_: Exception) {
            null
        }
    }

    fun token(): String = b64Url(random(16))

    fun random(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun b64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun fixed32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        if (raw.size == 32) return raw
        if (raw.size > 32) return raw.copyOfRange(raw.size - 32, raw.size)
        return ByteArray(32).also { raw.copyInto(it, 32 - raw.size) }
    }

    data class Keys(val privateKey: ByteArray, val publicKey: ByteArray, val auth: ByteArray)
}
