package de.corespace.shroud.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import de.corespace.shroud.AppContainer
import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.auth.SessionController
import de.corespace.shroud.core.crypto.Bip39
import de.corespace.shroud.core.crypto.CryptoController
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.ui.components.Appear
import de.corespace.shroud.ui.components.BrandLogoMark
import de.corespace.shroud.ui.components.BrandTileBackground
import de.corespace.shroud.ui.components.CredentialRow
import de.corespace.shroud.ui.components.EncryptionPhraseCard
import de.corespace.shroud.ui.components.FlowStepper
import de.corespace.shroud.ui.components.GlassBarRow
import de.corespace.shroud.ui.components.GlassCapsuleButton
import de.corespace.shroud.ui.components.GlassCircleButton
import de.corespace.shroud.ui.components.GroupedScreen
import de.corespace.shroud.ui.components.PhraseWarningCard
import de.corespace.shroud.ui.components.PhraseWordNumberBadge
import de.corespace.shroud.ui.components.PillButton
import de.corespace.shroud.ui.components.PrimaryButton
import de.corespace.shroud.ui.components.ScreenInset
import de.corespace.shroud.ui.components.SecretKeyboard
import de.corespace.shroud.ui.components.SectionCaption
import de.corespace.shroud.ui.components.Separator
import de.corespace.shroud.ui.components.ShimmerPlaceholder
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.ShroudTextField
import de.corespace.shroud.ui.components.Toast
import de.corespace.shroud.ui.components.ToastHost
import de.corespace.shroud.ui.components.WroteDownRow
import de.corespace.shroud.ui.components.brandTileShape
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.components.rememberToastState
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.rememberHaptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Phase { Credentials, Phrase }

/**
 * Log In (`LogInFlowView.swift`; settings-lock addendum *LogInFlowView.swift*; design `uUvuy`,
 * `I7yD0G`): one screen that morphs from the account step to the phrase step. The credentials
 * open a server session; the phrase unlocks messaging on this phone and never leaves it.
 *
 * With a session already in place — pushed over the lock screen by "Use encryption phrase" — it
 * opens on the phrase step without animation and Back returns to the lock screen ([onBack], L1). For an account that never had a
 * phrase (the server answers `KEYS_REQUIRED`) the phrase step offers **"I never got a 12-word
 * phrase"**: a fresh phrase to write down and save (`:257-357, 756-830`; web-parity §14.2, P11b).
 *
 * [onUnlocked] runs once the phrase opened the chats (iOS `router.unlockMessages()`, `:750`).
 * [onLogOut] backs the interim "Log Out" capsule (design `daz2w`), shown only when the screen opened
 * signed in with nothing to go back to — a root without the lock screen underneath. Pushed over the
 * lock screen ([onBack] given) it never shows; it goes with `daz2w` once every root has the lock
 * screen (L1; the interim root is W3-SHELL's to delete).
 */
@Composable
fun LogInScreen(
    container: AppContainer,
    onBack: (() -> Unit)?,
    onSignUp: () -> Unit,
    onLogOut: () -> Unit = {},
    onUnlocked: () -> Unit = {},
) {
    LogInContent(rememberOnboardingServices(container), onBack, onSignUp, onUnlocked, onLogOut)
}

/** Sequential pair reveal (`EncryptionPhraseReveal.start`): one at a time, cancelled by the next or by [cancel]. */
private class PhraseReveal(private val scope: CoroutineScope) {
    private var job: Job? = null

