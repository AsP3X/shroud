package de.corespace.shroud.core.notifications

import android.content.Context
import android.view.accessibility.AccessibilityManager

/**
 * What the banner's lifetime depends on (iOS `UIAccessibility.isVoiceOverRunning`,
 * `NotificationsController.swift:135`; notifications-push §5.12.3).
 */
interface AccessibilityState {
    /** TalkBack (or another touch-exploration service) is on: the iOS VoiceOver case. */
    val isTouchExplorationEnabled: Boolean

    /**
     * The user's "Time to take action" setting applied to [originalMillis] for content with text
     * and controls; never shorter than [originalMillis].
     */
    fun recommendedTimeoutMillis(originalMillis: Long): Long
}

/** [AccessibilityState] on the platform `AccessibilityManager`. */
class AndroidAccessibilityState(private val context: Context) : AccessibilityState {
    private val manager: AccessibilityManager? get() = context.getSystemService(AccessibilityManager::class.java)

    override val isTouchExplorationEnabled: Boolean get() = manager?.isTouchExplorationEnabled == true

    override fun recommendedTimeoutMillis(originalMillis: Long): Long {
        val manager = manager ?: return originalMillis
        val recommended = manager.getRecommendedTimeoutMillis(
            originalMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS,
        )
        return maxOf(originalMillis, recommended.toLong())
    }
}
