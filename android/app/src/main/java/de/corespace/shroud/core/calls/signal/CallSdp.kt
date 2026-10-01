package de.corespace.shroud.core.calls.signal

/**
 * Session-description munging — iOS `CallSdp` (`ios/shroud/Services/Calls/CallSdp.swift:9-188`),
 * identical to the web's `voiceSdp` / `screenSoundSdp` / `screenVideoSdp` / `sdpWithoutCandidates`
 * (`web/src/calls/logic.ts`), calls §2.5.
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
     * VP8 first on the screen's picture: the second video section's m-line lists the VP8 payload
     * types first, the rest after, each in their original order (`CallSdp.swift:92-113`). Fewer
     * than two video sections, no VP8 there, or an m-line with three parts or fewer: unchanged.
     */
    fun withScreenVideo(sdp: String): String {
        val eol = eol(sdp)
        val lines = splitLines(sdp).toMutableList()
        val section = secondSection(lines, "m=video ") ?: return lines.joinToString(eol)
        val vp8 = section.mapNotNull { index ->
            val line = lines[index]
            val lower = line.lowercase()
            if (!lower.startsWith("a=rtpmap:") || !lower.contains(" vp8/90000")) return@mapNotNull null
            line.substring("a=rtpmap:".length).takeWhile { it.isAsciiDigit() }.ifEmpty { null }
        }.toSet()
        if (vp8.isEmpty()) return lines.joinToString(eol)
        val from = section.first
        val parts = lines[from].split(" ")
        if (parts.size <= 3) return lines.joinToString(eol)
        val types = parts.drop(3)
        lines[from] = (parts.take(3) + types.filter { it in vp8 } + types.filterNot { it in vp8 }).joinToString(" ")
        return lines.joinToString(eol)
    }

    /** The full description as both sides make it before `setLocalDescription` (ME:392, 413; calls §2.5). */
    fun tuned(sdp: String): String = withScreenVideo(withScreenSound(withVoiceResilience(sdp)))

    private fun eol(sdp: String) = if (sdp.contains("\r\n")) "\r\n" else "\n"

    private fun splitLines(sdp: String): List<String> = sdp.replace("\r\n", "\n").split("\n")

    /** Indices of the second section whose m-line starts with [prefix] (case-insensitive), up to the next `m=`. */
    private fun secondSection(lines: List<String>, prefix: String): IntRange? {
        val starts = lines.indices.filter { lines[it].lowercase().startsWith(prefix) }
        if (starts.size < 2) return null
        val from = starts[1]
        val to = (from + 1 until lines.size).firstOrNull { lines[it].lowercase().startsWith("m=") } ?: lines.size
        return from until to
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
}
