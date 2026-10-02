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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.corespace.shroud.AppContainer
import de.corespace.shroud.core.auth.SessionController
import de.corespace.shroud.core.crypto.Bip39
import de.corespace.shroud.core.crypto.CryptoController
import de.corespace.shroud.ui.components.Appear
import de.corespace.shroud.ui.components.BrandLogoMark
import de.corespace.shroud.ui.components.BrandTileBackground
import de.corespace.shroud.ui.components.CredentialRow
import de.corespace.shroud.ui.components.FlowStepper
import de.corespace.shroud.ui.components.GlassBarRow
import de.corespace.shroud.ui.components.GlassCapsuleButton
import de.corespace.shroud.ui.components.GlassCircleButton
import de.corespace.shroud.ui.components.GroupedScreen
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
import de.corespace.shroud.ui.components.brandTileShape
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Phase { Credentials, Phrase }

/**
 * Log In (`LogInFlowView.swift`): one screen that morphs from the account step to the phrase
 * step. The credentials open a server session; the phrase unlocks messaging on this phone and
 * never leaves it. With a session already in place it opens on the phrase step
 * ([startedSignedIn]) and Back leaves the screen; with nothing to go back to, the bar offers
 * Log Out instead, so a lost phrase or a dead session is never a dead end.
 */
