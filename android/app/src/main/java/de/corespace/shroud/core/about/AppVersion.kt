package de.corespace.shroud.core.about

/**
 * This build's version as Settings › About Shroud shows it: [name] is `versionName` ("0.1.0"),
 * [code] the base `versionCode` (`BuildConfig.BASE_VERSION_CODE`), without the ABI offset the
 * release splits add (`offset × 1000 + base`, `app/build.gradle.kts`).
 */
data class AppVersion(val name: String, val code: Int)
