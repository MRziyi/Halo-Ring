<div align="center">

<img src="Doc/brand/v10a-aperture-arcs.svg" alt="Halo Ring 环意 brand mark — three swept mint-green arcs forming an aperture" width="180" height="180" />

# Halo Ring · 环意

**Where the ring goes, the world moves.** · 「环之所至，意之所达」

*by Zack 紫意*

[English](#english) · [中文](#中文)

</div>

---

## English

Use a **QRing R08 smart ring** as a single, wireless remote for **two pairs of AR glasses** —
**Rokid Glasses** and **RayNeo X3 Pro**. Same operations, same UI, automatic hand-over by wear
state. One codebase, two product flavors.

The headline performance claim, validated on a OnePlus 9 Pro / Android 14: the resident agent
turns a ring touch into an Android `InputManager.injectInputEvent` call in **median ~5 ms** —
about 30 × faster than the `adb shell input keyevent` path that the original reference app uses.

### Get a pre-built APK (no toolchain needed)

The [Build APKs](../../actions/workflows/build-apks.yml) workflow runs on every push to `main`,
every PR, and every `v*` git tag — uploading all four APKs (`rokid-debug` / `rokid-release` /
`rayneo-debug` / `rayneo-release`) as a 14-day artifact under that run's **Artifacts** section.

- **Tagged release** → also gets a [GitHub Release](../../releases) with the same four APKs attached.
- **Bleeding edge** → grab the artifact from the latest [main run](../../actions/workflows/build-apks.yml).
- **Manual build** → trigger `workflow_dispatch` from the Actions tab.

Both flavors build clean from a fresh checkout — no SDK download or local Mercury AAR drop
required (the AAR is now committed at `app-project/app/libs/mercury-release.aar`).

### Quick start (no glasses, no ring required)

You can exercise everything except the BLE link itself on a regular Android 12+ phone:

```bash
cd app-project
./gradlew :core:test                # 206 unit tests across 18 suites, all green
./gradlew :app:assembleRokidDebug   # ~13 MB debug APK
adb install -r app/build/outputs/apk/rokid/debug/app-rokid-debug.apk
```

For the full pre-hardware test plan (foreground-service smoke test, BLE scan timeout, agent
bootstrap, PING RTT measurement, …) see **[Doc/14-pre-hardware-testing.md](Doc/14-pre-hardware-testing.md)**.

### Architecture in one paragraph

A pure-Kotlin/JVM `:core` module owns the 12-gesture state machine, the routing pipeline, and
the power policy — fully unit-testable on the JVM. `:app` provides the Android shell:
`HaloRingService` foreground service, `AndroidR08BleClient` GATT client, Compose UI, two product
flavors (`rokid` / `rayneo`). `:agent` is a tiny dex that runs as a shell-uid `app_process`, talks
to `:app` over a LocalSocket, and dispatches `InputManager.injectInputEvent` directly via
reflection — same trick as scrcpy / Shizuku. The hidden-API gate moved in Android 13+ so we probe
`InputManagerGlobal` first, fall back to `InputManager` on API ≤ 30.

Full docs in **[Doc/](Doc/)** — the README there has a reading order by audience.

### Repository layout

```
.
├── app-project/         Gradle multi-module Android project (core + app + agent)
│   ├── core/              :core   — pure Kotlin/JVM, no Android deps
│   ├── app/               :app    — Android, Compose, rokid + rayneo flavors
│   └── agent/             :agent  — injection agent, shell uid via app_process
├── Doc/                 14-doc design specification
│   ├── brand/             Brand assets — icon SVG master + style guide
│   ├── 01-overview.md     … through …
│   └── 14-pre-hardware-testing.md
├── phase0/              Python BLE probe to verify the ring's protocol
├── LICENSE              MIT
└── README.md            this file
```

### Build & install

Requirements: **JDK 17**, **Android SDK** with `platform-34` and `build-tools 34.x`, an
**Android 12+ device** or emulator for sideload testing.

```bash
# Rokid flavor (≈ 13 MB debug APK, ≈ 3.4 MB after R8)
./gradlew :app:assembleRokidDebug
adb install -r app/build/outputs/apk/rokid/debug/app-rokid-debug.apk

# RayNeo flavor (Mercury SDK AAR committed under app-project/app/libs/ — no manual drop needed)
./gradlew :app:assembleRayneoDebug

# Unit tests (no Android SDK needed)
./gradlew :core:test
```

CI: [`.github/workflows/core-tests.yml`](.github/workflows/core-tests.yml) runs the `:core` test
suite on every push / PR; [`.github/workflows/build-apks.yml`](.github/workflows/build-apks.yml)
builds all four flavor / build-type APKs and publishes them as artifacts (and as a Release on
`v*` tags).

### Status

| Layer | Status |
|---|---|
| `:core` (BLE protocol, gesture state machine, power policy, modal layer, ADB packet, conn-interval estimator, temple-action mapping) | ✅ implemented + **206 unit tests across 18 suites** |
| `:app` (UI, foreground service, agent backend, accessibility backend, ADB bootstrap skeleton) | ✅ implemented |
| `:agent` (LocalSocket + reflection-based input injection) | ✅ implemented + RTT verified on Android 14 |
| Adaptive launcher icon, splash, bilingual strings, monochrome notification | ✅ implemented |
| **ADB-over-WiFi pairing (SPAKE2 + TLS) — needed to push the agent dex onto the glasses** | ⏳ **deferred — needs the actual glasses to validate the cryptographic protocol** |
| Hardware verification on Rokid Glasses + RayNeo X3 Pro + the QRing R08 ring | ⏳ blocked on hardware |

The "what's next" punch list is at [`Doc/13-handoff.md §2`](Doc/13-handoff.md).

### Contributing

This started as a personal hardware-tinkering project (see the design-decisions trail in
[Doc/](Doc/)) and is open-sourced under MIT in case anyone else wants a similar bridge between
a smart ring and AR glasses. PRs welcome, especially:

- Phase-0 probe runs against your own R08 ring (variant dedup windows, accel-frame layouts)
- ADB-over-WiFi pairing implementation (pointers in
  [`app-project/app/src/main/kotlin/com/halo/ring/adb/AdbBootstrap.kt`](app-project/app/src/main/kotlin/com/halo/ring/adb/AdbBootstrap.kt))
- Additional `ExecutorBackend` implementations (Shizuku, fuller inotifyd-script fallback)
- Mobile companion app

Open an issue first for anything substantive — design philosophy matters more than code style
for this codebase, and the existing design docs are the source of truth. See
[`CONTRIBUTING.md`](CONTRIBUTING.md).

### Legal

MIT licensed; copyright 2026 Zack Zhang (紫意). The BLE protocol used by the QRing R08 ring is
documented from a combination of public sources
([`tahnok/colmi_r02_client`](https://github.com/tahnok/colmi_r02_client),
[`atc1441/ATC_RF03_Ring`](https://github.com/atc1441/ATC_RF03_Ring)) and direct reverse
engineering as documented in [Doc/12-research-and-references.md](Doc/12-research-and-references.md).
Protocol facts (byte values, command sequences) are not copyrightable; no third-party source code
is incorporated into this repository.

This project is not affiliated with QRing, Rokid, RayNeo, Mercury, or any other vendor mentioned
in the documentation. The "Halo Ring" name is unique to this open-source bridge; "QRing R08" is
used purely descriptively as the hardware target.

---

## 中文

把一枚 **QRing R08 智能戒指**当作 **Rokid Glasses** 与 **RayNeo X3 Pro** 两副 AR 眼镜的统一无线
遥控——一套交互、一套 UI，根据佩戴状态自动在两副眼镜之间切换连接。一份代码，两个产品 flavor。

**实测性能**（OnePlus 9 Pro / Android 14）：常驻 agent 从戒指触摸到 Android
`InputManager.injectInputEvent` 的中位往返延迟 **~5 ms**——比参考 app 走 `adb shell input keyevent`
快约 30 倍。

### 直接拿到编译好的 APK（无需任何工具链）

[Build APKs](../../actions/workflows/build-apks.yml) workflow 在每次 push 到 `main`、每个 PR、
每个 `v*` git tag 触发——把四个 APK（`rokid-debug` / `rokid-release` / `rayneo-debug` /
`rayneo-release`）作为 14 天有效的 artifact 挂到该次运行的 **Artifacts** 区。

- **打 tag 发版** → 同时在 [GitHub Releases](../../releases) 创建一条发布，附带这四个 APK。
- **最新开发版** → 直接到 [main 最近的 run](../../actions/workflows/build-apks.yml) 拿 artifact。
- **手动触发** → Actions 页用 `workflow_dispatch`。

两个 flavor 从干净 checkout 都能直接编通——不需要 SDK 下载、不需要手工 drop Mercury AAR
（AAR 现在已直接提交在 `app-project/app/libs/mercury-release.aar`）。

### 快速上手（无戒指无眼镜也能跑）

只要一台 Android 12+ 的普通手机即可验证除 BLE 通信外的整个流水线：

```bash
cd app-project
./gradlew :core:test                # 206 个单元测试，18 个 suite，全绿
./gradlew :app:assembleRokidDebug   # ~13 MB debug APK
adb install -r app/build/outputs/apk/rokid/debug/app-rokid-debug.apk
```

无硬件的完整测试方案（前台服务冒烟、BLE 扫描超时、agent 引导、PING 往返延迟测量等）见
**[Doc/14-pre-hardware-testing.md](Doc/14-pre-hardware-testing.md)**。

### 一段话讲清架构

`:core` 是纯 Kotlin/JVM 模块，承载 12 手势状态机、4 层路由管线和功耗策略，全部 JVM 可单测。
`:app` 是 Android 壳：`HaloRingService` 常驻前台服务、`AndroidR08BleClient` GATT 客户端、
Compose UI、`rokid` / `rayneo` 两个 flavor。`:agent` 是一个微型 dex，以 shell uid 通过
`app_process` 跑，与 `:app` 之间走 LocalSocket，直接反射调用
`InputManager.injectInputEvent`——和 scrcpy / Shizuku 同源方案。Android 13+ 的隐藏 API gate 改了
位置，所以先探 `InputManagerGlobal`，对 API ≤ 30 才回落到旧的 `InputManager`。

完整文档见 **[Doc/](Doc/)**——其中的 README 按受众给了阅读顺序建议。

### 仓库结构

```
.
├── app-project/         Gradle 多模块 Android 工程 (core + app + agent)
│   ├── core/              :core   — 纯 Kotlin/JVM, 无 Android 依赖
│   ├── app/               :app    — Android, Compose, rokid + rayneo 双 flavor
│   └── agent/             :agent  — 注入 agent, 以 shell uid 通过 app_process 跑
├── Doc/                 14 份设计文档
│   ├── brand/             品牌资产 — 图标 SVG 母版 + 风格指南
│   ├── 01-overview.md     … 至 …
│   └── 14-pre-hardware-testing.md
├── phase0/              Python BLE 探针——首次接触戒指时核对协议
├── LICENSE              MIT
└── README.md            此文件
```

### 构建与安装

依赖：**JDK 17**、**Android SDK** (含 `platform-34` 与 `build-tools 34.x`)、一台 **Android 12+**
设备或模拟器。

```bash
# Rokid flavor (≈ 13 MB debug APK, R8 后 ≈ 3.4 MB)
./gradlew :app:assembleRokidDebug
adb install -r app/build/outputs/apk/rokid/debug/app-rokid-debug.apk

# RayNeo flavor (Mercury SDK AAR 已直接在 app-project/app/libs/ 仓库内, 无需手动下载)
./gradlew :app:assembleRayneoDebug

# 单元测试 (无 Android SDK 也能跑)
./gradlew :core:test
```

CI：[`.github/workflows/core-tests.yml`](.github/workflows/core-tests.yml) 每次 push / PR 跑
`:core` 测试套件；[`.github/workflows/build-apks.yml`](.github/workflows/build-apks.yml) 在同样
触发时把四种 flavor × 构建类型的 APK 编出来，作为 artifact 发布（`v*` tag 时还会创建一条
Release）。

### 当前状态

| 模块 | 状态 |
|---|---|
| `:core` (BLE 协议、手势状态机、功耗策略、模态层、ADB 包格式、conn-interval 估算、temple-action 映射) | ✅ 实现完成 + **206 测试 / 18 suite** |
| `:app` (UI、前台服务、agent backend、a11y backend、ADB bootstrap 骨架) | ✅ 实现完成 |
| `:agent` (LocalSocket + 反射注入) | ✅ 实现完成 + Android 14 验证 RTT |
| 自适应图标 / 启动屏 / 双语字符串 / 单色通知图标 | ✅ 实现完成 |
| **ADB‑over‑WiFi 配对 (SPAKE2 + TLS)——把 agent dex 推到眼镜的前提** | ⏳ **暂缓——需要真机眼镜核对加密协议** |
| 硬件验证 (Rokid Glasses / RayNeo X3 Pro / QRing R08 戒指) | ⏳ 等硬件 |

下一步待办清单见 [`Doc/13-handoff.md §2`](Doc/13-handoff.md)。

### 贡献

这个项目从个人硬件折腾起步（设计决策的全过程见 [Doc/](Doc/)），以 MIT 协议开源，
欢迎想做类似"智能戒指 ↔ AR 眼镜"桥接的人参考。特别欢迎：

- 用自己手上的 R08 戒指跑 Phase-0 探针——任何 dedup 窗口、加速度计帧格式的变体都值得加进文档
- ADB‑over‑WiFi 配对实现（具体待补位置见
  [`app-project/app/src/main/kotlin/com/halo/ring/adb/AdbBootstrap.kt`](app-project/app/src/main/kotlin/com/halo/ring/adb/AdbBootstrap.kt)）
- 更多 `ExecutorBackend` 实现（Shizuku、inotifyd-script 完整版）
- 手机伴侣 app

任何实质性改动请先开 issue 讨论——这个 codebase 里设计哲学比代码风格更重要，现有设计文档是
最终依据。详见 [`CONTRIBUTING.md`](CONTRIBUTING.md)。

### 法律

MIT 协议，著作权 © 2026 Zack Zhang (紫意)。QRing R08 戒指的 BLE 协议来自公共资源
([`tahnok/colmi_r02_client`](https://github.com/tahnok/colmi_r02_client) /
[`atc1441/ATC_RF03_Ring`](https://github.com/atc1441/ATC_RF03_Ring)) 与直接逆向工程的组合，
详见 [`Doc/12-research-and-references.md`](Doc/12-research-and-references.md)。协议事实
（字节值、命令序列）不受著作权保护；本仓库未含任何第三方源码。

本项目与 QRing、Rokid、RayNeo、Mercury 及文档中提到的任何厂商均无关联。"Halo Ring"是本
开源桥接项目的独有名称；"QRing R08"仅作硬件型号的描述性引用。
