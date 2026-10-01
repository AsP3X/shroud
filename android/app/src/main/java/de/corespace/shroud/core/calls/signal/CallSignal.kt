package de.corespace.shroud.core.calls.signal

import de.corespace.shroud.core.calls.IceCandidatePayload
import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.net.CallSignalType
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What one sealed signal says — iOS `CallSignal` (`ios/shroud/Services/Calls/CallSignal.swift:30-172`),
 * `docs/calls.md` "Plaintext", calls §2.4. `n` counts from 1 per sending device across every
 * signal type; it is not part of the value (see [Parsed]).
 */
sealed interface CallSignal {
    /** The `signal_type` the server sees and the sealed additional data binds (`CallSignal.swift:48-56`). */
    val signalType: String

    /** [ephemeral] is the sender's fresh X25519 public key (32 bytes), on the first offer only. */
    data class Offer(val sdp: String, val restart: Boolean, val ephemeral: Bytes? = null) : CallSignal {
        override val signalType: String get() = CallSignalType.SDP_OFFER
    }

    /** [ephemeral] is the sender's fresh X25519 public key, on the first answer only. */
    data class Answer(val sdp: String, val ephemeral: Bytes? = null) : CallSignal {
        override val signalType: String get() = CallSignalType.SDP_ANSWER
    }

    data class Candidates(val candidates: List<IceCandidatePayload>) : CallSignal {
        override val signalType: String get() = CallSignalType.ICE_CANDIDATE
    }

    /** The callee asks the caller for an ICE restart. */
    data object RestartRequest : CallSignal {
        override val signalType: String get() = CallSignalType.RENEGOTIATE
    }

    /**
     * What the sender sends now: the other side shows a muted mark or the avatar. [screen] is
     * whether it shares its screen; null from an app that cannot share or show one.
     */
    data class Media(val mic: Boolean, val camera: Boolean, val screen: Boolean? = null) : CallSignal {
        override val signalType: String get() = CallSignalType.MEDIA_STATE
    }

    /** A parsed plaintext and its number. */
    data class Parsed(val signal: CallSignal, val n: Int)

    /** `CallSignal.ParseError` (`CallSignal.swift:42-45`). */
    sealed class ParseException(message: String) : Exception(message, null, false, false) {
        /** Not an object, no `t`, a bad `n`, a missing or mistyped field, an unknown `t`. */
        object Malformed : ParseException("malformed call signal")

        /** The body says another type than the `signal_type` it arrived under (a relabel). */
        object TypeMismatch : ParseException("call signal type mismatch")
    }