    fun start(onRevealed: (Int) -> Unit) {
        job?.cancel()
        onRevealed(0)
        job = scope.launch {
            for (pair in 1..Bip39.WORD_COUNT / 2) {
                delay(Motion.PHRASE_PAIR_DELAY_MS)
                onRevealed(pair * 2)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}

/** [LogInScreen] on explicit services, for screen tests. */
@Composable
internal fun LogInContent(
    services: OnboardingServices,
    onBack: (() -> Unit)?,
    onSignUp: () -> Unit,
    onUnlocked: () -> Unit = {},
    onLogOut: () -> Unit = {},
) {
    val colors = ShroudTheme.colors
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val autofill = LocalAutofillManager.current
    val haptic = rememberHaptics()
    val scope = rememberCoroutineScope()
    val toast = rememberToastState()

    val startedSignedIn = remember { services.session.value != null }
    var phase by remember { mutableStateOf(if (startedSignedIn) Phase.Phrase else Phase.Credentials) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordRevealed by remember { mutableStateOf(false) }
    val words = remember { mutableStateListOf(*Array(Bip39.WORD_COUNT) { "" }) }
    var revealed by remember { mutableIntStateOf(Bip39.WORD_COUNT) }
    val reveal = remember { PhraseReveal(scope) }
    val actions = remember(services) { LogInActions(services) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // "I never got a 12-word phrase" (`:22-29`): offered once the server said KEYS_REQUIRED.
    var accountHasNoPhrase by remember { mutableStateOf(false) }
    var creatingPhrase by remember { mutableStateOf(false) }
    var newPhrase by remember { mutableStateOf(emptyList<String>()) }
    var wroteDownNewPhrase by remember { mutableStateOf(false) }
    val passwordFocus = remember { FocusRequester() }
    val wordFocus = remember { List(Bip39.WORD_COUNT) { FocusRequester() } }
    val localNetwork = rememberLocalNetworkAccess(services::needsLocalNetworkPermission)

    val isCredentials = phase == Phase.Credentials
    val canSubmitCredentials = username.isNotBlank() && password.isNotEmpty() && !submitting
    val canUnlock = LogInRules.canUnlock(submitting, creatingPhrase, words, newPhrase, revealed, wroteDownNewPhrase)
    val revealInProgress = revealed < Bip39.WORD_COUNT && words.any { it.isNotEmpty() }
    val signedInSession by services.session.collectAsState()
    val signedInName = LogInRules.signedInName(signedInSession?.username, username)

    // Autofill (L7): the password manager is offered the login once it worked; left without one,
    // the screen's fields are dropped from the save prompt.
    val committed = remember { booleanArrayOf(false) }
    val currentAutofill by rememberUpdatedState(autofill)
    DisposableEffect(Unit) {
        onDispose {
            reveal.cancel()
            if (!committed[0]) currentAutofill?.cancel()
        }
    }

    fun leaveNewPhrase() {
        if (!creatingPhrase) return
        reveal.cancel()
        error = null
        creatingPhrase = false
        newPhrase = emptyList()
        wroteDownNewPhrase = false
        revealed = Bip39.WORD_COUNT
    }

    fun showNewPhrase() {
        error = null
        focus.clearFocus()
        newPhrase = services.bip39.generate()
        wroteDownNewPhrase = false
        creatingPhrase = true
        reveal.start { revealed = it }
    }

    // One `GET keys/identity/{me}` each time the phrase step opens (`:126-130, 758-776`).
    LaunchedEffect(phase) {
        if (phase != Phase.Phrase) return@LaunchedEffect
        val session = services.session.value ?: return@LaunchedEffect
        val hasNone = actions.accountHasNoKey(session)
        if (phase != Phase.Phrase) return@LaunchedEffect
        accountHasNoPhrase = hasNone
        if (!hasNone) leaveNewPhrase()
    }

    fun submitCredentials() {
        if (!canSubmitCredentials) return
        submitting = true
        error = null
        scope.launch {
            try {
                when (val outcome = actions.logIn(username, password, localNetwork::ensure)) {
                    LogInActions.Outcome.Done -> {
                        committed[0] = true
                        autofill?.commit()
                        // Signed in is not unlocked: stay here for the phrase (`:706-712`).
                        focus.clearFocus()
                        phase = Phase.Phrase
                    }
                    is LogInActions.Outcome.Failed -> error = outcome.message
                    LogInActions.Outcome.SessionExpired, LogInActions.Outcome.SessionEnded -> Unit
                }
            } finally {
                submitting = false
            }
        }
    }

    fun submitPhrase() {
        if (!canUnlock) {
            error = LogInRules.incompleteMessage(creatingPhrase)
            return
        }
        focus.clearFocus()
        submitting = true
        error = null
        val chosen = LogInRules.wordsToSubmit(if (creatingPhrase) newPhrase else words)
        scope.launch {
            try {
                when (val outcome = actions.unlock(chosen, localNetwork::ensure)) {
                    LogInActions.Outcome.Done -> onUnlocked()
                    is LogInActions.Outcome.Failed -> error = outcome.message
                    // No session any more (`:725-734`): back to the credentials, which open a new one.
                    LogInActions.Outcome.SessionExpired -> {
                        error = LogInActions.SESSION_EXPIRED
                        phase = Phase.Credentials
                    }
                    // The session's auth listener ended it; the root shows why (the wipe overlay, then Welcome).
                    LogInActions.Outcome.SessionEnded -> Unit
                }
            } finally {
                submitting = false
            }
        }
    }

    fun paste() {
        val parsed = PhraseClipboard.read(context)?.let(services.bip39::parse)
        if (parsed == null) {
            // Empty clipboard or not a valid phrase (`:520-527`, L5).
            haptic(Haptic.Error)
            error = PASTE_FAILED
            return
        }
        error = null
        focus.clearFocus()
        parsed.forEachIndexed { i, w -> words[i] = w }
        reveal.start { revealed = it }
    }

    fun copyNewPhrase() {
        if (revealed != Bip39.WORD_COUNT || newPhrase.size != Bip39.WORD_COUNT) {
            toast.show(Toast.info("Wait until all 12 words appear"))
            return
        }
        if (PhraseClipboard.copy(context, newPhrase.joinToString(" "), services.appScope)) {
            haptic(Haptic.Success)
            toast.show(Toast("Encryption phrase copied"))
        } else {
            haptic(Haptic.Error)
            toast.show(Toast.failure("Couldn’t copy phrase — try again"))
        }
    }

    val back: (() -> Unit)? = when {
        !isCredentials && !startedSignedIn -> {
            {
                // The phrase step's error belongs to the phrase step (`:143-153`).
                error = null
                focus.clearFocus()
                leaveNewPhrase()
                accountHasNoPhrase = false
                phase = Phase.Credentials
            }
        }
        else -> onBack
    }
    BackHandler(enabled = !isCredentials && !startedSignedIn && !submitting) { back?.invoke() }
    // Leaving mid-request would cancel it half-done (a device row taken, keys half published).
    BackHandler(enabled = submitting) {}
    if (!isCredentials) HidePhraseFromRecents()

    GroupedScreen(Modifier.onboardingHeroDestination()) {
        Column(Modifier.fillMaxSize()) {
            GlassBarRow(
                leading = { if (back != null) GlassCircleButton(ShroudIcons.CaretLeftBold, "Back", back, enabled = !submitting) },
                trailing = {
                    // Fades out once the phrase step is up, and leaves TalkBack with it (L3).
                    val alpha by animateFloatAsState(if (isCredentials) 1f else 0f, Motion.fade(), label = "signUpAlpha")
                    if (startedSignedIn && back == null) {
                        // Interim only: a root without the lock screen under this step (design `daz2w`).
                        GlassCapsuleButton("Log Out", onLogOut, Modifier.testTag("login.logOut"), enabled = !submitting)
                    } else if (isCredentials || alpha > 0f) {
                        GlassCapsuleButton(
                            "Sign Up",
                            onSignUp,
                            Modifier
                                .graphicsLayer { this.alpha = alpha }
                                .then(if (isCredentials) Modifier.testTag("login.signUp") else Modifier.clearAndSetSemantics {}),
                            enabled = isCredentials && !submitting,
                        )
                    }
                },
            )
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ScreenInset)
                    .padding(top = 10.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AnimatedVisibility(!isCredentials, enter = fadeIn(Motion.standard()) + expandVertically(Motion.standard()), exit = fadeOut(Motion.fade()) + shrinkVertically(Motion.standard())) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.successBackground)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ShroudIcon(ShroudIcons.CheckCircleFill, colors.online, size = 15.dp)
                        ShroudText("Signed in as @$signedInName", inter(13f, FontWeight.SemiBold), colors.successText, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                }
                Hero(isCredentials, creatingPhrase)
                FlowStepper(if (isCredentials) 1 else 2)

                Box {
                    val reduce = ShroudTheme.reduceMotion
                    val credentialAlpha by animateFloatAsState(if (isCredentials) 1f else 0f, Motion.respecting(reduce, Motion.standard()), label = "credAlpha")
                    if (isCredentials || credentialAlpha > 0f) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    alpha = credentialAlpha
                                    val s = if (reduce) 1f else 0.96f + 0.04f * credentialAlpha
                                    scaleX = s
                                    scaleY = s
                                    transformOrigin = TransformOrigin(0.5f, 0f)
                                }
                                .then(if (isCredentials) Modifier else Modifier.clearAndSetSemantics {})
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.background),
                        ) {
                            CredentialRow(
                                icon = ShroudIcons.AtSign,
                                iconTint = colors.textSecondary,
                                placeholder = "Username",
                                value = username,
                                onValueChange = { username = it },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                                onImeAction = { passwordFocus.requestFocus() },
                                contentType = ContentType.Username,
                            )
                            Separator(startInset = 42.dp)
                            CredentialRow(
                                icon = ShroudIcons.Lock,
                                iconTint = colors.textSecondary,
                                placeholder = "Password",
                                value = password,
                                onValueChange = { password = it },
                                isSecret = true,
                                revealed = passwordRevealed,
                                onToggleVisible = { passwordRevealed = !passwordRevealed },
                                keyboardOptions = SecretKeyboard.copy(imeAction = ImeAction.Go),
                                onImeAction = ::submitCredentials,
                                focusRequester = passwordFocus,
                                contentType = ContentType.Password,
                            )
                        }
                    }
                    if (!isCredentials) {
                        PhraseStepContent(
                            creatingPhrase = creatingPhrase,
                            accountHasNoPhrase = accountHasNoPhrase,
                            submitting = submitting,
                            onToggleSource = { if (creatingPhrase) leaveNewPhrase() else showNewPhrase() },
                            entry = {
                                PhraseCard(
                                    words = words,
                                    revealed = revealed,
                                    pasteEnabled = !revealInProgress,
                                    onPaste = ::paste,
                                    wordFocus = wordFocus,
                                    onWordChange = { i, v -> LogInRules.applyWordInput(words, i, v) },
                                    onWordDone = { i ->
                                        // Return moves on; on the last word it unlocks once all 12 are in (`:588-598`).
                                        if (i < Bip39.WORD_COUNT - 1) {
                                            wordFocus[i + 1].requestFocus()
                                        } else {
                                            focus.clearFocus()
                                            if (canUnlock) submitPhrase()
                                        }
                                    },
                                )
                            },
                            newPhrase = {
                                NewPhraseContent(
                                    words = newPhrase,
                                    revealed = revealed,
                                    wroteDown = wroteDownNewPhrase,
                                    onToggleWroteDown = { wroteDownNewPhrase = !wroteDownNewPhrase },
                                    onCopy = ::copyNewPhrase,
                                )
                            },
                        )
                    }
                }

                if (isCredentials) {
                    ShroudText(
                        "You'll enter your encryption phrase in the next step.",
                        inter(12f),
                        colors.textSecondary,
                        Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
                // Collapsed on the credentials step, grows in with the phase (`:600-613`, L10).
                AnimatedVisibility(!isCredentials, enter = fadeIn(Motion.standard()) + expandVertically(Motion.standard()), exit = fadeOut(Motion.fade()) + shrinkVertically(Motion.standard())) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                        ShroudIcon(ShroudIcons.LockKeyhole, colors.textSecondary, size = 13.dp)
                        ShroudText("Your phrase never leaves this device.", inter(12f), colors.textSecondary)
                    }
                }
            }
            Column(Modifier.padding(horizontal = ScreenInset, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AnimatedVisibility(error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    ShroudText(error.orEmpty(), inter(13f, FontWeight.Medium), colors.dangerText, Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive })
                }
                PrimaryButton(
                    title = LogInRules.primaryTitle(isCredentials, submitting, creatingPhrase),
                    onClick = { if (isCredentials) submitCredentials() else submitPhrase() },
                    modifier = Modifier.testTag("login.primary"),
                    isLoading = submitting,
                    enabled = if (isCredentials) canSubmitCredentials else canUnlock,
                )
                if (!isCredentials) {
                    ShroudText(
                        "Shroud cannot recover a lost phrase. Messages on this device stay locked without it.",
                        inter(12f),
                        colors.textSecondary,
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        ToastHost(toast)
    }
}

/** "The clipboard doesn’t hold a valid 12-word phrase." (`:525`). */
internal const val PASTE_FAILED = "The clipboard doesn’t hold a valid 12-word phrase."

/**
 * Log In's two requests (`submitCredentials`, `submitPhraseUnlock`, `LogInFlowView.swift:699-754`),
 * apart from drawing so they run on the JVM. Main-confined.
 *
 * - [logIn]: `POST /auth/login` (the name trimmed and lower-cased, the anchored device id). A
 *   session alone never opens the chats: the screen moves on to the phrase.
 * - [unlock]: the phrase opens the vault on this phone (`CryptoController.unlockWithPhrase`; it
 *   never leaves the phone). No session any more → [Outcome.SessionExpired]. A failure that ended the
 *   session (a 401 streak, `DEVICE_REMOVED`) is the session bridge's to announce — the wipe overlay
 *   takes over — so it is [Outcome.SessionEnded] and the screen says nothing (addendum L2; settings-lock
 *   §13); otherwise the crypto message (`CryptoController.userMessage`).
 */
internal class LogInActions(private val services: OnboardingServices) {
    sealed interface Outcome {
        data object Done : Outcome
        data class Failed(val message: String) : Outcome
        data object SessionExpired : Outcome
        data object SessionEnded : Outcome
    }

    suspend fun logIn(username: String, password: String, ensureLocalNetwork: suspend () -> Boolean): Outcome = try {
        if (!ensureLocalNetwork()) {
            Outcome.Failed(LocalNetworkAccess.DENIED_MESSAGE)
        } else {
            services.login(username, password)
            Outcome.Done
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // "Invalid username or password.", the device limit, rate limits, transport text (`:713-715`).
        Outcome.Failed(SessionController.userMessage(e))
    }

    suspend fun unlock(words: List<String>, ensureLocalNetwork: suspend () -> Boolean): Outcome {
        val session = services.session.value ?: return Outcome.SessionExpired
        return try {
            if (!ensureLocalNetwork()) {
                Outcome.Failed(LocalNetworkAccess.DENIED_MESSAGE)
            } else {
                services.unlockWithPhrase(words, session)
                Outcome.Done
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (services.sessionAfterFailure() == SessionController.Validation.Offline) {
                Outcome.Failed(CryptoController.userMessage(e))
            } else {
                Outcome.SessionEnded
            }
        }
    }

    /**
     * "I never got a 12-word phrase" is offered only when the server says the account has no key
     * (`LogInFlowView.swift:758-781`). Core rethrows any other [ApiError] (offline, a 5xx): that
     * reads as "has a key", so the screen shows the plain phrase entry and nothing else.
     */
    suspend fun accountHasNoKey(session: Session): Boolean = try {
        services.accountHasNoKey(session)
    } catch (e: CancellationException) {
        throw e
    } catch (_: ApiError) {
        false
    }

    companion object {
        /** `:730`. */
        const val SESSION_EXPIRED = "Session expired. Log in again."
    }
}

/** The pure rules of the Log In screen (`LogInFlowView.swift:50-74, 404-408, 720-742`), tested on the JVM. */
internal object LogInRules {
    /** `signedInUsername` (`:50-56`): the session's name, else the typed one, else "user". */
    fun signedInName(sessionUsername: String?, typed: String): String =
        sessionUsername?.takeIf { it.isNotEmpty() } ?: typed.trim().ifEmpty { "user" }

    /**
     * `canUnlockWithPhrase` (`:64-74`): not while submitting; a new phrase needs all 12 words shown
     * and "I wrote down" ticked, as at Sign Up; typed words need all 12 cells filled.
     */
    fun canUnlock(submitting: Boolean, creatingPhrase: Boolean, words: List<String>, newPhrase: List<String>, revealed: Int, wroteDown: Boolean): Boolean {
        if (submitting) return false
        if (creatingPhrase) return newPhrase.size == Bip39.WORD_COUNT && revealed == Bip39.WORD_COUNT && wroteDown
        return words.all { it.isNotBlank() }
    }

    /** The error when the button is pressed too early (`:721-725`). */
    fun incompleteMessage(creatingPhrase: Boolean): String =
        if (creatingPhrase) "Write down all 12 words first." else "Enter all 12 words of your encryption phrase."

    /** Trimmed, lower-cased, no empties (`:740-742`). */
    fun wordsToSubmit(words: List<String>): List<String> = words.map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    /** The primary button's title (`:405-407`). */
    fun primaryTitle(isCredentials: Boolean, submitting: Boolean, creatingPhrase: Boolean): String = when {
        isCredentials -> if (submitting) "Signing in…" else "Log In"
        submitting -> "Unlocking…"
        creatingPhrase -> "Save Phrase and Continue"
        else -> "Unlock Messages"
    }

    /**
     * A word cell's input: a pasted-in run of words (a keyboard clipboard chip) fills the next
     * cells; a single word drops whitespace (Android improvement, addendum LogIn L8). Words split on
     * any Unicode whitespace or line break, as `EncryptionPhraseParser` does (`.whitespacesAndNewlines`,
     * crypto §17.1): a phrase copied with no-break spaces or line separators still spreads.
     */
    fun applyWordInput(words: MutableList<String>, index: Int, input: String) {
        val parts = splitWords(input)
        if (parts.size > 1) {
            parts.take(Bip39.WORD_COUNT - index).forEachIndexed { k, w -> words[index + k] = w.lowercase() }
        } else {
            words[index] = input.filterNot(::isWordBreak)
        }
    }

    /** [input]'s words: runs between Unicode whitespace, line breaks and NEL (U+0085). */
    private fun splitWords(input: String): List<String> {
        val out = ArrayList<String>()
        val word = StringBuilder()
        for (c in input) {
            if (isWordBreak(c)) {
                if (word.isNotEmpty()) out += word.toString()
                word.setLength(0)
            } else {
                word.append(c)
            }
        }
        if (word.isNotEmpty()) out += word.toString()
        return out
    }

    private fun isWordBreak(c: Char): Boolean = c.isWhitespace() || c == '\u0085'
}

/**
 * Brand mark on the account step, the key tile on the phrase step; title and copy follow
 * (`heroSection`, `:194-240`). A new phrase titles itself "Your New Phrase".
 */
@Composable
private fun Hero(isCredentials: Boolean, creatingPhrase: Boolean) {
    val colors = ShroudTheme.colors
    val reduce = ShroudTheme.reduceMotion
    val t by animateFloatAsState(if (isCredentials) 0f else 1f, Motion.respecting(reduce, Motion.standard()), label = "hero")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(contentAlignment = Alignment.Center) {
            BrandLogoMark(64.dp, Modifier.graphicsLayer {
                alpha = 1f - t
                val s = if (reduce) 1f else 1f - 0.18f * t
                scaleX = s
                scaleY = s
            })
            Box(
                Modifier
                    .graphicsLayer {
                        alpha = t
                        val s = if (reduce) 1f else 0.82f + 0.18f * t
                        scaleX = s
                        scaleY = s
                    }
                    .dropShadow(brandTileShape(64.dp), Shadow(radius = 10.dp, color = colors.accent, offset = DpOffset(0.dp, 8.dp), alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                BrandTileBackground(64.dp)
                ShroudIcon(ShroudIcons.KeyRound, Color.White, size = 28.dp)
            }
        }
        val copy = when {
            isCredentials -> HeroCopy.Credentials
            creatingPhrase -> HeroCopy.NewPhrase
            else -> HeroCopy.Phrase
        }
        AnimatedContent(
            targetState = copy,
            transitionSpec = { fadeIn(Motion.standard()) togetherWith fadeOut(Motion.fade()) },
            label = "heroCopy",
        ) { shown ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ShroudText(shown.title, inter(30f, FontWeight.Bold), colors.textPrimary, Modifier.semantics { heading() }, textAlign = TextAlign.Center)
                ShroudText(shown.subtitle, inter(14f, lineSpacing = 4f), colors.textSecondary, Modifier.widthIn(max = 300.dp), textAlign = TextAlign.Center)
            }
        }
    }
}

/** The hero's title and subtitle per state (`:219-230`). */
private enum class HeroCopy(val title: String, val subtitle: String) {
    Credentials("Welcome Back", "Log in with your account to continue. You'll unlock your messages in the next step."),
    Phrase("Enter Encryption Phrase", "Enter your 12-word phrase to decrypt your message history on this device."),
    NewPhrase("Your New Phrase", "Write these 12 words down. They become this account’s encryption phrase."),
}

/**
 * The 12 entry fields, or — for an account that never had a phrase — a fresh phrase to write down,
 * with the link between them shown only after the server said `KEYS_REQUIRED` (`phraseStepContent`,
 * `:257-301`).
 */
@Composable
private fun PhraseStepContent(
    creatingPhrase: Boolean,
    accountHasNoPhrase: Boolean,
    submitting: Boolean,
    onToggleSource: () -> Unit,
    entry: @Composable () -> Unit,
    newPhrase: @Composable () -> Unit,
) {
    val colors = ShroudTheme.colors
    val reduce = ShroudTheme.reduceMotion
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AnimatedContent(
            targetState = creatingPhrase,
            transitionSpec = { fadeIn(Motion.respecting(reduce, Motion.standard())) togetherWith fadeOut(Motion.fade()) },
            label = "phraseSource",
        ) { creating -> if (creating) newPhrase() else entry() }
        AnimatedVisibility(accountHasNoPhrase, enter = fadeIn(Motion.respecting(reduce, Motion.standard())), exit = fadeOut(Motion.fade())) {
            // About 18 dp of text; the target reaches past it to 48 dp (`:291-296`).
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .pressable(enabled = !submitting, onClick = onToggleSource)
                    .testTag("login.phraseSource"),
                contentAlignment = Alignment.Center,
            ) {
                ShroudText(
                    if (creatingPhrase) "I do have a phrase — let me type it" else "I never got a 12-word phrase",
                    inter(14f, FontWeight.SemiBold),
                    colors.accentText,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** The fresh phrase, the warning and "I wrote it down" (`newPhraseContent`, `:303-357`). */
@Composable
private fun NewPhraseContent(words: List<String>, revealed: Int, wroteDown: Boolean, onToggleWroteDown: () -> Unit, onCopy: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionCaption("ENCRYPTION PHRASE", Modifier.weight(1f))
            PillButton("Copy", onCopy, Modifier.testTag("login.copyNewPhrase"), icon = ShroudIcons.Copy, enabled = revealed == Bip39.WORD_COUNT)
        }
        EncryptionPhraseCard(words, revealed)
        PhraseWarningCard(
            "This is only for an account that has never had a phrase. If you already set one on another device, go back and enter that phrase. A different one is not saved.",
        )
        WroteDownRow(wroteDown, onToggleWroteDown)
    }
}

@Composable
private fun PhraseCard(
    words: List<String>,
    revealed: Int,
    pasteEnabled: Boolean,
    onPaste: () -> Unit,
    wordFocus: List<FocusRequester>,
    onWordChange: (Int, String) -> Unit,
    onWordDone: (Int) -> Unit,
) {
    val colors = ShroudTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.background)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionCaption("ENCRYPTION PHRASE", Modifier.weight(1f))
            PillButton("Paste", onPaste, Modifier.padding(0.dp).testTag("login.paste"), icon = ShroudIcons.Clipboard, enabled = pasteEnabled)
        }
        // Grid rows 8 apart; header → grid 10 (`:502`, L6).
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in 0 until 6) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (column in 0 until 2) {
                        val i = row * 2 + column
                        WordField(i, words[i], i < revealed, wordFocus[i], { onWordChange(i, it) }, { onWordDone(i) }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * One word cell (`phraseWordField`, `:542-586`): number badge, the word 14 Medium monospaced with
 * no suggestions and no keyboard learning (P5), Next / Go; a tap anywhere on the 40 dp cell focuses
 * it once revealed (L4); before the reveal a shimmer.
 */
@Composable
private fun WordField(
    index: Int,
    value: String,
    isRevealed: Boolean,
    focusRequester: FocusRequester,
    onChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    val colors = ShroudTheme.colors
    val reduce = ShroudTheme.reduceMotion
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.backgroundGrouped)
            .pointerInput(isRevealed) { if (isRevealed) detectTapGestures { focusRequester.requestFocus() } }
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhraseWordNumberBadge(index + 1, isRevealed, Modifier.clearAndSetSemantics {})
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Appear(
                isRevealed,
                enter = if (reduce) fadeIn(Motion.fade()) else fadeIn(Motion.wordReveal()) + scaleIn(Motion.wordReveal(), 0.86f, TransformOrigin(0f, 0.5f)),
                exit = fadeOut(Motion.fade()),
            ) {
                ShroudTextField(
                    value = value,
                    onValueChange = onChange,
                    placeholder = "word",
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Word ${index + 1}" }.testTag("login.word${index + 1}"),
                    style = inter(14f, FontWeight.Medium, monospaced = true),
                    keyboardOptions = SecretKeyboard.copy(imeAction = if (index < Bip39.WORD_COUNT - 1) ImeAction.Next else ImeAction.Go),
                    keyboardActions = KeyboardActions(onAny = { onDone() }),
                    focusRequester = focusRequester,
                    noPersonalizedLearning = true,
                )
            }
            Appear(!isRevealed, enter = fadeIn(Motion.fade()), exit = fadeOut(Motion.fade())) {
                ShimmerPlaceholder(72.dp, 14.dp)
            }
        }
    }
}
