# Halo Ring · 环意 — brand assets

> **Halo Ring · 环意** · *Where the ring goes, the world moves.* / 「环之所至，意之所达」
> by Zack 紫意

## Name

| | Value |
|---|---|
| English | **Halo Ring** |
| 中文 | **环意** |
| Slogan (en) | **Where the ring goes, the world moves.** |
| Slogan (zh) | 「环之所至，意之所达」 |
| Author byline | by Zack 紫意 |
| Package ID | `com.halo.ring` (suffixed `.rokid` / `.rayneo` per flavor) |
| Open-source repo (planned) | `halo-ring` |
| Internal codename (retired) | ~~R08-Remote / R08-Dev~~ — kept only in repo path on Zack's laptop |

## Palette

| Token | Hex | Use |
|---|---|---|
| `halo_black` | `#000000` | Background — every surface, including launcher icon background |
| `halo_accent` | `#5EE08C` | The one and only chrome color — focus halo, CTA, slogans in About |
| `halo_mint_bright` | `#B8FFD4` | Light variant — used only as the bright "head" of each blade in the icon |
| `accent_dim` | `#2A5A36` | Disabled / mute-accent state |
| `halo_fg` | `#FFFFFF` | Primary text |
| `halo_mute` | `#8A8A8A` | Secondary text |
| `halo_warn` | `#FFB84D` | Yellow indicator — battery low etc. |
| `halo_bad` | `#FF7C7C` | Red indicator — disconnect / error |
| `halo_line` | `#2A2A2A` | List dividers |
| `focus_tint` | `#125EE08C` (7 % accent) | Tint over rows under focus |

No other color may ship in user-visible chrome. Per-profile color theming is explicitly NOT a
thing (Doc/08 §2). The launcher icon uses only `halo_black` + `halo_accent` + `halo_mint_bright`.

## Type

System sans-serif (Roboto on most Android). Numerals are tabular for metrics. Full type-scale
tokens in [`R08Theme.kt`](../../app-project/app/src/main/kotlin/com/halo/ring/ui/HaloRingTheme.kt).

## Icon files

| Path | What | Notes |
|---|---|---|
| [`v10a-aperture-arcs.svg`](v10a-aperture-arcs.svg) | **Master design** at 1024 × 1024 | Three 150°-arc blades at 12/4/8 o'clock heads, 120° rotational symmetry, double radial halo |
| [`v1-classic.svg`](v1-classic.svg) … [`v10b-sweep-aperture.svg`](v10b-sweep-aperture.svg) | Exploration variants | Kept for design history; only v10a is shipping |
| [`compare.html`](compare.html) | Side-by-side comparison page | Open in a browser to see all variants |
| [`../../app-project/app/src/main/res/drawable/ic_launcher_foreground.xml`](../../app-project/app/src/main/res/drawable/ic_launcher_foreground.xml) | **Android adaptive icon foreground** | VectorDrawable port of v10a at 75 % scale with boosted halo alpha (so the launcher tile reads as glowing rather than thin) |
| [`../../app-project/app/src/main/res/drawable/ic_notification.xml`](../../app-project/app/src/main/res/drawable/ic_notification.xml) | Monochrome status-bar icon | Single white annulus on transparent; Android tints automatically |
| [`../../app-project/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`](../../app-project/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml) | Adaptive icon descriptor | `background = @color/ic_launcher_background`, `foreground = @drawable/ic_launcher_foreground`, `monochrome` for Android 13+ themed icons |

## Design principles (carry forward)

1. **Pure black canvas, single green accent**, full stop. Any temptation to add a secondary color
   is a temptation to make the product visually noisier on a small AR display.
2. **OLED-first**: black pixels really are off. Don't paint backgrounds you don't need.
3. **Glow is the design language**: the green ring is always rendered with at least one radial
   halo behind it. Bare strokes look unfinished.
4. **No text in the icon, no text in the brand mark.** "Halo Ring" / "环意" is set in system type
   in the About panel, never baked into an image.
5. **Geometric purity**: the ring is a true annulus, the blades are exact 150° arcs at 120° apart.
   No artistic asymmetry — the design's interest comes from the gradient, not from drawn detail.

## When to edit the icon

Major palette / geometry changes: edit `v10a-aperture-arcs.svg` first (the master), preview in a
browser, then port the changes to the VectorDrawable. The conversion math:

| SVG (1024 viewport) | VectorDrawable (108 dp viewport) |
|---|---|
| coordinate × 0.10547 | coordinate |
| radius × 0.10547 | radius |
| 75 % shrink is already applied to the VectorDrawable | n/a |
| Opacity 0.55 = `#8C` ARGB byte | matches |

Re-build:
```bash
./gradlew :app:assembleRokidDebug
```
The build does NOT regenerate the VectorDrawable from the SVG — keep them in sync manually.
