package de.corespace.shroud.ui.settings

import android.os.LocaleList
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.transcription.TranscriptionInstallState
import de.corespace.shroud.core.transcription.VoiceTranscription
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.InsetDivider
import de.corespace.shroud.ui.components.PushedScreen
import de.corespace.shroud.ui.components.SettingsCard
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.Spinner
import de.corespace.shroud.ui.components.ToggleRow
import de.corespace.shroud.ui.components.ToggleRowSpacing
import de.corespace.shroud.ui.components.highlightRow
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import java.text.Collator
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Settings › Transcription (iOS `TranscriptionLanguageView`, `ios/shroud/Features/Main/TranscriptionLanguageView.swift:9-187`;
 * settings-lock §9; design `Transcription` `uG2Sv`): pins the language Whisper transcribes voice
 * messages in, or leaves it on Automatic. It never starts a model download; while the first
 * download runs (started by a transcription) it shows its progress.
 *
 * Reads and writes the `VoiceTranscription` seam (`TranscriptionModule.voice`, W3-TRANSCRIPTION):
 * [VoiceTranscription.availableLocales][de.corespace.shroud.core.transcription.VoiceTranscription.availableLocales]
 * (Whisper's languages, the device's own first), `languageOverride` (prefs `transcription.locale`,
 * wiped at Log Out) and `install`.
 */
@Composable
fun TranscriptionScreen(onBack: () -> Unit) = TranscriptionScreen(LocalAppContainer.current.transcription.voice, onBack)

/** [TranscriptionScreen] on a given [voice] (K7); [deviceLanguageTags] are the phone's languages, most preferred first. */
@Composable
internal fun TranscriptionScreen(
    voice: VoiceTranscription,
    onBack: () -> Unit,
    deviceLanguageTags: () -> List<String> = TranscriptionPicker::deviceLanguageTags,
) {
    val install by voice.install.collectAsState()
    // Read once on appear, as iOS does in `onAppear` (`:40-43`).
    val available = remember(voice) { TranscriptionPicker.order(voice.availableLocales(), deviceLanguageTags()) }
    var selection by remember(voice) { mutableStateOf(voice.languageOverride) }
    var automatic by remember(voice) { mutableStateOf(voice.transcribesAutomatically) }
    TranscriptionContent(
        install = install,
        languages = available,
        selection = selection,
        onChoose = { locale ->
            selection = locale
            // No haptic: the row press already ticks (`:180-186`).
            voice.languageOverride = locale
        },
        onBack = onBack,
        automatic = automatic,
        onAutomatic = { on ->
            automatic = on
            voice.transcribesAutomatically = on
        },
    )
}

/** The picker's order, names and copy (`TranscriptionLanguageView.swift:46-100`). Pure. */
object TranscriptionPicker {
    const val TITLE = "Transcription"
    const val HEADER =
        "Voice messages are transcribed on this device with Whisper. Audio never leaves it. The model downloads once, " +
            "the first time you transcribe, then works for every language."
    const val AUTOMATIC = "Automatic"
    const val AUTOMATIC_SUBTITLE = "Detects the spoken language. Remembers it per chat when Whisper is unsure."
    const val AUTO_TRANSCRIBE = "Transcribe automatically"
    const val AUTO_TRANSCRIBE_SUBTITLE =
        "Transcribes the voice messages you send, right after sending. When off, tap the transcript button next to " +
            "a voice message to transcribe it."

    /** A row's minimum height, its touch target (iOS draws 41 dp one-line rows). */
    val ROW_MIN_HEIGHT: Dp = 48.dp

    /**
     * `pickerOrder` (`TranscriptionLanguageView.swift:46-59`): the leading run of [all] whose
     * languages the device prefers ([preferredTags], `LocaleList` order) stays as listed — `all`
     * already lists them first — then the rest A–Z by their shown name with a locale-aware
     * collator (iOS `localizedStandardCompare`). Only the picker is sorted; the transcriber reads
     * `all` in its own order.
     */
    fun order(all: List<Locale>, preferredTags: List<String>, displayLocale: Locale = Locale.getDefault()): List<Locale> {
        val preferred = preferredTags.mapNotNull { tag -> Locale.forLanguageTag(tag).language.takeIf { it.isNotEmpty() } }.toSet()
        val head = all.takeWhile { it.language in preferred }
        val collator = Collator.getInstance(displayLocale)
        val rest = all.drop(head.size).sortedWith { a, b -> collator.compare(displayName(a, displayLocale), displayName(b, displayLocale)) }
        return head + rest
    }

    /** The language's name in the UI language ("German" in an English UI; `TranscriptionLanguage.displayName`). */
    fun displayName(locale: Locale, displayLocale: Locale = Locale.getDefault()): String =
        locale.getDisplayName(displayLocale).ifEmpty { locale.toLanguageTag() }

    /**
     * Whether [row] is the chosen language: by language code, not the full tag — overrides saved
     * before Whisper carried a region ("de-DE") while the list is plain languages
     * (`TranscriptionLanguageView.swift:128-130`).
     */
    fun isSelected(row: Locale, selection: Locale?): Boolean = selection != null && selection.language == row.language

