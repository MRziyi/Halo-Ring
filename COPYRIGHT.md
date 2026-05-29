# Halo Ring · 环意 — Copyright & Licensing

Copyright © 2026 Zack Zhang (紫意) — <https://ziyi-zhang.vercel.app>

## Dual-licensed

Halo Ring is offered under **either** of the following two licenses:

### 1. AGPLv3 — open-source / community / personal use

Halo Ring is free software under the **GNU Affero General Public License,
version 3** (see [LICENSE](LICENSE)).

The AGPLv3 explicitly preserves four user freedoms:

  1. The freedom to run the program for any purpose.
  2. The freedom to study how the program works and adapt it.
  3. The freedom to redistribute copies.
  4. The freedom to improve the program and release improvements to the public.

It additionally requires — and this is the key clause for a project like Halo
Ring — that **anyone who modifies the source code, OR who runs a modified
version as a network service, must publish the corresponding source code under
the same AGPLv3 license**. This includes hosted services, embedded
distributions, derivative apps, and forks.

In plain language: you can use Halo Ring freely, you can fork it, you can sell
hardware that ships with it, **but** every line of code you change must also
be released to the public under the same terms. There is no permission to fork
this project privately or to embed it in a closed-source product. If the AGPL
terms don't fit your needs, see option 2 below.

### 2. Commercial license — for companies that can't comply with AGPLv3

If your use case is incompatible with AGPLv3 — for example:

  - You want to ship Halo Ring (modified or unmodified) inside a closed-source
    product;
  - You want to integrate Halo Ring into a proprietary SaaS / cloud offering
    without releasing your service's source code;
  - You want to bundle the agent or BLE-protocol code into glasses firmware
    distributed under a proprietary license;
  - You are a hardware vendor wishing to ship Halo Ring pre-installed on a
    consumer device under your own brand;

— then please **contact the copyright holder for a separate commercial
license** before integrating, redistributing, or shipping any part of this
project. See [COMMERCIAL-LICENSE.md](COMMERCIAL-LICENSE.md) for the standard
commercial terms.

**Contact:** `zackzhang0813 [at] gmail.com` · <https://ziyi-zhang.vercel.app>

## Which license applies to you?

| You are… | License | What you must do |
|---|---|---|
| An individual using Halo Ring on your own glasses | AGPLv3 | Nothing — enjoy it |
| A developer hacking on / forking Halo Ring on GitHub | AGPLv3 | Publish your fork under AGPLv3; keep this copyright notice intact |
| A researcher / academic project | AGPLv3 | Cite the project; publish modifications |
| A non-profit ARG / event using Halo Ring as-is | AGPLv3 | Nothing extra |
| A company embedding Halo Ring in a product you sell | Commercial | Contact for commercial license |
| A SaaS / cloud service running modified Halo Ring | Commercial | Contact for commercial license (AGPLv3 §13 network-use clause applies otherwise) |
| Glasses OEM wanting to pre-install | Commercial | Contact for commercial license |

If you're not sure which category you fall into, reach out — most personal /
community use is unambiguously AGPL-OK and free; commercial conversations
start with an email.

## Trademarks

"Halo Ring", "环意", and the Halo Ring logo are unregistered trademarks of
the copyright holder. The AGPL covers the *source code* — using the project
name and visual identity in a fork requires either obvious "fork-of" framing
(e.g. "MyFork — based on Halo Ring") OR a separate trademark license. This
mirrors how Linux, Firefox, and PostgreSQL handle their marks.

## Documentation & the reverse-engineered protocol spec

The design docs in [Doc/](Doc/) — including the reverse-engineered BLE protocol
specification [Doc/09-r08-ble-protocol-spec.md](Doc/09-r08-ble-protocol-spec.md)
— are creative works licensed under the **same AGPLv3** as the code. Raw
interoperability facts (a byte value, an opcode number) are not themselves
copyrightable, but the spec's **specific expression** — its selection,
arrangement, wording, examples, and the particular documented constants — is an
original work. Reproducing the specification, or distributing software derived
from it, carries the same copyleft obligation as the code: publish your
corresponding source under AGPLv3, or obtain a commercial license. The published
spec also contains verification provenance that lets the author distinguish an
independent re-implementation from a copy of this work.

## Third-party components

Halo Ring links against third-party **libraries** at build time, each under its
own license: AndroidX / Jetpack Compose, Kotlin coroutines, BouncyCastle, a
prebuilt BoringSSL (consumed via Prefab), and ZXing; the RayNeo flavor
additionally links the RayNeo **Mercury** AR SDK (`app/libs/mercury-release.aar`).

The reverse-engineered QRing R08 BLE protocol is **original, first-hand work** —
no other application's research or source was used as a reference. No third-party
application source code is incorporated into this repository.

## Why this license

I want Halo Ring to remain genuinely free for everyone — and also genuinely
free from being silently absorbed into a closed-source product. The AGPL is
the strongest copyleft license maintained by the FSF; pairing it with an
optional commercial license lets companies that need different terms come
have a conversation instead of either skipping the project or quietly
violating its terms. This is the same pattern used by MongoDB (pre-SSPL),
Mautic, Ghost, Plausible Analytics, and Bitwarden — chosen because it works.
