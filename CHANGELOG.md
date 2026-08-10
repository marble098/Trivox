# Changelog

## Batch Real Delay false-negative fix and UI polish — 2026-08-11

- Fixed "Real All" (batch) profile testing reporting healthy configs as failed/no-result, especially with the Turbo Real Delay profile. `BatchRealDelayRunner` was building each grouped profile's Xray config without the bootstrap-resolved IP that the single-profile Real Delay path already injects, so hostname-based servers paid a live DNS-over-TCP lookup inside the shared Xray process at connect time instead of a pre-resolved address. Under Turbo's tight per-probe timeout, that extra round trip was enough to time out configs that connect and test fine individually. Batch runs now resolve every profile's hostname in parallel (cached, IP-literal-fast) before starting the group's Xray process, matching the single-profile path.
- Added a touch more padding to the collapsible page title used on every tab (Home/Configs/Subscriptions/Tools/Settings), and gave Settings' buttons and toggle rows the app's rounded continuous-corner shape and roomier touch padding for visual consistency with the rest of the design system.

## D8/R8 Kotlin metadata build fix — 2026-08-10

- Pinned Kotlin to `2.3.21` (was `2.4.10`). AGP `8.13.2` bundles R8 `8.13.19`, which only supports Kotlin metadata up through the 2.3 series; building with Kotlin 2.4.x against that R8 caused release/minified builds (including the direct-download APK job) to fail with repeated `D8: Unexpected error during rewriting of Kotlin metadata` / `Should never be called` warnings and errors across nearly every Kotlin class.

## Compose Material 3 migration — 2026-08-08

- Removed the application `res/layout` layer and enabled Jetpack Compose Material 3.
- Rebuilt the primary navigation around five bottom tabs: Home, Configs, Subscriptions, Tools and Settings.
- Added connection/profile health overview, favorites-only filtering, centralized quick tools and a discoverable Android 13+ Quick Settings tile request without removing existing actions.
- Migrated per-app routing to direct Compose state and removed its full-list RecyclerView refresh path.
- Added a lifecycle-safe programmatic compatibility bridge for complex existing controllers so networking/core behavior remains stable during UI migration.
- Updated GitHub Actions with a dedicated Compose migration verifier and removed obsolete one-shot XML patch workflows.
- Added a guarded repository replacement script with ZIP validation, backup tags, offline validation, optional Gradle verification and protected-branch fallback.
- Fixed the technical audit fallback so generated Python bytecode is not falsely treated as tracked when the source is validated outside a Git checkout.

## 0.1.0 — 2026-08-02

- Initial compact Android client architecture.
- Official libXray `v26.7.28` adapter with validated proxy and native Android TUN modes.
- Common Xray share-link, JSON, subscription, file, clipboard, and share imports.
- List/grid configuration management, bounded testing, per-app routing, DNS settings, session accounting, diagnostics, Bash/PowerShell wizards, multi-ABI builds, and GitHub Actions.