    /**
     * The download card's title (`:94-100`): "Downloading Whisper… n%" while determinate and
     * past 0 % (n rounded), else "Downloading Whisper…".
     */
    fun downloadTitle(state: TranscriptionInstallState): String {
        val percent = (state.fractionCompleted * 100).roundToInt()
        return if (state.isDeterminate && percent > 0) "Downloading Whisper… $percent%" else "Downloading Whisper…"
    }

    /** The determinate bar's value, never quite empty (`:79`). */
    fun barFraction(state: TranscriptionInstallState): Float = max(state.fractionCompleted, 0.02).toFloat().coerceAtMost(1f)

    /** The device's languages, most preferred first (iOS `Locale.preferredLanguages`). */
    fun deviceLanguageTags(): List<String> {
        val list = LocaleList.getDefault()
        return (0 until list.size()).map { list[it].toLanguageTag() }
    }
}

/**
 * The Transcription screen's drawing (`TranscriptionLanguageView.swift:16-44`): header, the
 * Transcribe automatically switch ([automatic], off by default), the download card while the
 * model downloads, the Automatic card and one card of [languages].
 */
@Composable
fun TranscriptionContent(
    install: TranscriptionInstallState,
    languages: List<Locale>,
    selection: Locale?,
    onChoose: (Locale?) -> Unit,
    onBack: () -> Unit,
    automatic: Boolean = false,
    onAutomatic: (Boolean) -> Unit = {},
) {
    val colors = ShroudTheme.colors
    PushedScreen(TranscriptionPicker.TITLE, onBack) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ShroudText(
                TranscriptionPicker.HEADER,
                inter(14f),
                colors.textSecondary,
                Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
            SettingsCard {
                ToggleRow(
                    title = TranscriptionPicker.AUTO_TRANSCRIBE,
                    subtitle = TranscriptionPicker.AUTO_TRANSCRIBE_SUBTITLE,
                    checked = automatic,
                    spacing = ToggleRowSpacing.Privacy,
                    onCheckedChange = onAutomatic,
                )
            }
            if (install.phase == TranscriptionInstallState.Phase.Downloading) DownloadCard(install)
            SettingsCard {
                LanguageRow(TranscriptionPicker.AUTOMATIC, TranscriptionPicker.AUTOMATIC_SUBTITLE, selected = selection == null) { onChoose(null) }
            }
            if (languages.isNotEmpty()) {
                SettingsCard {
                    languages.forEachIndexed { index, locale ->
                        if (index > 0) InsetDivider(14.dp)
                        LanguageRow(
                            TranscriptionPicker.displayName(locale),
                            subtitle = null,
                            selected = TranscriptionPicker.isSelected(locale, selection),
                        ) { onChoose(locale) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The first model download's progress (`TranscriptionLanguageView.swift:73-92`): padding 14, the
 * title, then a 4 dp `accent` bar on a `separator` track while determinate, else a small spinner.
 */
@Composable
private fun DownloadCard(install: TranscriptionInstallState) {
    val colors = ShroudTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.background)
            .semantics(mergeDescendants = true) {}
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ShroudText(TranscriptionPicker.downloadTitle(install), inter(15f, FontWeight.SemiBold), colors.textPrimary)
        if (install.isDeterminate) {
            val fraction = TranscriptionPicker.barFraction(install)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.separator),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.accent),
                )
            }
        } else {
            Spinner(colors.accent, size = 18.dp)
        }
    }
}

/**
 * A language row (`TranscriptionLanguageView.swift:140-178`): title (and subtitle), the check on
 * the selected one, padding h 14 v 11. TalkBack: the title, then the subtitle (iOS's hint).
 *
 * At least [TranscriptionPicker.ROW_MIN_HEIGHT] tall: a one-line row is 41 dp on iOS and in the
 * design (`uG2Sv`), but the rows touch each other, so Compose cannot widen their hit areas the
 * way it does for a lone small control; 48 dp keeps every language a full touch target (00-plan
 * §2.0 rule 8, as `MenuPicker` does with its 44 dp iOS rows). The text stays centred.
 */
@Composable
private fun LanguageRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    val colors = ShroudTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = TranscriptionPicker.ROW_MIN_HEIGHT)
            .highlightRow(onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ShroudText(title, inter(16f), colors.textPrimary)
            if (subtitle != null) ShroudText(subtitle, inter(13f), colors.textSecondary)
        }
        SelectionCheck(selected)
    }
}

@Preview(name = "Transcription · 412", widthDp = 412, heightDp = 800)
@Composable
private fun TranscriptionPreview() {
    ShroudTheme(dark = false) {
        TranscriptionContent(
            install = TranscriptionInstallState(TranscriptionInstallState.Phase.Downloading, 0.42, isDeterminate = true, languageName = null, messageId = null),
            languages = listOf("en", "de", "ar", "ca", "zh", "hr").map(Locale::forLanguageTag),
            selection = Locale.forLanguageTag("de-DE"),
            onChoose = {},
            onBack = {},
        )
    }
}

@Preview(name = "Transcription · 360 · dark", widthDp = 360, heightDp = 700)
@Composable
private fun TranscriptionDarkPreview() {
    ShroudTheme(dark = true) {
        TranscriptionContent(TranscriptionInstallState.Idle, listOf("en", "fr").map(Locale::forLanguageTag), selection = null, onChoose = {}, onBack = {})
    }
}
