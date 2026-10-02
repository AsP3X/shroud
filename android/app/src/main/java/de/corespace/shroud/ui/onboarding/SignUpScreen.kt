package de.corespace.shroud.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.corespace.shroud.AppContainer
import de.corespace.shroud.core.auth.PasswordStrength
import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.auth.SessionController
import de.corespace.shroud.core.crypto.Bip39
import de.corespace.shroud.core.crypto.CryptoController
import de.corespace.shroud.core.crypto.CryptoException
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ErrorCodes
import de.corespace.shroud.ui.components.BrandLogoMark
import de.corespace.shroud.ui.components.CheckBoxMark
import de.corespace.shroud.ui.components.CredentialRow
import de.corespace.shroud.ui.components.EncryptionPhraseCard
import de.corespace.shroud.ui.components.FlowStepper
import de.corespace.shroud.ui.components.GlassBarRow
import de.corespace.shroud.ui.components.GlassCapsuleButton
import de.corespace.shroud.ui.components.GlassCircleButton
import de.corespace.shroud.ui.components.GroupedScreen
import de.corespace.shroud.ui.components.PasswordStrengthMeter
import de.corespace.shroud.ui.components.PillButton
import de.corespace.shroud.ui.components.PrimaryButton
import de.corespace.shroud.ui.components.ScreenInset
import de.corespace.shroud.ui.components.SecretKeyboard
import de.corespace.shroud.ui.components.SectionCaption
import de.corespace.shroud.ui.components.Separator
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.Toast
import de.corespace.shroud.ui.components.ToastState
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class SignUpStep { Account, Phrase }

/**
 * Sign Up in two steps (`Sign Up` and `Sign Up — Phrase` in the design): first the username and
 * password, then the encryption phrase generated on this phone. Only the username and password go
 * to the server, and only once the phrase step is confirmed — so an abandoned sign-up never leaves
 * an account without keys. The server's objections to the name or password send the user back to
 * the account step, where they belong.
 */
