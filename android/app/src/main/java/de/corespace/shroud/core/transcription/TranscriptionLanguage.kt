package de.corespace.shroud.core.transcription

import android.content.SharedPreferences
import android.os.LocaleList
import androidx.core.content.edit
import de.corespace.shroud.core.storage.StorageSeal
import java.util.Locale

/**
 * Which language a voice note is decoded in (iOS `TranscriptionLanguage`,
 * `TranscriptionLanguage.swift:14-201`). The Settings pin is prefs `transcription.locale`
 * (absent = automatic) in [de.corespace.shroud.core.storage.PrefsFiles.VOICE]. Log Out wipes
 * that key; [StorageSeal] drops the write while a wipe is running.
 *
 * Automatic means Whisper hears the language. The device's languages (UI and region, so `en-DE`
 * counts German) are the candidates its probabilities are weighed with ([SpokenLanguagePick]);
 * they never replace what the audio says. Nothing here leaves the device.
 */
class TranscriptionLanguage(
    private val prefs: SharedPreferences,
    private val seal: StorageSeal,
) {
    /** Test seam — replaces `LocaleList.getDefault()` when non-null (`preferredLanguageTagsOverride`). */
    var preferredLanguageTagsOverride: List<String>? = null

    /** Test seam — replaces [Locale.getDefault] when non-null. */
    var currentLocaleOverride: Locale? = null

    /**
     * The pinned language, or null for automatic. Stored as a BCP-47 tag. A sealed wipe drops
     * the write; the next read sees the key gone because the wipe cleared the file.
     */
    var override: Locale?
        get() {
            val id = prefs.getString(LOCALE_KEY, null)
            if (id.isNullOrEmpty()) return null
            return Locale.forLanguageTag(id.replace('_', '-'))
        }
        set(value) {
            if (seal.isSealed) return
            prefs.edit(commit = true) {
                if (value == null) remove(LOCALE_KEY) else putString(LOCALE_KEY, value.toLanguageTag())
            }
        }

    /**
     * Whether a voice note you send is transcribed on its own, right after it goes out (prefs
     * `transcription.automatic`, iOS `TranscriptionPreferences`). Off by default; Log Out wipes it.
     * A sealed wipe drops the write.
     */
    var transcribesAutomatically: Boolean
        get() = prefs.getBoolean(AUTOMATIC_KEY, false)
        set(value) {
            if (seal.isSealed) return
            prefs.edit(commit = true) { putBoolean(AUTOMATIC_KEY, value) }
        }

    /** Display name in the user's language, e.g. "German (Germany)". */
    fun displayName(locale: Locale): String {
        val name = locale.getDisplayName(Locale.getDefault())
        return name.ifBlank { locale.toLanguageTag() }
    }

    /**
     * Languages Whisper can transcribe, device languages first, then [WHISPER_CODES]
     * (`whisperLocales`, `TranscriptionLanguage.swift:47-56`). The picker, not extra downloads.
     */
    fun whisperLocales(): List<Locale> {
        val preferred = preferredLanguageTags().map { languageCode(Locale.forLanguageTag(it.replace('_', '-'))) }
        val ordered = LinkedHashSet<String>()
        for (code in preferred + WHISPER_CODES) {
            if (code in WHISPER_SET) ordered += code
        }
        return ordered.map { Locale.forLanguageTag(it) }
    }

    /** The pinned language as a Whisper code, or null for automatic (also for a pin Whisper can't use). */
    fun pinnedLanguage(): String? {
        val pinned = override ?: return null
        val code = normalize(languageCode(pinned))
        return code.takeIf { it in WHISPER_SET }
    }

    /**
     * The languages this device lives in, UI languages first, then the region's (`deviceLanguages`,
     * `TranscriptionLanguage.swift`). An English UI in Germany gives `en`, `de`.
     */
    fun deviceLanguages(): List<String> {
        val ordered = LinkedHashSet<String>()
        fun add(raw: String) {
            val code = normalize(raw)
            if (code in WHISPER_SET) ordered += code
        }
        for (tag in preferredLanguageTags()) add(languageCode(Locale.forLanguageTag(tag.replace('_', '-'))))
        for (code in regionLanguageHints()) add(code)
        return ordered.toList()
    }

    /** Spoken language implied by region, so `en-DE` also counts German (`regionLanguageHints`). */
    fun regionLanguageHints(): List<String> {
        val tags = preferredLanguageTags().toMutableList()
        tags += (currentLocaleOverride ?: Locale.getDefault()).toLanguageTag()
        val codes = LinkedHashSet<String>()
        for (tag in tags) {
            val region = Locale.forLanguageTag(tag.replace('_', '-')).country
            val language = languageForRegion(region) ?: continue
            codes += language
        }
        return codes.toList()
    }

    private fun preferredLanguageTags(): List<String> = preferredLanguageTagsOverride ?: devicePreferredTags()

    companion object {
        const val LOCALE_KEY = "transcription.locale"
        const val AUTOMATIC_KEY = "transcription.automatic"

        val WHISPER_CODES = listOf(
            "en", "de", "es", "fr", "it", "pt", "nl", "pl", "ru", "uk",
            "tr", "ar", "hi", "ja", "ko", "zh", "sv", "da", "nb", "fi",
            "cs", "el", "he", "id", "th", "vi", "ro", "hu", "ca", "hr",
        )
        val WHISPER_SET = WHISPER_CODES.toSet()

        private val LANGUAGE_NAMES = mapOf(
            "german" to "de", "english" to "en", "spanish" to "es", "french" to "fr",
            "italian" to "it", "portuguese" to "pt", "dutch" to "nl", "polish" to "pl",
            "russian" to "ru", "ukrainian" to "uk", "turkish" to "tr", "arabic" to "ar",
            "hindi" to "hi", "japanese" to "ja", "korean" to "ko", "chinese" to "zh",
            "swedish" to "sv", "danish" to "da", "norwegian" to "nb", "finnish" to "fi",
            "czech" to "cs", "greek" to "el", "hebrew" to "he", "indonesian" to "id",
            "thai" to "th", "vietnamese" to "vi", "romanian" to "ro", "hungarian" to "hu",
            "catalan" to "ca", "croatian" to "hr",
        )

        /** Non-English region → likely spoken language. English-speaking regions are omitted on purpose. */
        private val REGIONS = mapOf(
            "DE" to "de", "AT" to "de", "LI" to "de",
            "FR" to "fr", "MC" to "fr",
            "ES" to "es", "MX" to "es", "AR" to "es", "CO" to "es", "CL" to "es", "PE" to "es",
            "IT" to "it", "NL" to "nl", "PL" to "pl", "PT" to "pt", "BR" to "pt",
            "RU" to "ru", "UA" to "uk", "TR" to "tr", "JP" to "ja", "KR" to "ko",
            "CN" to "zh", "TW" to "zh", "SE" to "sv", "DK" to "da", "NO" to "nb", "FI" to "fi",
            "GR" to "el", "IL" to "he", "SA" to "ar", "AE" to "ar", "EG" to "ar",
            "TH" to "th", "VN" to "vi", "RO" to "ro", "HU" to "hu", "CZ" to "cs", "HR" to "hr",
        )

        /** ISO 639-1, or a Whisper language name (`german` → `de`). Unknown text is returned trimmed. */
        fun normalize(code: String): String {
            val raw = code.trim().lowercase()
            if (raw.isEmpty()) return ""
            if (raw.length == 2 && raw in WHISPER_SET) return raw
            LANGUAGE_NAMES[raw]?.let { if (it in WHISPER_SET) return it }
            val prefix = raw.take(2)
            return if (prefix in WHISPER_SET) prefix else raw
        }

        fun languageForRegion(region: String): String? = REGIONS[region.uppercase()]

        /**
         * Languages detection weighs fully ([SpokenLanguagePick]): the device's, and English, which
         * Whisper is best at and many people mix in (`detectionCandidates`). None, no preference.
         */
        fun detectionCandidates(languages: List<String>): List<String> =
            if (languages.isEmpty() || "en" in languages) languages else languages + "en"

        /** `iw` / `in` are the legacy JDK codes for Hebrew and Indonesian. */
        internal fun languageCode(locale: Locale): String = when (val code = locale.language.lowercase()) {
            "iw" -> "he"
            "in" -> "id"
            else -> code
        }

        private fun devicePreferredTags(): List<String> = try {
            val list = LocaleList.getDefault()
            if (list.isEmpty) listOf(Locale.getDefault().toLanguageTag())
            else List(list.size()) { list[it].toLanguageTag() }
        } catch (_: RuntimeException) {
            listOf(Locale.getDefault().toLanguageTag())
        }
    }
}
