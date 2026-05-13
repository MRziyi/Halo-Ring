plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    // :core is intentionally dependency-free (just Kotlin stdlib) so it stays portable and
    // trivially unit-testable. The production Scheduler / Clock implementations live in :app.
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // runBlocking{} for tests that exercise suspend functions on the router/synthesizer.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