@Composable
fun LogInScreen(
    container: AppContainer,
    onBack: (() -> Unit)?,
    onSignUp: () -> Unit,
    onLogOut: () -> Unit,
) {
    val colors = ShroudTheme.colors
    val reduce = ShroudTheme.reduceMotion
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val sessions = container.auth.sessionController

    val startedSignedIn = remember { sessions.session.value != null }
    var phase by remember { mutableStateOf(if (startedSignedIn) Phase.Phrase else Phase.Credentials) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordRevealed by remember { mutableStateOf(false) }
    val words = remember { mutableStateListOf(*Array(Bip39.WORD_COUNT) { "" }) }
    var revealed by remember { mutableIntStateOf(Bip39.WORD_COUNT) }
    var revealRun by remember { mutableIntStateOf(0) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val passwordFocus = remember { FocusRequester() }
    val wordFocus = remember { List(Bip39.WORD_COUNT) { FocusRequester() } }
    val localNetwork = rememberLocalNetworkAccess(container)

    val isCredentials = phase == Phase.Credentials
    val canSubmitCredentials = username.isNotBlank() && password.isNotEmpty() && !submitting
    val canUnlock = !submitting && words.all { it.isNotBlank() }
    val revealInProgress = revealed < Bip39.WORD_COUNT && words.any { it.isNotEmpty() }
    val signedInName = sessions.session.value?.username ?: username.trim().ifEmpty { "user" }

    // A pasted phrase replays Sign Up's paired reveal.
    LaunchedEffect(revealRun) {
        if (revealRun == 0) return@LaunchedEffect
        for (pair in 1..Bip39.WORD_COUNT / 2) {
            delay(Motion.PHRASE_PAIR_DELAY_MS)
            revealed = pair * 2
        }
    }

    fun submitCredentials() {
        if (!canSubmitCredentials) return
        submitting = true
        error = null
        scope.launch {
            try {
                if (!localNetwork.ensure()) {
                    error = LocalNetworkAccess.DENIED_MESSAGE
                    return@launch
                }
                sessions.login(username, password)
                // Signed in is not unlocked: stay here for the phrase.
                focus.clearFocus()
                phase = Phase.Phrase
            } catch (e: Throwable) {
                error = SessionController.userMessage(e)
            } finally {
                submitting = false
            }
        }
    }

    fun submitPhrase() {
        if (!canUnlock) {
            error = "Enter all 12 words of your encryption phrase."
            return
        }
        val session = sessions.session.value
        if (session == null) {
            error = "Session expired. Log in again."
            phase = Phase.Credentials
            return
        }
        focus.clearFocus()
        submitting = true
        error = null
        scope.launch {
            try {
                if (!localNetwork.ensure()) {
                    error = LocalNetworkAccess.DENIED_MESSAGE
                    return@launch
                }
                container.auth.onboarding.unlockWithPhrase(words.map { it.trim().lowercase() }, session)
            } catch (e: Throwable) {
                // A revoked or removed session must not trap the user on this step: the session's
                // auth listener already ended it, and the root goes back to Welcome with its message.
                if (sessions.sessionAfterFailure() == SessionController.Validation.Offline) {
                    error = CryptoController.userMessage(e)
                }
            } finally {
                submitting = false
            }
        }
    }

    fun paste() {
        val parsed = PhraseClipboard.read(context)?.let(container.keys.bip39::parse)
        if (parsed == null) {
            error = "The clipboard doesn’t hold a valid 12-word phrase."
            return
        }
        error = null
        focus.clearFocus()
        parsed.forEachIndexed { i, w -> words[i] = w }
        revealed = 0
        revealRun++
    }

    val back: (() -> Unit)? = when {
        !isCredentials && !startedSignedIn -> {
            {
                error = null
                focus.clearFocus()
                phase = Phase.Credentials
            }
        }
        else -> onBack
    }
    BackHandler(enabled = !isCredentials && !startedSignedIn) { back?.invoke() }
    // Leaving mid-request would cancel it half-done (a device row taken, keys half published).
    BackHandler(enabled = submitting) {}
    if (!isCredentials) HidePhraseFromRecents()

    GroupedScreen {
        Column(Modifier.fillMaxSize()) {
            GlassBarRow(
                leading = { if (back != null) GlassCircleButton(ShroudIcons.CaretLeftBold, "Back", back, enabled = !submitting) },
                trailing = {
                    if (startedSignedIn && back == null) {
                        GlassCapsuleButton("Log Out", onLogOut, enabled = !submitting)
                    } else {
                        val alpha by animateFloatAsState(if (isCredentials) 1f else 0f, Motion.fade(), label = "signUpAlpha")
                        GlassCapsuleButton("Sign Up", onSignUp, Modifier.graphicsLayer { this.alpha = alpha }, enabled = isCredentials && !submitting)
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
                Hero(isCredentials)
                FlowStepper(if (isCredentials) 1 else 2)

                Box {
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
                            )
                        }
                    }
                    if (!isCredentials) {
                        PhraseCard(
                            words = words,
                            revealed = revealed,
                            pasteEnabled = !revealInProgress,
                            onPaste = ::paste,
                            wordFocus = wordFocus,
                            onWordChange = { i, v ->
                                // A pasted-in run of words (a keyboard clipboard chip) fills the next cells.
                                val parts = v.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                                if (parts.size > 1) {
                                    parts.take(Bip39.WORD_COUNT - i).forEachIndexed { k, w -> words[i + k] = w.lowercase() }
                                } else {
                                    words[i] = v.filterNot(Char::isWhitespace)
                                }
                            },
                            onWordDone = { i -> if (i < Bip39.WORD_COUNT - 1) wordFocus[i + 1].requestFocus() else submitPhrase() },
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
                } else {
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
                    title = if (isCredentials) {
                        if (submitting) "Signing in…" else "Log In"
                    } else {
                        if (submitting) "Unlocking…" else "Unlock Messages"
                    },
                    onClick = { if (isCredentials) submitCredentials() else submitPhrase() },
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
    }
}

/** Brand mark on the account step, the key tile on the phrase step; title and copy follow. */
@Composable
private fun Hero(isCredentials: Boolean) {
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
                    .shadow(12.dp, brandTileShape(64.dp), ambientColor = colors.accent.copy(alpha = 0.25f), spotColor = colors.accent.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                BrandTileBackground(64.dp)
                ShroudIcon(ShroudIcons.KeyRound, Color.White, size = 28.dp)
            }
        }
        AnimatedContent(
            targetState = isCredentials,
            transitionSpec = { fadeIn(Motion.standard()) togetherWith fadeOut(Motion.fade()) },
            label = "heroCopy",
        ) { credentials ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ShroudText(
                    if (credentials) "Welcome Back" else "Enter Encryption Phrase",
                    inter(30f, FontWeight.Bold),
                    colors.textPrimary,
                    Modifier.semantics { heading() },
                    textAlign = TextAlign.Center,
                )
                ShroudText(
                    if (credentials) {
                        "Log in with your account to continue. You'll unlock your messages in the next step."
                    } else {
                        "Enter your 12-word phrase to decrypt your message history on this device."
                    },
                    inter(14f, lineSpacing = 4f),
                    colors.textSecondary,
                    Modifier.widthIn(max = 300.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
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
            PillButton("Paste", onPaste, Modifier.padding(0.dp), icon = ShroudIcons.Clipboard, enabled = pasteEnabled)
        }
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
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Word ${index + 1}" },
                    style = inter(14f, FontWeight.Medium, monospaced = true),
                    keyboardOptions = SecretKeyboard.copy(imeAction = if (index < Bip39.WORD_COUNT - 1) ImeAction.Next else ImeAction.Go),
                    keyboardActions = KeyboardActions(onAny = { onDone() }),
                    focusRequester = focusRequester,
                )
            }
            Appear(!isRevealed, enter = fadeIn(Motion.fade()), exit = fadeOut(Motion.fade())) {
                ShimmerPlaceholder(72.dp, 14.dp)
            }
        }
    }
}

