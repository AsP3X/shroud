package de.corespace.shroud.ui.contacts

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.AddContactOutcome
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.components.GlassStyle
import de.corespace.shroud.ui.components.NoLearningTextInput
import de.corespace.shroud.ui.components.SheetStyle
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudSheet
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.glassSurface
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.permissions.rememberPermissionGranted
import de.corespace.shroud.ui.permissions.rememberPermissionRequest
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.mono
import de.corespace.shroud.ui.theme.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Add Contact (iOS `AddContactSheet`, `ios/shroud/Features/Main/AddContactSheet.swift:4-104`;
 * contacts §5.4, design `Add Contact` l14KDf): the floating inset sheet with Cancel · "Add Contact"
 * · Add, the "Scan QR code" button, the invite field and, after a failed try, the reason.
 *
 * Human: Paste a link, type a share code, a username or a user ID, or scan their code. Add sends
 * the request; the sheet closes and the Contacts tab says "Request sent to jane" (or "jane added"
 * when they had asked first). A failure stays in the sheet in red, with an error buzz, and TalkBack
 * reads it. A scanned code is filled in and sent at once (the scan is the user's own action); a
 * link that opened the app ([prefill]) only fills the field — it never sends by itself (P10c).
 *
 * Agent: [onAdded] gets the confirmation just before [onDismiss] (the presenter owns the toast,
 * `:8-10`). The request runs in the app scope, so it completes even if the sheet goes away
 * meanwhile. The field's text lives in memory only (no saved state).
 */
@Composable
fun AddContactSheet(visible: Boolean, prefill: String?, onDismiss: () -> Unit, onAdded: (String) -> Unit) {
    val ports = rememberContactsPorts()
    val contacts = ports.contacts
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    val form = remember { AddContactForm(prefill) }
    val currentOnAdded by rememberUpdatedState(onAdded)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    // The scanner's phase while it is up; null while the sheet shows alone.
    var scanner by remember { mutableStateOf<ScannerPhase?>(null) }
    val focus = remember { FocusRequester() }
    var focusRequests by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val hasCamera = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    val cameraGranted by rememberPermissionGranted(Manifest.permission.CAMERA)
    // The system dialog over the sheet (design w6957); its answer opens the scanner live or denied.
    val askCamera = rememberPermissionRequest(Manifest.permission.CAMERA) { granted, _ -> scanner = ScannerPhases.afterRequest(granted) }

    fun submit(raw: String) {
        scope.launch {
            // `submit(_:)` (`:82-103`).
            when (val result = form.submit(raw) { invite -> ports.actionScope.runDetached { contacts.add(invite) } }) {
                is AddContactForm.Submission.Failed -> haptic(Haptic.Error)
                is AddContactForm.Submission.Done -> {
                    haptic(Haptic.Success)
                    currentOnAdded(result.confirmation)
                    currentOnDismiss()
                }
                AddContactForm.Submission.Ignored -> Unit
            }
        }
    }

    ShroudSheet(visible = visible, onDismiss = onDismiss, style = SheetStyle.Inset, paneTitle = ContactsCopy.ADD_CONTACT_TITLE) {
        AddContactContent(
            form = form,
            focusRequester = focus,
            onCancel = onDismiss,
            onSubmit = { submit(form.text) },
            onScan = {
                when (val start = ScannerPhases.onScanTapped(hasCamera, cameraGranted)) {
                    ScannerPhases.Start.AskFirst -> askCamera()
                    is ScannerPhases.Start.Open -> scanner = start.phase
                }
            },
        )
    }

    // The full-screen scanner over the sheet (iOS `.fullScreenCover`, `:62-77`). A read code is
    // the user's own action, so it is sent at once (unlike an App Link's [prefill]).
    QrScannerOverlay(
        phase = scanner?.takeIf { visible },
        onPhaseChange = { scanner = it },
        onCode = { value ->
            scanner = null
            form.text = value
            submit(value)
        },
        onCancel = { focusField ->
            scanner = null
            if (focusField) focusRequests++
        },
    )

    // "Enter code instead" (design oGuCt): back in the sheet, the field takes the focus.
    LaunchedEffect(focusRequests) {
        if (focusRequests == 0) return@LaunchedEffect
        // One frame for the scanner's layer to go and the field to be focusable again.
        delay(FOCUS_DELAY_MS)
        runCatching { focus.requestFocus() }
    }
}

private const val FOCUS_DELAY_MS = 50L

/**
 * The state of one Add Contact sheet (`AddContactSheet.swift:12-15`): the field, the error shown
 * under it, whether a request is on the wire. Pure (no Android types), tested on the JVM.
 */
@Stable
internal class AddContactForm(prefill: String?) {
    var text by mutableStateOf(prefill.orEmpty())
    var error: String? by mutableStateOf(null)
        private set
    var isAdding by mutableStateOf(false)
        private set

    /** Add is live (`:59`). */
    val canSubmit: Boolean get() = ContactsCopy.canSubmit(text, isAdding)

    sealed interface Submission {
        /** Another request was still on the wire. */
        data object Ignored : Submission
        data class Failed(val message: String) : Submission

        /** Sent: the presenter shows [confirmation] and the sheet closes. */
        data class Done(val confirmation: String) : Submission
    }

    /**
     * Sends [raw] through [add] (`submit(_:)`, `:82-103`): clears the error, marks the request in
     * flight, and shows a failure's message. A second submit while one runs is ignored (a scan
     * landing during a typed Add).
     */
    suspend fun submit(raw: String, add: suspend (String) -> AddContactOutcome): Submission {
        if (isAdding) return Submission.Ignored
        isAdding = true
        error = null
        val outcome = try {
            add(raw)
        } finally {
            isAdding = false
        }
        return when (outcome) {
            is AddContactOutcome.Failed -> {
                error = outcome.message
                Submission.Failed(outcome.message)
            }
            else -> Submission.Done(checkNotNull(ContactsCopy.confirmation(outcome)))
        }
    }
}

/**
 * The sheet's content (design l14KDf): header row 44 dp, then the scan button with its hint, the
 * field with its hint, and the error card — 14 dp apart (the inset sheet's gap).
 */
@Composable
internal fun AddContactContent(
    form: AddContactForm,
    focusRequester: FocusRequester,
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
    onScan: () -> Unit,
) {
    val colors = ShroudTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    // Header: Cancel · "Add Contact" · Add / Adding… (`:49-61`).
    Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
        ShroudText(
            ContactsCopy.ADD_CONTACT_TITLE,
            inter(17f, FontWeight.SemiBold),
            colors.textPrimary,
            Modifier.semantics { heading() },
            maxLines = 1,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SheetCapsuleButton(ContactsCopy.CANCEL, colors.textPrimary, glass = true, onClick = onCancel)
            Box(Modifier.weight(1f))
            val enabled = form.canSubmit
            SheetCapsuleButton(
                ContactsCopy.addButtonTitle(form.isAdding),
                if (enabled) colors.accent else colors.textPrimary.copy(alpha = 0.25f),
                glass = false,
                enabled = enabled,
                onClick = {
                    keyboard?.hide()
                    onSubmit()
                },
            )
        }
    }

    // Scan (`:20-29`): design 48 dp capsule, `textPrimary` @ 6 %, viewfinder glyph 22 (Lucide `scan` for SF
    // `qrcode.viewfinder`) + 17 Medium in `accent`.
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                // Before the fill, so the whole capsule presses, not only its label.
                .pressable(scale = 0.98f) {
                    // The camera covers the sheet: the keyboard goes with it, not over it.
                    keyboard?.hide()
                    onScan()
                }
                .heightIn(min = 48.dp)
                .clip(CircleShape)
                .background(colors.textPrimary.copy(alpha = 0.06f))
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShroudIcon(ShroudIcons.Scan, colors.accent, size = 22.dp)
            ShroudText(ContactsCopy.SCAN_QR_CODE, inter(17f, FontWeight.Medium), colors.accent, maxLines = 1)
        }
        ShroudText(ContactsCopy.SCAN_FOOTER, inter(13f), colors.textSecondary, Modifier.padding(horizontal = 4.dp))
    }

    // The field (`:31-39`): grows 2…4 lines, monospaced, no capitalisation, no autocorrect.
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.textPrimary.copy(alpha = 0.06f))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            NoLearningTextInput {
                BasicTextField(
                    value = form.text,
                    onValueChange = { form.text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    textStyle = mono(17f).copy(color = colors.textPrimary),
                    minLines = 2,
                    maxLines = 4,
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        if (form.canSubmit) {
                            keyboard?.hide()
                            onSubmit()
                        }
                    }),
                    decorationBox = { field ->
                        Box {
                            if (form.text.isEmpty()) {
                                ShroudText(ContactsCopy.FIELD_PLACEHOLDER, mono(17f), colors.textSecondary.copy(alpha = 0.6f))
                            }
                            field()
                        }
                    },
                )
            }
        }
        ShroudText(ContactsCopy.FIELD_FOOTER, inter(13f), colors.textSecondary, Modifier.padding(horizontal = 4.dp))
    }

    // The reason, in its own card; read out like iOS's announcement (`:41-47`, `:91-92`).
    val error = form.error
    if (error != null) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.background)
                // One node holding the text, so the live region reads it out.
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            ShroudText(error, inter(14f), colors.danger)
        }
    }
}

/**
 * A 44 dp text capsule of a sheet's header (design l14KDf / rqBW3): [glass] = the glass-soft
 * "Cancel" / "Done"; otherwise the "Add" pill on `textPrimary` @ 5 %. 17 sp Regular in [color].
 * Disabled: no click (the colour says so).
 */
@Composable
internal fun SheetCapsuleButton(
    label: String,
    color: Color,
    glass: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = ShroudTheme.colors
    val surface = if (glass) {
        Modifier.glassSurface(CircleShape, GlassStyle.Soft)
    } else {
        Modifier.clip(CircleShape).background(colors.textPrimary.copy(alpha = 0.05f))
    }
    Box(
        modifier
            .pressable(enabled = enabled, scale = 0.94f, onClick = onClick)
            .heightIn(min = 44.dp)
            .then(surface)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        ShroudText(label, inter(17f), color, maxLines = 1)
    }
}
