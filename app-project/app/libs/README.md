# app/libs/ — vendor SDK drop

The **rayneo** flavor depends on the RayNeo Mercury SDK AAR for binocular rendering, temple-touchpad
gestures, and 佩戴检测 (wear detection). Mercury is RayNeo's openly-distributable Android ARDK
(reference: https://rayneo.gitbook.io/rayneo-devdoc/x-xi-lie/android-kai-fa), so the AAR is
**committed directly to this repo**:

- [`mercury-release.aar`](mercury-release.aar) — pinned version drop.

The build references it from [`app/build.gradle.kts`](../build.gradle.kts) under
`rayneoImplementation(files("libs/mercury-release.aar"))`. CI builds pick it up unchanged.

To upgrade: replace this file with a newer release-AAR from RayNeo's developer portal, re-run
`./gradlew :app:assembleRayneoDebug`, and commit the new binary. Bump the SDK version in
[`Doc/03-target-platforms.md`](../../../Doc/03-target-platforms.md) §2.3 if the surface changed.

The **rokid** flavor does not need it.
