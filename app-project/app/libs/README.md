# app/libs/ — vendor SDK drop point

The **rayneo** flavor depends on the RayNeo Mercury SDK AAR, which we cannot redistribute. It's
gitignored from this repo (see [`.gitignore`](../../../.gitignore)). To build the rayneo flavor
locally:

1. Download `mercury-release.aar` from the RayNeo developer portal at
   [rayneo.gitbook.io](https://rayneo.gitbook.io/).
2. Drop it here as `mercury-release.aar`.
3. Re-run the rayneo build:
   ```bash
   ./gradlew :app:assembleRayneoDebug
   ```

The Mercury SDK is used for: binocular mirroring (`BaseMirrorActivity`), temple touch-bar
gestures, and 佩戴检测 (wear detection) on the RayNeo X3 Pro. The rokid flavor does not need it.

If the AAR is missing the build fails with a clear error pointing at this file.
