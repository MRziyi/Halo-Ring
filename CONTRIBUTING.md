# Contributing to Halo Ring

Welcome — this is a small hardware-tinkering project; PRs and issues are appreciated. Two things
to read before substantive contributions:

1. **[Doc/13-handoff.md](Doc/13-handoff.md)** — the canonical "where we are, what's left, where
   to look" document. The §2 priority-ordered TODO list is the authoritative roadmap.
2. **[Doc/](Doc/)** — the 14-doc design specification. Doc/04 (architecture) + Doc/05
   (interaction design) are the most-referenced.

## Code conventions

- **`:core` is Kotlin/JVM only** — no Android imports allowed, so the gesture state machine stays
  trivially JVM-testable. Use the `Scheduler` interface for timers.
- **The pipeline runs on one thread.** Anything that mutates `GestureSynthesizer`,
  `InteractionRouter`, or `ModeManager` state must run on the scheduler thread (see
  Doc/13 §3.6).
- **No persistent wakelock**, no continuous BLE scanning, no continuous health-data streaming
  (Doc/06 explains why; Doc/13 §5 is the "explicitly out of scope" list).
- **One green accent, pure black background, no per-profile color theming** (Doc/08 §2).
  This is a strong design rule, not a suggestion.

## What we want PRs for

- Phase-0 probe runs against any R08 / R02 / Colmi-family ring — variant timing windows,
  accelerometer-frame layouts, LED behaviors. Even a one-line note in Doc/02 is welcome.
- ADB-over-WiFi pairing (SPAKE2 + TLS) — the missing piece is documented inline in
  [`app-project/app/src/main/kotlin/com/halo/ring/adb/AdbBootstrap.kt`](app-project/app/src/main/kotlin/com/halo/ring/adb/AdbBootstrap.kt).
- Additional `ExecutorBackend` implementations (Shizuku is half-stubbed; the inotifyd-script
  fallback skeleton is in place and needs the inotifyd shell-script side fleshed out).
- A mobile companion app for richer profile editing — currently not in scope but welcome.

## What we'd push back on

- Adding a second accent color or per-profile color theming. The brand is intentionally
  monochrome; raise it as a design discussion if you disagree.
- Replacing the `Scheduler` abstraction with direct coroutines in `:core` — keeping `:core`
  dependency-free is load-bearing for test-ability.
- Material 3 widget defaults in the UI — Doc/08 §1 spells out why we don't want them.

## Running tests

```bash
cd app-project
./gradlew :core:test                  # unit tests (no Android SDK needed)
./gradlew :app:assembleRokidDebug     # build APK (needs Android SDK + platform-34)
./gradlew :app:lintRokidDebug         # lint
```

CI is in [`.github/workflows/core-tests.yml`](.github/workflows/core-tests.yml). Currently
runs `:core:test` only — adding lint + debug-build steps is a welcome PR.

## Style

- Kotlin defaults: 4-space indent, idiomatic naming.
- Comments: prefer "why this is here" over "what this does". The architecture doc (Doc/04) is
  the place for ambient context.
- Commit messages: short imperative subject + a paragraph of motivation. See `git log` for
  examples.
