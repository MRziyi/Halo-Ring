# 15 — A-2: SPAKE2 pairing + TLS-wrapped ADB connection

> Implementation log. Originally a forward-looking plan; rewritten 2026-05-13 g once we
> started cutting code; finished 2026-05-13 h after the wizard + overlay + root-bypass paths
> all landed.
>
> **Final status**:
> - Step 1 — SPAKE2 pairing handshake ✅ verified end-to-end on OnePlus 9 Pro / Android 14 (loopback)
> - Step 2 — TLS-wrapped ADB connection (`CNXN`/`STLS` + `sync:` push) ✅ verified
> - Step 3 — `pm grant` + `app_process` startAgent ✅ agent visible at `@halo.agent` abstract socket
> - Step 4 — First-run wizard: code-entry overlay (SYSTEM_ALERT_WINDOW), state-aware sub-state CTAs, auto-detection of a11y / battery exemption ✅
> - Bonus — Persistent keypair (`AdbKeyStore` → DataStore) ✅, root-bypass shortcut for dev rigs ✅
>
> All software pieces of B12-real are now done. The only remaining verification is on real
> glasses hardware (Priority C7 / C8 in [Doc/13](13-handoff.md)).

---

## 1. What A-2 is

Android 11+ supports "Wireless ADB pairing": run `adb pair <host>:<port> <6-digit-code>` and
the device's `adbd` walks a SPAKE2 + TLS-wrapped handshake that ends with the device adding
your X.509 cert to `/data/misc/adb/adb_keys`. Once paired, `adb connect <host>:<port>` works
without re-pairing for the lifetime of that keypair.

We need the same flow but **inside the app itself**, against the device's own loopback
`adbd` — so the first-run wizard can install the agent dex without the user touching a laptop.

## 2. Where the code is

Production code (all in `app-project/app/src/main/kotlin/com/halo/ring/adb/`):

| File | Role |
|---|---|
| [`AdbCrypto.kt`](../app-project/app/src/main/kotlin/com/halo/ring/adb/AdbCrypto.kt) | RSA-2048 keypair, BouncyCastle self-signed X.509 cert, ANDROID! legacy pubkey encoder |
| [`AdbMdnsDiscovery.kt`](../app-project/app/src/main/kotlin/com/halo/ring/adb/AdbMdnsDiscovery.kt) | `NsdManager` wrapper that resolves `_adb-tls-pairing._tcp` / `_adb-tls-connect._tcp` to `host:port` |
| [`NativeSpake2.kt`](../app-project/app/src/main/kotlin/com/halo/ring/adb/NativeSpake2.kt) | Kotlin wrapper over the JNI SPAKE2 shim |
| [`AdbPairingClient.kt`](../app-project/app/src/main/kotlin/com/halo/ring/adb/AdbPairingClient.kt) | The full pairing handshake (TLS 1.3 + RFC 5705 EKM + SPAKE2 + HKDF + AES-GCM peer info) |
| [`AdbConnection.kt`](../app-project/app/src/main/kotlin/com/halo/ring/adb/AdbConnection.kt) | TLS-wrapped ADB client: `CNXN`/`STLS` handshake, `sync:` push, `exec:` shell |
| [`AdbBootstrap.kt`](../app-project/app/src/main/kotlin/com/halo/ring/adb/AdbBootstrap.kt) | Public surface the wizard calls: `pairWithCode` → `connectTo` → `pushAgentDex` → `grantWriteSecureSettings` → `startAgent` |
| [`PairingTestReceiver.kt`](../app-project/app/src/main/kotlin/com/halo/ring/adb/PairingTestReceiver.kt) | Debug-only `am broadcast` entry point — see §5 |
| [`src/main/cpp/spake2_jni.cpp`](../app-project/app/src/main/cpp/spake2_jni.cpp) | JNI shim that exposes BoringSSL's 4 `SPAKE2_*` symbols to Kotlin |
| [`src/main/cpp/CMakeLists.txt`](../app-project/app/src/main/cpp/CMakeLists.txt) | Pulls a prebuilt BoringSSL static library via Prefab |