@Composable
fun SignUpScreen(container: AppContainer, toast: ToastState, onBack: () -> Unit, onLogIn: () -> Unit) {
    val colors = ShroudTheme.colors
    val reduce = ShroudTheme.reduceMotion
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(SignUpStep.Account) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordRevealed by remember { mutableStateOf(false) }
    var wroteDown by remember { mutableStateOf(false) }
    // One phrase per visit: going back to fix the name keeps the words already written down.
    val words = remember { container.keys.bip39.generate() }
    var revealed by remember { mutableIntStateOf(0) }
    var submitting by remember { mutableStateOf(false) }
    var accountError by remember { mutableStateOf<String?>(null) }
    var phraseError by remember { mutableStateOf<String?>(null) }
    val passwordFocus = remember { FocusRequester() }
    // The account this screen created, with the name and password it was created with: a retry
    // after the keys failed to publish reuses it. Never a session from anywhere else (that could
    // be an existing account whose keys this screen must not replace).
    var registered by remember { mutableStateOf<Pair<Session, String>?>(null) }
    val localNetwork = rememberLocalNetworkAccess(container)

    val evaluation = PasswordStrength.evaluate(password)
    val canContinue = username.isNotBlank() && evaluation.meetsRequirements && !submitting
    val canCreate = wroteDown && revealed == Bip39.WORD_COUNT && !submitting

    if (step == SignUpStep.Phrase) HidePhraseFromRecents()
    // Leaving mid-request would cancel it half-done (account created, keys not published).
    BackHandler(enabled = submitting) {}
    BackHandler(enabled = !submitting && step == SignUpStep.Phrase) {
        phraseError = null
        step = SignUpStep.Account
    }

    // Words arrive in pairs, 45 ms apart (`EncryptionPhraseReveal`), the first time the step shows.
    LaunchedEffect(step) {
        if (step != SignUpStep.Phrase || revealed == Bip39.WORD_COUNT) return@LaunchedEffect
        for (pair in 1..Bip39.WORD_COUNT / 2) {
            delay(Motion.PHRASE_PAIR_DELAY_MS)
            revealed = pair * 2
        }
    }

    fun continueToPhrase() {
        if (!canContinue) return
        focus.clearFocus()
        accountError = SessionController.usernameProblem(username)
            // Checked before any phrase is shown: without a screen lock the keys cannot be protected.
            ?: CryptoController.userMessage(CryptoException.NoScreenLock()).takeUnless { container.auth.onboarding.hasScreenLock() }
        if (accountError == null) step = SignUpStep.Phrase
    }

    fun createAccount() {
        if (!canCreate) return
        phraseError = null
        submitting = true
        scope.launch {
            try {
                container.keys.bip39.validate(words)
                if (!localNetwork.ensure()) {
                    phraseError = LocalNetworkAccess.DENIED_MESSAGE
                    return@launch
                }
                val sessions = container.auth.sessionController
                val name = SessionController.normalize(username)
                val session = registered
                    ?.takeIf { (s, pw) -> s.username == name && pw == password && sessions.session.value == s }
                    ?.first
                    ?: sessions.register(username, password).also { registered = it to password }
                container.auth.onboarding.establishFromSignup(words, session)
            } catch (e: Throwable) {
                if (e is ApiError.Server && e.code in CREDENTIAL_ERRORS) {
                    accountError = e.userMessage
                    step = SignUpStep.Account
                } else {
                    phraseError = if (e is Bip39.PhraseException || e is CryptoException) CryptoController.userMessage(e) else SessionController.userMessage(e)
                }
            } finally {
                submitting = false
            }
        }
    }

    GroupedScreen {
        Column(Modifier.fillMaxSize()) {
            GlassBarRow(
                leading = {
                    GlassCircleButton(
                        ShroudIcons.CaretLeftBold,
                        "Back",
                        onClick = {
                            if (step == SignUpStep.Phrase) {
                                phraseError = null
                                step = SignUpStep.Account
                            } else {
                                onBack()
                            }
                        },
                        enabled = !submitting,
                    )
                },
                trailing = {
                    if (step == SignUpStep.Account) GlassCapsuleButton("Log In", onLogIn, enabled = !submitting)
                },
            )
            AnimatedContent(
                targetState = step,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    val forward = targetState == SignUpStep.Phrase
                    if (reduce) {
                        fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade())
                    } else {
                        (slideInHorizontally(Motion.standard()) { if (forward) it / 3 else -it / 3 } + fadeIn(Motion.fade())) togetherWith
                            (slideOutHorizontally(Motion.standard()) { if (forward) -it / 3 else it / 3 } + fadeOut(Motion.fade()))
                    }
                },
                label = "signUpStep",
            ) { shown ->
                Column(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = ScreenInset)
                            .padding(top = 6.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        BrandLogoMark(48.dp, Modifier.padding(bottom = 4.dp))
                        ShroudText(
                            if (shown == SignUpStep.Account) "Create Account" else "Save Your Phrase",
                            inter(32f, FontWeight.Bold),
                            colors.textPrimary,
                            Modifier.semantics { heading() },
                        )
                        FlowStepper(if (shown == SignUpStep.Account) 1 else 2, Modifier.padding(vertical = 2.dp), alignment = Alignment.Start)
                        if (shown == SignUpStep.Account) {
                            AccountStep(
                                username = username,
                                onUsername = {
                                    username = it
                                    accountError = null
                                },
                                password = password,
                                onPassword = {
                                    password = it
                                    accountError = null
                                },
                                passwordRevealed = passwordRevealed,
                                onTogglePassword = { passwordRevealed = !passwordRevealed },
                                evaluation = evaluation,
                                passwordFocus = passwordFocus,
                                onDone = ::continueToPhrase,
                            )
                        } else {
                            PhraseStep(
                                words = words,
                                revealed = revealed,
                                onCopy = {
                                    if (PhraseClipboard.copy(context, words.joinToString(" "), container.appScope)) {
                                        toast.show(Toast("Encryption phrase copied"))
                                    } else {
                                        toast.show(Toast.failure("Couldn’t copy phrase — try again"))
                                    }
                                },
                            )
                        }
                    }
                    Column(
                        Modifier.padding(horizontal = ScreenInset, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val error = if (shown == SignUpStep.Account) accountError else phraseError
                        AnimatedVisibility(error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                            ShroudText(
                                error.orEmpty(),
                                inter(13f, FontWeight.Medium),
                                colors.dangerText,
                                Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
                            )
                        }
                        if (shown == SignUpStep.Account) {
                            ShroudText(
                                "Next you’ll get the 12-word phrase that protects your messages.",
                                inter(12f),
                                colors.textSecondary,
                                Modifier.fillMaxWidth(),
                            )
                            PrimaryButton("Continue", ::continueToPhrase, enabled = canContinue)
                        } else {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .pressable(enabled = !submitting, scale = 0.97f, role = Role.Checkbox, onClick = { wroteDown = !wroteDown })
                                    .padding(horizontal = 4.dp)
                                    .semantics(mergeDescendants = true) { stateDescription = if (wroteDown) "Checked" else "Not checked" },
                                horizontalArrangement = Arrangement.spacedBy(9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CheckBoxMark(wroteDown)
                                ShroudText("I wrote down my encryption phrase", inter(13f, FontWeight.Medium), colors.textPrimary)
                            }
                            PrimaryButton(
                                title = if (submitting) "Creating…" else "Create Account",
                                onClick = ::createAccount,
                                isLoading = submitting,
                                enabled = canCreate,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Server errors that are about the name or password, shown back on the account step. */
private val CREDENTIAL_ERRORS = setOf(
    ErrorCodes.USERNAME_TAKEN,
    ErrorCodes.USERNAME_RESERVED,
    ErrorCodes.VALIDATION_ERROR,
    ErrorCodes.PASSWORD_TOO_SHORT,
    ErrorCodes.PASSWORD_TOO_COMMON,
)

@Composable
private fun AccountStep(
    username: String,
    onUsername: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    passwordRevealed: Boolean,
    onTogglePassword: () -> Unit,
    evaluation: PasswordStrength,
    passwordFocus: FocusRequester,
    onDone: () -> Unit,
) {
    val colors = ShroudTheme.colors
    SectionCaption("CHOOSE YOUR IDENTITY", Modifier.padding(top = 6.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.background)) {
        CredentialRow(
            icon = ShroudIcons.AtSign,
            iconTint = colors.accent,
            placeholder = "Username",
            value = username,
            onValueChange = onUsername,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            onImeAction = { passwordFocus.requestFocus() },
        )
        Separator(startInset = 42.dp)
        CredentialRow(
            icon = ShroudIcons.Lock,
            iconTint = colors.accent,
            placeholder = "Password",
            value = password,
            onValueChange = onPassword,
            isSecret = true,
            revealed = passwordRevealed,
            onToggleVisible = onTogglePassword,
            keyboardOptions = SecretKeyboard.copy(imeAction = ImeAction.Next),
            onImeAction = onDone,
            focusRequester = passwordFocus,
        )
        Separator(startInset = 42.dp)
        PasswordStrengthMeter(evaluation)
    }
    ShroudText("No phone number or email required.", inter(12f), colors.textSecondary)
}

@Composable
private fun PhraseStep(words: List<String>, revealed: Int, onCopy: () -> Unit) {
    val colors = ShroudTheme.colors
    ShroudText(
        "Write these 12 words down, in order, and keep them somewhere safe. You need them to read your messages on a new phone.",
        inter(14f, lineSpacing = 4f),
        colors.textSecondary,
    )
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        SectionCaption("ENCRYPTION PHRASE", Modifier.weight(1f))
        PillButton("Copy", onCopy, enabled = revealed == Bip39.WORD_COUNT)
    }
    EncryptionPhraseCard(words, revealed)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.warningBackground)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        ShroudIcon(ShroudIcons.WarningFill, colors.warningIcon, size = 15.dp)
        ShroudText(
            "These 12 words are the only way to restore your messages. Shroud cannot recover them for you.",
            inter(12f, lineSpacing = 3f),
            colors.warningText,
        )
    }
}
