import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// One build: no product flavors, no Firebase/FCM BuildConfig fields, no google-services plugin
// (decision record 2026-10-01, 00-plan §5.2). Shared file — one owner per wave (00-plan §2.6).
android {
    namespace = "de.corespace.shroud"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.corespace.shroud"
        // Android 11: the oldest version in the test matrix (no RenderEffect blur there — the
        // design's "Glass — Without Blur" fallback). Keystore auth parameters need API 30.
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Debug builds point at a local Compose stack from the emulator (10.0.2.2 is the host).
        buildConfigField("String", "DEFAULT_SELF_HOSTED_HOST", "\"10.0.2.2\"")
        buildConfigField("int", "DEFAULT_SELF_HOSTED_PORT", "8080")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }

    // Reproducible builds: no build-time values baked into the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric (only where a Bitmap or NotificationManager is unavoidable, 00-plan §5.1).
        unitTests.isIncludeAndroidResources = true
    }

    // region native — whisper.cpp (00-plan §5.2): pinned ndkVersion, CMake 3.31.x,
    // abiFilters arm64-v8a + x86_64, -O3, GGML_NATIVE=OFF. Filled by W2-WHISPER (W2),
    // W3-TRANSCRIPTION (W3), W4-RELEASE (W4); nobody else edits between these markers.
    // endregion native
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.bouncycastle)

    // Feature libraries of later waves (00-plan §5.1), declared once here.
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.emoji2.bundled)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.video)
    implementation(libs.camerax.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.muxer)
    implementation(libs.androidx.exifinterface)
    implementation(libs.ink.authoring.compose)
    implementation(libs.ink.brush)
    implementation(libs.ink.strokes)
    implementation(libs.ink.geometry)
    implementation(libs.ink.rendering)
    implementation(libs.zxing.core)
    implementation(libs.webrtc)
    implementation(libs.androidx.core.telecom)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.icu4j)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.work.testing)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.compose.ui.test.manifest)
}

// ---------------------------------------------------------------------------------------------
// Ground-rule checks, wired into `check` and CI (00-plan §1.1 rules 1 and 5, §2.0 rule 10, §5.2).

/**
 * Base of the dependency checks: the resolved debug and release runtime graphs as task inputs
 * (configuration-cache safe), walked into "group:name" coordinates.
 */
abstract class RuntimeGraphTask : DefaultTask() {
    @get:Input
    abstract val releaseRuntime: Property<ResolvedComponentResult>

    @get:Input
    abstract val debugRuntime: Property<ResolvedComponentResult>

    /** Every external module ("group:name") the app ships in either build type. */
    protected fun shippedModules(): Set<String> {
        val modules = sortedSetOf<String>()
        val seen = HashSet<Any>()
        val queue = ArrayDeque<ResolvedComponentResult>()
        queue += releaseRuntime.get()
        queue += debugRuntime.get()
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            if (!seen.add(component.id)) continue
            (component.id as? ModuleComponentIdentifier)?.let { modules += "${it.group}:${it.module}" }
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { queue += it.selected }
        }
        return modules
    }

    protected fun finish(report: RegularFileProperty, problems: List<String>, rule: String) {
        report.get().asFile.writeText(problems.joinToString("\n", postfix = "\n"))
        if (problems.isNotEmpty()) throw GradleException("$rule:\n" + problems.joinToString("\n"))
    }
}

/**
 * Fails on any reference to the Compose Material libraries: in the app's sources (imports,
 * fully qualified names) or among the modules the app ships. The app draws its own chrome
 * from `ui/theme` tokens (00-plan §1.1 rule 5).
 */
abstract class VerifyNoMaterialTask : RuntimeGraphTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val forbidden = "androidx.compose." + "material"
        val inSources = sources.files.sorted().flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (line.contains(forbidden)) "${file.path}:${index + 1}: ${line.trim()}" else null
            }
        }
        val inModules = shippedModules().filter { it.startsWith(forbidden) }.map { "dependency $it" }
        finish(report, inSources + inModules, "No Material components (00-plan §1.1 rule 5)")
    }
}

/**
 * Fails when Google services reach the app: a module from a banned group (or a banned module)
 * on the debug or release runtime classpath, or a GMS/Firebase/C2DM entry in the merged release
 * manifest (decision record 2026-10-01, 00-plan §5.2). [allowedModules] exceptions need the
 * product owner's sign-off in the PR; none are expected.
 */
abstract class VerifyNoGoogleServicesTask : RuntimeGraphTask() {
    @get:Input
    abstract val bannedGroups: ListProperty<String>

    @get:Input
    abstract val bannedModules: ListProperty<String>

    @get:Input
    abstract val allowedModules: SetProperty<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedReleaseManifest: RegularFileProperty

    @get:Input
    abstract val bannedManifestTexts: ListProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val allowed = allowedModules.get()
        val groups = bannedGroups.get()
        val modules = bannedModules.get()
        val badModules = shippedModules().filter { coordinate ->
            val group = coordinate.substringBefore(':')
            coordinate !in allowed &&
                (coordinate in modules || groups.any { group == it || group.startsWith("$it.") })
        }
        val manifest = mergedReleaseManifest.get().asFile.readText()
        val badManifest = bannedManifestTexts.get().filter { manifest.contains(it) }
        finish(
            report,
            badModules.map { "dependency $it" } + badManifest.map { "merged release manifest mentions $it" },
            "No Google services (decision record 2026-10-01)",
        )
    }
}

val verifyNoMaterial = tasks.register<VerifyNoMaterialTask>("verifyNoMaterial") {
    group = "verification"
    description = "Fails on any Compose Material reference in sources or shipped dependencies."
    sources.from(fileTree("src") { include("**/*.kt", "**/*.java", "**/*.xml") })
    report.set(layout.buildDirectory.file("reports/verifyNoMaterial.txt"))
}

val verifyNoGoogleServices = tasks.register<VerifyNoGoogleServicesTask>("verifyNoGoogleServices") {
    group = "verification"
    description = "Fails if Firebase, Google Play services, ML Kit, Tink or Play libraries reach the app."
    bannedGroups.set(
        listOf(
            "com.google.firebase",
            "com.google.android.gms",
            "com.google.gms",
            "com.google.mlkit",
            "com.google.crypto.tink",
            "com.google.android.play",
            "com.android.installreferrer",
            "com.google.android.datatransport",
        ),
    )
    bannedModules.set(listOf("org.jetbrains.kotlinx:kotlinx-coroutines-play-services"))
    allowedModules.set(emptySet())
    bannedManifestTexts.set(listOf("com.google.android.gms", "com.google.android.c2dm", "com.google.firebase"))
    report.set(layout.buildDirectory.file("reports/verifyNoGoogleServices.txt"))
}

// The variants' runtime classpaths only exist once AGP created them.
androidComponents {
    onVariants { variant ->
        val graph = variant.runtimeConfiguration.incoming.resolutionResult.rootComponent
        when (variant.buildType) {
            "release" -> {
                verifyNoMaterial.configure { releaseRuntime.set(graph) }
                verifyNoGoogleServices.configure {
                    releaseRuntime.set(graph)
                    mergedReleaseManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                }
            }
            "debug" -> {
                verifyNoMaterial.configure { debugRuntime.set(graph) }
                verifyNoGoogleServices.configure { debugRuntime.set(graph) }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyNoMaterial, verifyNoGoogleServices)
}
