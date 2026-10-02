package de.corespace.shroud.ui.onboarding

import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import de.corespace.shroud.ui.components.ComposeHarness

/**
 * Screen-test helpers on [ComposeHarness] (the JVM stand-in for a Compose UI test): find nodes by
 * test tag or text, and act on them the way TalkBack and the keyboard do — click, set text, the IME
 * action. Every action is followed by an idle so effects and recompositions land.
 */

/** Every semantics node, unmerged (a node's own tag, text and actions). */
internal fun ComposeHarness.unmergedNodes(): List<SemanticsNode> =
    (root as RootForTest).semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)

/** The one node tagged [tag]. */
internal fun ComposeHarness.tagged(tag: String): SemanticsNode {
    val matches = unmergedNodes().filter { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    check(matches.size == 1) { "expected one node tagged \"$tag\", found ${matches.size}: ${describe()}" }
    return matches.single()
}

internal fun ComposeHarness.hasTag(tag: String): Boolean =
    unmergedNodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

/** True when a merged node (what TalkBack reads) shows [text] or is described by it. */
internal fun ComposeHarness.shows(text: String): Boolean = nodes().any { node ->
    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true
}

/** The merged node with the content description or text [label] that can be clicked. */
internal fun ComposeHarness.button(label: String): SemanticsNode {
    val matches = nodes().filter { node ->
        SemanticsActions.OnClick in node.config &&
            (node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true)
    }
    check(matches.size == 1) { "expected one button \"$label\", found ${matches.size}: ${describe()}" }
    return matches.single()
}

internal fun ComposeHarness.hasButton(label: String): Boolean = nodes().any { node ->
    SemanticsActions.OnClick in node.config &&
        (node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true)
}

internal val SemanticsNode.isEnabled: Boolean get() = SemanticsProperties.Disabled !in config

internal fun ComposeHarness.click(node: SemanticsNode) {
    check(node.isEnabled) { "node #${node.id} is disabled" }
    node.config[SemanticsActions.OnClick].action!!.invoke()
    idle()
}

/** The text field described by [label] (unmerged: the field's own node). */
internal fun ComposeHarness.field(label: String): SemanticsNode {
    val matches = unmergedNodes().filter { node ->
        SemanticsActions.SetText in node.config && node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
    }
    check(matches.size == 1) { "expected one field \"$label\", found ${matches.size}: ${describe()}" }
    return matches.single()
}

internal fun ComposeHarness.type(node: SemanticsNode, text: String) {
    node.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(text))
    idle()
}

/** The keyboard's action key (Next / Go / Done) on [node]. */
internal fun ComposeHarness.imeAction(node: SemanticsNode) {
    node.config[SemanticsActions.OnImeAction].action!!.invoke()
    idle()
}

internal val SemanticsNode.editableText: String get() = config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

/**
 * Idles until [condition] holds. Coroutine `delay`s inside compositions run on real time here (the
 * main dispatcher of the composition has no virtual clock), so this sleeps in between.
 */
internal fun ComposeHarness.waitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (true) {
        idle()
        if (condition()) return
        check(System.currentTimeMillis() < end) { "condition not met within $timeoutMs ms: ${describe()}" }
        Thread.sleep(20)
    }
}