    companion object {
        private val json = Json

        /**
         * The plaintext JSON, numbered [n] (`CallSignal.swift:69-96`). Keys in sorted order as iOS
         * writes them (`.sortedKeys`; receivers do not care). `ek` only when present, `screen`
         * only when known, `sdpMid` only when set; `sdpMLineIndex` always (0 when unknown).
         */
        fun plaintext(signal: CallSignal, n: Int): ByteArray {
            val body = buildJsonObject {
                when (signal) {
                    is Offer -> {
                        signal.ephemeral?.let { put("ek", B64.encode(it.toByteArray())) }
                        put("n", n)
                        put("restart", signal.restart)
                        put("sdp", signal.sdp)
                        put("t", "offer")
                    }
                    is Answer -> {
                        signal.ephemeral?.let { put("ek", B64.encode(it.toByteArray())) }
                        put("n", n)
                        put("sdp", signal.sdp)
                        put("t", "answer")
                    }
                    is Candidates -> {
                        put(
                            "cs",
                            buildJsonArray {
                                for (candidate in signal.candidates) {
                                    add(
                                        buildJsonObject {
                                            put("candidate", candidate.candidate)
                                            put("sdpMLineIndex", candidate.sdpMLineIndex ?: 0)
                                            candidate.sdpMid?.let { put("sdpMid", it) }
                                        },
                                    )
                                }
                            },
                        )
                        put("n", n)
                        put("t", "ice")
                    }
                    RestartRequest -> {
                        put("n", n)
                        put("t", "restart")
                    }
                    is Media -> {
                        put("camera", signal.camera)
                        put("mic", signal.mic)
                        put("n", n)
                        signal.screen?.let { put("screen", it) }
                        put("t", "media")
                    }
                }
            }
            return body.toString().toByteArray(Charsets.UTF_8)
        }

        /**
         * Reads a plaintext that arrived under [signalType]; the two must agree
         * (`CallSignal.swift:99-144`, calls §2.4):
         * - not an object, no string `t`, or `n` not a number > 0 → [ParseException.Malformed];
         * - `offer`/`answer`: `sdp` a string (empty allowed, as iOS); `restart` a bool, absent false;
         *   `ek` absent → null (older peer), present but not 32 bytes of padded base64 → malformed;
         * - `ice`: `cs` an array of objects; entries without a non-empty `candidate` dropped;
         *   `sdpMid` a string or null; `sdpMLineIndex` a number, absent 0;
         * - `media`: `mic` default true, `camera` default false; `screen` absent → null, present and
         *   not a bool → malformed;
         * - an unknown `t` → malformed; a `t` of another type than [signalType] → [ParseException.TypeMismatch].
         *
         * Booleans and numbers read as iOS's `NSNumber` does (`CallSignal.swift:147-171`): a number
         * counts as a bool (non-zero is true) and a fractional `n` is cut to its integer part;
         * strings never count. A boolean is no number here (the web refuses it too).
         */
        fun parse(data: ByteArray, signalType: String): Parsed {
            val obj = try {
                json.parseToJsonElement(String(data, Charsets.UTF_8)) as? JsonObject
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: throw ParseException.Malformed
            val tag = obj["t"].stringValue() ?: throw ParseException.Malformed
            val n = obj["n"].intValue()?.takeIf { it > 0 } ?: throw ParseException.Malformed

            val signal: CallSignal = when (tag) {
                "offer" -> Offer(
                    sdp = obj["sdp"].stringValue() ?: throw ParseException.Malformed,
                    restart = obj["restart"].boolValue() ?: false,
                    ephemeral = ephemeral(obj),
                )
                "answer" -> Answer(
                    sdp = obj["sdp"].stringValue() ?: throw ParseException.Malformed,
                    ephemeral = ephemeral(obj),
                )
                "ice" -> {
                    val list = obj["cs"] as? JsonArray ?: throw ParseException.Malformed
                    // iOS casts the whole list to `[[String: Any]]`: one entry that is no object fails it.
                    val entries = list.map { it as? JsonObject ?: throw ParseException.Malformed }
                    Candidates(
                        entries.mapNotNull { entry ->
                            val candidate = entry["candidate"].stringValue()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                            IceCandidatePayload(
                                candidate = candidate,
                                sdpMid = entry["sdpMid"].stringValue(),
                                sdpMLineIndex = entry["sdpMLineIndex"].intValue() ?: 0,
                            )
                        },
                    )
                }
                "restart" -> RestartRequest
                "media" -> {
                    // Absent from an app that knows no screens; anything but a boolean is malformed.
                    val screenValue = obj["screen"]
                    val screen = screenValue.boolValue()
                    if (screenValue != null && screen == null) throw ParseException.Malformed
                    Media(
                        mic = obj["mic"].boolValue() ?: true,
                        camera = obj["camera"].boolValue() ?: false,
                        screen = screen,
                    )
                }
                else -> throw ParseException.Malformed
            }
            if (signal.signalType != signalType) throw ParseException.TypeMismatch
            return Parsed(signal, n)
        }

        /** Absent is an older peer; present but not 32 bytes of padded base64 rejects the signal (`CallSignal.swift:155-163`). */
        private fun ephemeral(obj: JsonObject): Bytes? {
            val value = obj["ek"] ?: return null
            val text = value.stringValue() ?: throw ParseException.Malformed
            val bytes = B64.decodeStrict(text)?.takeIf { it.size == 32 } ?: throw ParseException.Malformed
            return Bytes.adopt(bytes)
        }

        private fun JsonElement?.stringValue(): String? {
            val primitive = this as? JsonPrimitive ?: return null
            if (primitive is JsonNull || !primitive.isString) return null
            return primitive.content
        }

        /** `CallSignal.int` (`:147-153`): a JSON number; a fractional one is cut like `NSNumber.intValue`. */
        private fun JsonElement?.intValue(): Int? {
            val primitive = this as? JsonPrimitive ?: return null
            if (primitive is JsonNull || primitive.isString || primitive.booleanOrNull != null) return null
            primitive.content.toIntOrNull()?.let { return it }
            val number = primitive.content.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
            return number.toInt()
        }

        /** `CallSignal.bool` (`:165-171`): a JSON bool, or a number (non-zero is true). */
        private fun JsonElement?.boolValue(): Boolean? {
            val primitive = this as? JsonPrimitive ?: return null
            if (primitive is JsonNull || primitive.isString) return null
            primitive.booleanOrNull?.let { return it }
            return primitive.content.toDoubleOrNull()?.let { it != 0.0 }
        }
    }
}

/** Drops signals seen before: with Redis the server may deliver one twice (`CallSignalSequencer`, `CallSignal.swift:175-182`). */
class CallSignalSequencer {
    private val seen = HashSet<Int>()

    /** True the first time [n] arrives. */
    fun accept(n: Int): Boolean = seen.add(n)
}

/**
 * Media states apply newest first (`CallMediaOrder`, `CallSignal.swift:187-197`). The server hands
 * back the other device's latest one (after a reconnect, on the heartbeat), and that answer can
 * arrive after a newer one came over the socket: the older one must not undo it.
 */
class CallMediaOrder {
    private val latest = HashMap<String, Int>()

    /** True when a media state numbered [n] is newer than the last one taken from [device] (any case). */
    fun isNewer(n: Int, device: String): Boolean {
        val key = device.lowercase()
        if (n <= (latest[key] ?: 0)) return false
        latest[key] = n
        return true
    }
}
