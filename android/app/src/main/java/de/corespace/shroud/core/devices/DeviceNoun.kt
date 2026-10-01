package de.corespace.shroud.core.devices

import android.content.Context

/**
 * What this device is called in copy: "phone", or "tablet" on a large screen (settings-lock copy
 * convention [A]; P14 "this phone"/"this tablet"). iOS reads `UIDevice.current.model` ("iPhone" /
 * "iPad"); Android has no such name, so the smallest screen width decides, like the layouts do.
 */
object DeviceNoun {
    const val PHONE = "phone"
    const val TABLET = "tablet"

    /** Width from which a device is a tablet (`smallestScreenWidthDp ≥ 600`, the platform's sw600dp). */
    const val TABLET_MIN_SMALLEST_WIDTH_DP = 600

    /** "phone" or "tablet" for this device now. */
    fun current(context: Context): String = forSmallestWidthDp(context.resources.configuration.smallestScreenWidthDp)

    /** "phone" below [TABLET_MIN_SMALLEST_WIDTH_DP] dp, else "tablet". */
    fun forSmallestWidthDp(smallestWidthDp: Int): String = if (smallestWidthDp >= TABLET_MIN_SMALLEST_WIDTH_DP) TABLET else PHONE
}
