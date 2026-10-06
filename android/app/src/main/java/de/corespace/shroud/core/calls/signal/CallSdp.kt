package de.corespace.shroud.core.calls.signal

/**
 * Session-description munging — iOS `CallSdp` (`ios/shroud/Services/Calls/CallSdp.swift:9-188`),
 * identical to the web's `voiceSdp` / `screenSoundSdp` / `screenVideoSdp` / `cameraVideoSdp` /
 * `sdpWithoutCandidates` (`web/src/calls/logic.ts`), calls §2.5.
 *
 * Every function keeps the input's line ending (`\r\n` when it occurs anywhere, else `\n`), splits
 * on `\r\n`/`\n` keeping empty lines, and joins back. A parameter is replaced only when it is its
 * own key, so `stereo` never rewrites `sprop-stereo`.
 */
object CallSdp {
    /** Opus for speech: error correction, silence not sent, mono, ~32 kbps (`CallSdp.swift:10-16`). */
    private val VOICE_EXTRAS = listOf("useinbandfec=1", "usedtx=1", "stereo=0", "sprop-stereo=0", "maxaveragebitrate=32000")

    /** Opus for a shared screen's sound: stereo, never silenced, ~128 kbps (`CallSdp.swift:115-121`). */
    private val SCREEN_SOUND_EXTRAS = listOf("useinbandfec=1", "usedtx=0", "stereo=1", "sprop-stereo=1", "maxaveragebitrate=128000")

    /** ICE addresses leave the description; they travel later, under the per-call key (`CallSdp.swift:19-25`). */
    fun withoutCandidates(sdp: String): String {
        val eol = eol(sdp)
        return sdp.split(eol).filterNot { it.lowercase().startsWith("a=candidate:") }.joinToString(eol)
    }

    /** The SHA-256 DTLS fingerprint, lower case, or null when the description has none (`CallSdp.swift:28-38`). */
    fun fingerprint(sdp: String): String? {
        val prefix = "a=fingerprint:sha-256"
        for (line in splitLines(sdp)) {
            val text = line.trim(' ', '\t')
            if (!text.lowercase().startsWith(prefix)) continue
            val value = text.substring(prefix.length).trim(' ', '\t')
            if (value.isEmpty()) continue
            return value.lowercase()
        }
        return null
    }

    /** The certificate fingerprint and the sealed one name the same certificate (`CallSdp.swift:41-49`). */
    fun matches(sdpPrint: String, certPrint: String): Boolean {
        val left = normalize(sdpPrint)
        return left.isNotEmpty() && left == normalize(certPrint)
    }

    private fun normalize(value: String): String = value.filter { !it.isWhitespace() && it != ':' }.lowercase()

    /** The first Opus payload's fmtp gets [VOICE_EXTRAS], or one is inserted after its rtpmap (`CallSdp.swift:51-63`). */
    fun withVoiceResilience(sdp: String): String {
        val eol = eol(sdp)
        val lines = splitLines(sdp).toMutableList()
        val payload = lines.firstNotNullOfOrNull(::opusPayload) ?: return lines.joinToString(eol)
        val fmtp = lines.indexOfFirst { isFmtp(it, payload) }
        if (fmtp >= 0) {
            lines[fmtp] = applying(VOICE_EXTRAS, lines[fmtp])
        } else {
            val map = lines.indexOfFirst { isRtpmap(it, payload) }
            if (map >= 0) lines.add(map + 1, "a=fmtp:$payload ${VOICE_EXTRAS.joinToString(";")}")
        }
        return lines.joinToString(eol)
    }

    /**
     * Opus for a shared screen's sound: the second audio section (the first is the microphone)
     * gets [SCREEN_SOUND_EXTRAS] (`CallSdp.swift:68-85`). Fewer than two audio sections: unchanged.
     */
    fun withScreenSound(sdp: String): String {
        val eol = eol(sdp)
        val lines = splitLines(sdp).toMutableList()
        val section = secondSection(lines, "m=audio ") ?: return lines.joinToString(eol)
        val payload = section.firstNotNullOfOrNull { opusPayload(lines[it]) } ?: return lines.joinToString(eol)
        val fmtp = section.firstOrNull { isFmtp(lines[it], payload) }
        if (fmtp != null) {
            lines[fmtp] = applying(SCREEN_SOUND_EXTRAS, lines[fmtp])
        } else {
            val map = section.firstOrNull { isRtpmap(lines[it], payload) }
            if (map != null) lines.add(map + 1, "a=fmtp:$payload ${SCREEN_SOUND_EXTRAS.joinToString(";")}")
        }
        return lines.joinToString(eol)
    }

