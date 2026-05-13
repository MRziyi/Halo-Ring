// The injection agent. Compiled as a plain JVM module against the Android API jar (compileOnly so
// nothing is bundled at compile time — the framework classes are provided at runtime by the host
// Android system when launched via `app_process`).
//
// Build pipeline (now fully automated via `:agent:packageDex`):
//   ./gradlew :agent:packageDex                       → app/src/main/assets/halo-agent.dex
//   (`:app:preBuild` depends on it, so any APK build picks up the latest agent automatically.)
//
// On-device, :app pushes the dex to /data/local/tmp/ during the ADB-bootstrap wizard and starts:
//   CLASSPATH=/data/local/tmp/halo-agent.dex nohup app_process /system/bin \
//       --nice-name=halo.agent com.halo.ring.agent.Main &
//
// The process inherits shell uid (2000) — what lets it call InputManager.injectInputEvent via
// reflection (the hidden-API gate is process-level, and app_process bypasses it).
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

/**
 * Locate the Android SDK from env, then local.properties. Returns null if none found —
 * compile/d8 tasks themselves will fail loudly with a clear message at execution time. Keeping
 * this lazy lets `:core:test` run in environments without an Android SDK (e.g. minimal CI).
 */
fun resolveAndroidSdk(): String? =
    providers.environmentVariable("ANDROID_HOME").orNull
        ?: providers.environmentVariable("ANDROID_SDK_ROOT").orNull
        ?: rootProject.file("local.properties").takeIf { it.exists() }
            ?.readLines()?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")

/** Locate d8 in any build-tools subdir (newest-first). Errors at execution time only. */
fun resolveD8(): File {
    val sdk = resolveAndroidSdk()
        ?: error("Cannot find Android SDK — set ANDROID_HOME or sdk.dir in local.properties.")
    val buildToolsRoot = file("$sdk/build-tools")
    val dirs = buildToolsRoot.listFiles()?.filter { it.isDirectory }
        ?: error("No build-tools under $buildToolsRoot")
    val newest = dirs.sortedByDescending { it.name }.first()
    val d8 = File(newest, "d8")
    if (!d8.exists()) error("d8 not found at $d8 — check Android SDK install")
    return d8
}

// Kotlin stdlib jar that d8 needs to bundle into the agent dex (otherwise
// `kotlin.jvm.internal.Intrinsics` is missing at runtime and the agent crashes on startup).
val kotlinStdlibForDex by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    // android.jar — compileOnly: framework classes are provided at runtime by app_process. The
    // path is resolved at configuration time but `files(...)` is lazy: missing file is only an
    // error if a task actually tries to compile against it (so :core:test still works without an
    // SDK).
    val sdk = resolveAndroidSdk()
    compileOnly(files(sdk?.let { "$it/platforms/android-34/android.jar" } ?: "missing-android.jar"))

    // Bundled into the dex (not into the compile classpath — that's already implicit via the
    // kotlin-jvm plugin).
    kotlinStdlibForDex(kotlin("stdlib"))
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("halo.agent")
    manifest { attributes("Main-Class" to "com.halo.ring.agent.Main") }
}

/**
 * d8 the agent jar into a single `halo-agent.dex` and drop it into `:app`'s assets directory.
 * The `:app` Gradle plugin then picks it up via the normal asset-merge path and bundles it into
 * the APK so the first-run ADB wizard can push it.
 */
val agentDex = tasks.register<Exec>("packageDex") {
    group = "build"
    description = "d8 the agent jar into app/src/main/assets/halo-agent.dex"
    dependsOn(tasks.named("jar"))

    val outputDex = rootProject.file("app/src/main/assets/halo-agent.dex")
    val jarFile = layout.buildDirectory.file("libs/halo.agent.jar")
    inputs.file(jarFile)
    outputs.file(outputDex)

    doFirst { outputDex.parentFile.mkdirs() }

    // d8 wants --output to be a *directory*; it writes classes.dex into it. We then rename.
    val stagingDir = layout.buildDirectory.dir("dex-staging").get().asFile
    doFirst { stagingDir.mkdirs() }

    // Bundle the agent jar + kotlin-stdlib (resolved transitively → kotlin.jvm.internal.Intrinsics
    // etc.) so the dex is self-contained when launched via `app_process`. Without the stdlib the
    // agent crashes immediately with NoClassDefFoundError for Intrinsics on the very first
    // Kotlin-emitted null check.
    val extraJars = kotlinStdlibForDex.files.map { it.absolutePath }
    inputs.files(kotlinStdlibForDex)

    commandLine = listOf(
        resolveD8().absolutePath,
        "--output", stagingDir.absolutePath,
        "--min-api", "26",
        jarFile.get().asFile.absolutePath,
    ) + extraJars

    doLast {
        val staged = File(stagingDir, "classes.dex")
        check(staged.exists()) { "d8 did not produce $staged" }
        staged.copyTo(outputDex, overwrite = true)
    }
}