Reference (NOT in this repo, third-party copyright):

```
/Users/Zack/Code/Projects/R08-dev/refs/r08remote-decompiled-v2/sources/com/ring/r08remote/adb/
  AdbPairingClient.java   ~800 lines, jadx-decompiled
  AdbConnection.java      ~800 lines, jadx-decompiled
```

## 3. The journey through SPAKE2 — three pivots before something worked

The SPAKE2 step looked like the hardest part on paper and ended up being the hardest in
practice — but for unexpected reasons. The protocol math itself is fine; **library plumbing
was the problem**.

### Pivot 1 — `spake2-java` (pure Java port of BoringSSL's SPAKE2)

First attempt: `implementation("com.github.MuntashirAkon.spake2-java:spake2-java:2.2.1")`. Pure
Java, no NDK. The 32-byte SPAKE2 message exchange round-tripped fine. AES-GCM decrypt of the
server's peer info failed every time with "mac check in GCM failed."

Root cause: [MuntashirAkon/spake2-java#1](https://github.com/MuntashirAkon/spake2-java/issues/1)
— an open, unfixed bug where Alice and Bob don't always derive the same shared key. The
maintainer attributes it to bugs in the upstream EdDSA-Java group operations the port depends
on. Deterministic failure for our parameter set; retrying doesn't help. **Abandoned.**

### Pivot 2 — JNI `dlopen` of Android's system `libcrypto.so`

Android ships BoringSSL as `/apex/com.android.conscrypt/lib64/libcrypto.so` and it exports
the 4 `SPAKE2_*` symbols (`SPAKE2_CTX_new`, `_free`, `_generate_msg`, `_process_msg`). The
plan was a tiny ~7 KB JNI shim that `dlopen`s libcrypto and calls those symbols — same code
adbd itself uses, no need to vendor any source.

Problem: Android's linker namespace isolation (since Android 7) blocks apps from `dlopen`'ing
system libraries. The escape hatch is `android_dlopen_ext` with the `com_android_conscrypt`
exported namespace handle — except `android_get_exported_namespace`, the function that
returns that handle, lives in `libdl.so` under symbol version `LIBC_PLATFORM`. That version
is **reserved for platform code**; apps can't link against it.

We tried four ways to reach the symbol:
- `dlsym(RTLD_DEFAULT, "android_get_exported_namespace")` → null
- `dlsym(dlopen("libdl.so"), ...)` → null (libdl.so on Android is a stub backed by the linker)
- Weak `extern __attribute__((weak))` declaration → null at runtime
- `--unresolved-symbols=ignore-in-object-files` link flag → null at runtime

All four are blocked by the same symbol-version restriction. **Abandoned.** This dead end
took a couple of hours of dlopen tomfoolery to confirm, but the lesson is durable: an app
cannot reach Conscrypt's libcrypto, period.

### Pivot 3 — Statically linked BoringSSL via the vvb2060 Prefab AAR ✅

The mechanism Shizuku and similar tools use: bundle a prebuilt BoringSSL static archive into
your own `.so`. There's an off-the-shelf Prefab AAR for exactly this:

```kotlin
// app/build.gradle.kts
android.buildFeatures.prefab = true
dependencies {
    implementation("io.github.vvb2060.ndk:boringssl:20250114")
}
```

```cmake
# app/src/main/cpp/CMakeLists.txt
find_package(boringssl REQUIRED CONFIG)
target_link_libraries(halo_spake2 log boringssl::crypto_static)
```

```cpp
// app/src/main/cpp/spake2_jni.cpp
#include <openssl/curve25519.h>   // SPAKE2_CTX_new / generate_msg / process_msg
```

That's it. Five lines of plumbing. The resulting `libhalo_spake2.so` is ~830 KB stripped per
ABI — bigger than the ideal 7 KB, but the algorithm-level correctness is worth far more than
the bytes. **End-to-end pairing verified** on OnePlus 9 Pro / Android 14: TLS handshake →
SPAKE2 message exchange → HKDF-SHA256 → AES-GCM peer info → server's peer info decrypts OK
→ adbd added our key to `adb_keys`.

### Why not just port BoringSSL's SPAKE2 to Kotlin?

Considered briefly. ~500 lines of crypto code (SPAKE2 + Ed25519 + SHA-512 primitives). High
audit cost for an algorithm that's already in a vetted C library shipped on every Android
device. Not worth it when the Prefab AAR exists.

## 4. The TLS-connect blockers we hit (and fixed)

> Originally one section, now four — the connect path had multiple landmines, each invisible
> until the one before it was cleared.

After pairing succeeds, `AdbConnection.connect()` does:

1. Plain TCP to `host:port` (`port` = mDNS-resolved `_adb-tls-connect._tcp`).
2. Send plain `CNXN` (`A_VERSION=0x01000001`, `MAX_PAYLOAD=262144`, banner `"host::features=cmd,shell_v2"`).
3. Receive `STLS` (version `0x01000000`).
4. Echo `STLS` back.
5. Wrap socket in `SSLSocket` with PKCS12 keystore containing `(keyPair, selfSignCert(keyPair))`. Trust-all server certs (adbd's cert is self-signed and not in any chain).
6. `startHandshake()` — **succeeds** (`TLSv1.3, TLS_AES_128_GCM_SHA256`).
7. Read next message — **EOF**. adbd has closed the socket.

The TLS handshake completing means adbd's TLS layer is happy with us. The post-TLS hangup
means adbd's *auth layer* rejected our certificate. AOSP's `transport.cpp::on_stls_negotiated`
does this:

```cpp
if (TlsAuthVerify()) send_connect(this);
else                 close;
```

`TlsAuthVerify` extracts the RSA pubkey from our X.509 cert, re-encodes it as the legacy
ANDROID! `RSAPublicKey` struct, base64-encodes that, and looks for a matching prefix in
`/data/misc/adb/adb_keys`. We added our key there during pairing using `AdbCrypto.encodeAdbPublicKey`.
If our encoder produces *different* bytes than adbd's re-encoder, the match fails.

### The bug

[`AdbCrypto.encodeAdbPublicKey`](../app-project/app/src/main/kotlin/com/halo/ring/adb/AdbCrypto.kt#L73) computes the Montgomery `rr` field as:

```kotlin
val rr = BigInteger.ONE.shiftLeft(2048).mod(modulus)   // 2^2048 mod n
```

AOSP's encoder (`android_pubkey_encode`, [source](https://android.googlesource.com/platform/system/core/+/refs/heads/main/libcrypto_utils/android_pubkey.cpp))
defines `rr = R² mod n` where `R = 2^(modulus_bits)`. For a 2048-bit modulus, `R = 2^2048`
and `R² = 2^4096`. So `rr` should be `2^4096 mod n`, not `2^2048 mod n`.

Pairing succeeded despite the wrong `rr` because the pair flow only checks that the AES-GCM
peer-info decrypts — it doesn't inspect the pubkey bytes adbd stored. adbd writes whatever
we sent into `adb_keys`. But on the next connect, adbd computes `rr` *correctly* from the
cert's modulus, so its base64 doesn't match the wrong-`rr` base64 we stored. Auth fails.

### Fix 1 — `rr = 2^4096 mod n`

```diff
-    val rr = BigInteger.ONE.shiftLeft(2048).mod(modulus)
+    val rr = BigInteger.ONE.shiftLeft(4096).mod(modulus)
```

After fix 1, the connect path got further but TLS still failed silently.

### Fix 2 — Force the client cert via a `ForcedAliasKeyManager`

Conscrypt's default `X509KeyManager` returns `null` from `chooseClientAlias` when the
server's TLS 1.3 `CertificateRequest` has no acceptable-CA filter — which is exactly what
adbd sends (it authenticates via `adb_keys`, not PKIX). When the manager returns null, no
client cert is presented, adbd hits `PEER_DID_NOT_RETURN_A_CERTIFICATE`, and closes the
socket the instant the TLS handshake completes.

Wrapping the manager so the alias defaults to our single known `"adbkey"` entry whenever
the delegate returns null fixes this. See `ForcedAliasKeyManager` inside
[`AdbConnection.kt`](../app-project/app/src/main/kotlin/com/halo/ring/adb/AdbConnection.kt).
The decompiled v2 reference includes the same workaround under the name `DebugX509KeyManager`.

### Fix 3 — Filter stale `CLSE` frames in `openStream`

After our first `sync:` stream closed (we sent CLSE), adbd echoed its own CLSE back. The
next `openStream("exec:pm grant ...")` then read that stale CLSE as the OPEN reply and
mis-reported "stream open failed".

`openStream` now skips any reply whose `arg1 != local`, where `local` is the new stream's
client-side id. adbd echoes our local-id in `arg1` on legitimate OKAY/CLSE for THIS stream,
so the filter is precise:

```kotlin
while (true) {
    val reply = readMessage() ?: return null
    if (reply.arg1 != local) continue   // stale frame from a closed stream
    return when (reply.cmd) {
        CMD_OKAY -> Stream(local, reply.arg0)
        else -> { Log.e(TAG, "OPEN($service) → 0x${reply.cmd.toString(16)}"); null }
    }
}
```

### Fix 4 — Use `shell:` not `exec:` for the agent spawn

`exec:CLASSPATH=… app_process … &` returned cleanly but the agent process died within
seconds. We verified the same command run via `adb exec-out` (USB transport) keeps the
agent alive — but via our wireless TLS transport it died. The wireless-adbd implementation
appears to track and kill processes spawned from `exec:` streams when those streams close,
defeating both `nohup` and `setsid`. The `shell:` service spawns a pty-attached shell that
survives stream close → backgrounded children inherit the surviving shell, then we
`setsid` them out of its session and they persist.

```kotlin
val cmd = "setsid sh -c 'CLASSPATH=$AGENT_DEX_PATH exec app_process /system/bin " +
        "--nice-name=halo-agent com.halo.ring.agent.Main' " +
        "</dev/null >/data/local/tmp/halo-agent.log 2>&1 &"
val out = conn.exec(cmd, service = "shell")   // not "exec"!
```

### Fix 5 — Tolerate vendor `pm grant` lockdowns

OnePlus (and reportedly Xiaomi) strip `GRANT_RUNTIME_PERMISSIONS` and `MANAGE_APP_OPS_MODES`
from the `shell` uid as part of their OEM hardening. `pm grant` from `shell:` returns a
`SecurityException`. Stock AOSP (Rokid YodaOS, RayNeo AIOS, Pixel) is fine.

The bootstrap treats `pm grant` as best-effort: failure is logged but doesn't abort the
rest of the chain. The grant is only needed to let the app later toggle wireless debugging
itself — the agent itself doesn't depend on `WRITE_SECURE_SETTINGS`.

## 5. Testing without UI — `PairingTestReceiver`

Driving the wizard manually for each iteration is painful. The receiver lets the host machine
fire the full bootstrap from a single `am broadcast`:

```bash
# Pair only
adb shell am broadcast \
  -n com.halo.ring.rokid/com.halo.ring.adb.PairingTestReceiver \
  -a com.halo.ring.TEST_PAIR \
  --es host 127.0.0.1 --ei port <pair-port> --es code <6-digit>

# Full bootstrap: pair, then TLS-connect, push agent dex, pm grant, start agent
adb shell am broadcast \
  -n com.halo.ring.rokid/com.halo.ring.adb.PairingTestReceiver \
  -a com.halo.ring.TEST_PAIR \
  --es host 127.0.0.1 --ei port <pair-port> --es code <6-digit> \
  --ei connectPort <tls-connect-port>
```

Watch with:

```bash
adb logcat -s AdbPairingClient:* PairTestRcv:* AdbBootstrap:* AdbConnection:* Spake2Jni:*
```

The receiver is registered with `tools:node="remove"` in the release manifest — debug only.

### Finding the connect port without leaving the device

Settings → "Wireless debugging" shows the LAN-routable connect port. From the device's own
loopback, the same port (`adbd` binds `[::]` = all interfaces). Two ways to get it:

```bash
# A. mDNS from the host (works because adbd advertises the service over wlan0)
dns-sd -B _adb-tls-connect._tcp
dns-sd -L "adb-<serial>-<random>" _adb-tls-connect._tcp

# B. /proc dump (USB-connected, no root needed)
adb shell 'cat /proc/$(pidof adbd)/net/tcp6 | awk "\$4==\"0A\" {print \$2}"'
```

## 6. SPAKE2 protocol details, for future reference

For anyone debugging the pairing flow later — the bytes that matter:

| Constant | Value | Source |
|---|---|---|
| Wire framing | `{ver:1B=1, type:1B, len:4B BE, payload}` | `pairing_connection.cpp` |
| Message types | 0 = SPAKE2 msg (32 B), 1 = peer info (8208 B encrypted) | same |
| SPAKE2 role | client = Alice, server = Bob | `pairing_auth.cpp` |
| SPAKE2 names | client = `"adb pair client\0"` (16 B), server = `"adb pair server\0"` (16 B) | same |
| Password | `pairing_code` (UTF-8) ‖ `tls_keying_material` (64 B) | same |
| TLS exporter label | `"adb-label\0"` (10 B) | `tls_connection.cpp` |
| TLS exporter length | 64 B | same |
| HKDF salt | none | `aes_128_gcm.cpp` |
| HKDF info | `"adb pairing_auth aes-128-gcm key"` (32 ASCII chars, NO NUL) | same |
| AES key length | 16 (AES-128-GCM) | same |
| AES-GCM nonce | 12 B, first 8 = LE uint64 counter, last 4 = 0 | same |
| AES-GCM counter | per-direction, monotonic starting at 0 | same |
| AES-GCM tag | 128 bits, appended | same |
| Peer info struct | 1 B `type` (0 = RSA pubkey) + 8191 B data | same |
| Peer info data | NUL-terminated `"<base64 ANDROID! pubkey> <username>\n"` | same |

Pitfalls that ate hours:

- **NUL-termination of names**: AOSP passes `sizeof("adb pair client")` to `SPAKE2_CTX_new`, which in C
  is **16** (includes the literal NUL). Pass 15 and the SPAKE2 keys diverge silently.
- **HKDF info string is NOT NUL-terminated**: AOSP passes `sizeof(info) - 1`. Including the NUL
  gives a 33-byte info, derived key differs.
- **TLS exporter label IS NUL-terminated**: `sizeof("adb-label")` in C is 10. Strip the NUL and
  the keying material differs → SPAKE2 password differs → keys differ.
- **`BigInteger.toByteArray()` adds a sign byte** for positive integers with the high bit set.
  A 2048-bit modulus comes back as 257 bytes (0x00 + 256-byte magnitude). The legacy ADB pubkey
  encoder must take the last 256 bytes only. `AdbCrypto.toLittleEndianWords` handles this.
- **Modern adbd ignores the CRC field** in ADB packet headers; sending 0 is fine. The magic
  field (`cmd XOR 0xFFFFFFFF`) IS checked.

## 7. The wizard — three paths through pairing

Once the wire protocol worked, getting a USABLE pair flow from the user's seat was its own
project. Three paths exist; the wizard picks one at runtime.

### 7.1 Root bypass (dev rigs)

`RootBypass.installKey()` writes our pubkey straight into `/data/misc/adb/adb_keys` via
`su`, skipping the SPAKE2 dialog entirely. Tried first on every START PAIRING tap — if `su`
returns 0 within ~1s and the file write succeeds, no pairing code is ever needed.

This unblocked the OnePlus loopback testing where the system pairing dialog auto-closes
the moment the user leaves Settings (taking adbd's pair port with it), making the
overlay/manual paths unusable. **Not the production code path** — real glasses end-users
aren't expected to have root.

### 7.2 System overlay (production path on glasses)

`AdbPairingOverlay` is a `SYSTEM_ALERT_WINDOW`-hosted Compose panel that sits on top of the
system Settings pairing dialog. The user reads the 6-digit code off the dialog, types it
into our overlay's text input, taps PAIR; the bootstrap runs in-process and updates the
overlay's status text in place.

Why an overlay rather than an in-app dialog: Android tears down the pairing-service mDNS
advertisement (and on phones, the entire pairing port) the moment the system dialog loses
focus. To discover the port via mDNS or pair against it at all, our input UI must coexist
on-screen with the system dialog.

Key window flags (in `AdbPairingOverlay.buildLayoutParams`):

- `TYPE_APPLICATION_OVERLAY` — required since Android 8
- `FLAG_NOT_TOUCH_MODAL` — **critical**; without it the focusable overlay window eats every
  touch on the screen (including ones outside its visible bounds), breaking the home screen
  and the Settings dialog we're floating over
- `FLAG_ALT_FOCUSABLE_IM` — lets the IME bind to our text field even though the parent
  Activity is unfocused
- Standalone `LifecycleOwner` pinned at `RESUMED` (same pattern as `HudServiceHost`) — using
  the Activity as the LifecycleOwner caused Compose to stop composing the moment the user
  switched to Settings, making the overlay look like it had vanished

### 7.3 Phone caveat — `HIDE_NON_SYSTEM_OVERLAY_WINDOWS`

On the OnePlus 9 Pro / OxygenOS, when the user taps into Settings → Wireless debugging →
"Pair with code", the system applies `HIDE_NON_SYSTEM_OVERLAY_WINDOWS` to the SubSettings
window (anti-tap-jacking security). Our overlay gets visually hidden the moment the user
enters that specific sub-screen. **Apps cannot bypass this** — it's a privileged window flag.

Vendor ROMs on AR glasses (Rokid YodaOS, RayNeo AIOS) likely don't apply this flag to
their Wireless-debugging UI, since the security threat model on a wearable is different.
We haven't verified on real glasses yet — see [Doc/13 §C7 / §C8](13-handoff.md).

On unrooted phones where the overlay gets hidden, the user has to memorise the 6-digit
code in the brief window the dialog is visible, switch back to our app, and type fast
before the dialog (and its mDNS service) close. This is suboptimal, but it's a phone-only
dev limitation — production glasses won't hit it.

### 7.4 Wizard sub-state machine

`FirstRunWizardScreen` shows AT MOST ONE primary CTA per sub-state, per Doc/08 §1 ("big
text, generous space"). For the ADB step:

| Substate | Shown CTA(s) |
|---|---|
| INTRO | OPEN SETTINGS + START PAIRING |
| RUNNING (status starts with neither `✓` nor `✗`) | none — just the progress text |
| SUCCESS (`✓ Agent running.`) | CONTINUE |
| FAILED (`✗ <reason>`) | TRY AGAIN |

The Accessibility + Battery steps similarly auto-detect granted state via `onResume` polls
and collapse to a single CONTINUE when the system permission is already granted —
no human re-tapping required after returning from the system Settings deep-link.

## 8. After A-2

The "software side" of Halo Ring is complete. Remaining items in
[Doc/13 §2 Priority C](13-handoff.md) are all on-device hardware-verification:

- C1: phase-0 protocol check against the actual R08 ring (`python3 phase0/r08_probe.py`)
- C2-C6: ring-specific tuning (dedup window, accel frames, LED behavior)
- C7-C8: Rokid + RayNeo first-launch verification. Key questions on first glasses contact:
  - Does Wireless-debugging UI auto-close the pair dialog when our app comes to foreground? (If yes, overlay path is needed exactly as on OnePlus.)
  - Does `HIDE_NON_SYSTEM_OVERLAY_WINDOWS` apply to glasses' Settings sub-screens? (We expect no — vendor ROMs don't usually carry over phone-grade anti-tap-jacking to wearables.)
  - Does mDNS discovery work in app-context on glasses? (Should — both Rokid and RayNeo are stock-ish Android 12.)
- C9: end-to-end latency p95 ≤ 100 ms validation via the LatencyLogger CSV export
- C10: cross-glasses hand-over

That's where the project graduates from "ready to test" to "field-tested".