    /**
     * H.264 first on the camera's picture: the first video section's m-line lists the H.264
     * payload types first (its profiles in their own order), the rest after, each in their
     * original order (web `cameraVideoSdp`, docs/calls.md "Camera quality"). Phones and Macs
     * encode it in hardware; VP8, which a browser lists first, is encoded in software there and
     * runs out of processor at the sizes the camera goes out at. Both sides put it first in their
     * own descriptions, so it is what either one sends whoever offers. Each H.264 entry there
     * declares at least level 4.0 ([h264Level]), so 1080p goes out at 30 fps. No video section, or
     * no H.264 there: unchanged; an m-line with three parts or fewer keeps its order.
     */
    fun withCameraVideo(sdp: String): String {
        val ordered = codecFirst(sdp, place = 0, codec = "h264")
        val eol = eol(ordered)
        val lines = splitLines(ordered).toMutableList()
        val section = nthSection(lines, "m=video ", 0) ?: return ordered
        val h264 = section.mapNotNull { payloadOf(lines[it], "h264") }.toSet()
        for (index in section.first + 1..section.last) {
            val pt = FMTP.find(lines[index])?.groupValues?.get(1) ?: continue
            if (pt !in h264) continue
            val level = PROFILE_LEVEL_ID.find(lines[index]) ?: continue
            val value = level.groups[3] ?: continue
            lines[index] = lines[index].replaceRange(value.range, h264Level(value.value))
        }
        return lines.joinToString(eol)
    }

    /** H.264 level 4.0 (`level_idc` 0x28): 1080p at 30 fps. */
    private const val H264_LEVEL_1080P = 0x28

    /** `a=fmtp:<pt>` followed by a space or a tab (web `cameraVideoSdp`). */
    private val FMTP = Regex("^a=fmtp:(\\d+)[ \t]", RegexOption.IGNORE_CASE)

    /** The first `profile-level-id=` that is its own parameter, with its hex value. */
    private val PROFILE_LEVEL_ID = Regex("(^|[ \t;])(profile-level-id=)([0-9a-f]+)", RegexOption.IGNORE_CASE)

    /**
     * A `profile-level-id` (six hex digits: profile, constraints, level) raised to at least level
     * 4.0; anything else is unchanged (web `h264Level`, iOS `CallSdp.h264Level`). A sender may
     * hold its frame rate to the level the receiver declares (the iPhone's H.264 encoder does),
     * and browsers declare 3.1, which carries 1080p at only about 13 fps. Every client here
     * decodes 1080p30, so declaring 4.0 is true; it says what this side can receive and changes
     * nothing about what it sends.
     */
    fun h264Level(profileLevelId: String): String {
        if (profileLevelId.length != 6 || !profileLevelId.all { it.isHexDigit() }) return profileLevelId
        val level = profileLevelId.substring(4).toInt(16)
        return if (level >= H264_LEVEL_1080P) profileLevelId else profileLevelId.substring(0, 4) + H264_LEVEL_1080P.toString(16)
    }

    /**
     * VP8 first on the screen's picture: the second video section's m-line lists the VP8 payload
     * types first, the rest after, each in their original order (`CallSdp.swift:92-113`). Fewer
     * than two video sections, no VP8 there, or an m-line with three parts or fewer: unchanged.
     */
    fun withScreenVideo(sdp: String): String = codecFirst(sdp, place = 1, codec = "vp8")

    /**
     * [codec]'s payload types first in the [place]-th video section (0 the camera's, 1 the
     * screen's), keeping the order of everything else (web `codecFirst`). [codec] is lower case.
     */
    private fun codecFirst(sdp: String, place: Int, codec: String): String {
        val eol = eol(sdp)
        val lines = splitLines(sdp).toMutableList()
        val section = nthSection(lines, "m=video ", place) ?: return lines.joinToString(eol)
        val wanted = section.mapNotNull { payloadOf(lines[it], codec) }.toSet()
        if (wanted.isEmpty()) return lines.joinToString(eol)
        val from = section.first
        val parts = lines[from].split(" ")
        if (parts.size <= 3) return lines.joinToString(eol)
        val types = parts.drop(3)
        lines[from] = (parts.take(3) + types.filter { it in wanted } + types.filterNot { it in wanted }).joinToString(" ")
        return lines.joinToString(eol)
    }

    /**
     * The full description as both sides make it before `setLocalDescription`, offer and answer
     * (ME:392, 413; calls §2.5).
     */
    fun tuned(sdp: String): String = withCameraVideo(withScreenVideo(withScreenSound(withVoiceResilience(sdp))))

    private fun eol(sdp: String) = if (sdp.contains("\r\n")) "\r\n" else "\n"

    private fun splitLines(sdp: String): List<String> = sdp.replace("\r\n", "\n").split("\n")

    /** Indices of the second section whose m-line starts with [prefix] (case-insensitive), up to the next `m=`. */
    private fun secondSection(lines: List<String>, prefix: String): IntRange? = nthSection(lines, prefix, 1)

    /** Indices of the [place]-th (from 0) section whose m-line starts with [prefix], up to the next `m=`. */
    private fun nthSection(lines: List<String>, prefix: String, place: Int): IntRange? {
        val starts = lines.indices.filter { lines[it].lowercase().startsWith(prefix) }
        if (starts.size <= place) return null
        val from = starts[place]
        val to = (from + 1 until lines.size).firstOrNull { lines[it].lowercase().startsWith("m=") } ?: lines.size
        return from until to
    }

    /** The payload type of an `a=rtpmap:<pt> <codec>/90000` line; [codec] is lower case. */
    private fun payloadOf(line: String, codec: String): String? {
        val lower = line.lowercase()
        if (!lower.startsWith("a=rtpmap:") || !lower.contains(" $codec/90000")) return null
        return line.substring("a=rtpmap:".length).takeWhile { it.isAsciiDigit() }.ifEmpty { null }
    }

    private fun opusPayload(line: String): String? {
        val lower = line.lowercase()
        if (!lower.startsWith("a=rtpmap:") || !lower.contains(" opus/48000")) return null
        return line.substring("a=rtpmap:".length).takeWhile { it.isAsciiDigit() }.ifEmpty { null }
    }

    /** `a=rtpmap:<pt>` followed by a space or a tab (`CallSdp.swift:137-140`). */
    private fun isRtpmap(line: String, payload: String): Boolean {
        val lower = line.lowercase()
        return lower.startsWith("a=rtpmap:$payload ") || lower.startsWith("a=rtpmap:$payload\t")
    }

    /** `a=fmtp:<pt>` followed by the end, a space, `;` or a tab (`CallSdp.swift:142-148`). */
    private fun isFmtp(line: String, payload: String): Boolean {
        val prefix = "a=fmtp:$payload"
        if (!line.lowercase().startsWith(prefix.lowercase())) return false
        if (line.length == prefix.length) return true
        val next = line[prefix.length]
        return next == ' ' || next == ';' || next == '\t'
    }

    /** Each `key=value` replaces its own parameter, or is appended with `;` (`CallSdp.swift:150-161`). */
    private fun applying(extras: List<String>, line: String): String = extras.fold(line) { partial, extra ->
        val eq = extra.indexOf('=')
        if (eq < 0) return@fold partial
        val range = parameterRange(extra.substring(0, eq), partial)
        if (range != null) partial.replaceRange(range, extra) else "$partial;$extra"
    }

    /** The `key=value` span when `key` is its own fmtp parameter, not a suffix of another (`CallSdp.swift:164-187`). */
    private fun parameterRange(key: String, line: String): IntRange? {
        val token = "$key="
        var search = 0
        while (true) {
            val found = line.indexOf(token, search)
            if (found < 0) return null
            val atBoundary = found == 0 || line[found - 1].let { it == ';' || it == ' ' || it == '\t' }
            if (atBoundary) {
                var end = found + token.length
                while (end < line.length && line[end] != ';' && line[end] != ' ' && line[end] != '\t') end++
                return found until end
            }
            search = found + token.length
        }
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
