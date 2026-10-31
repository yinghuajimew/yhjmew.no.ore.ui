# Changelog

English · [中文](./CHANGELOG.zh-CN.md) · [Back to README](./README.md)

Versions are listed newest first. Only released versions appear here; work in progress lives in the
commit log.

---

## 2.4

The big one: the app now fetches the game for you, and the patching side got a lot sturdier.

### Added

- **Built-in version picker with download.** Browse official Bedrock releases, see sizes and
  architecture, and download straight into `/storage/emulated/0/Download/Minecraft_oreUI/` — a public
  folder, not the app's private one.
- **Segmented download.** The OneDrive mirror supports HTTP `Range`, so the package is split across
  several parallel connections instead of one. On a 700 MB package this is the difference between
  half an hour and a few minutes.
- **Pause / resume / delete**, from the app *and* from an expandable notification with a pause or
  resume button.
- **Process straight from the download list.** A finished package gets a *Process* button that asks
  normal vs. coexistence and starts immediately — no round trip through the document picker.
- **Configurable thread count.** Presets `1 / 2 / 4 / 8 / 16` plus a custom 1–16, default 4.
  Setting 1 disables splitting entirely.
- **English / 简体中文 / 繁體中文**, following the system locale by default.
- **Coexistence build** can now be installed alongside the untouched game.

### Fixed

- **Progress stuck at 0% and the notification showing a spinner forever.** The version probe hits
  the mirror's rate limiter (`429`) very easily — two `bytes=0-0` requests in the same second and the
  second one is rejected. Since the total size was unknown, the percentage had a zero denominator and
  the progress bar fell back to indeterminate. The probe now retries with backoff, honours
  `Retry-After`, and — if it still fails — reads the real size out of the first `Content-Range` it
  sees and re-plans the segments around it without discarding downloaded bytes.
- **"Merging files…" stayed in the notification after the download finished.** The foreground
  notification was removed *after* the done notification had replaced it, which took the new one with
  it.
- **Resuming could download the wrong version.** Resuming `1.26.52.3` looked the version up with a
  fuzzy query that returns the whole `1.26` series and took the first result — which is `1.26.60.4`.
  Lookups are now exact-match only.
- **0-byte output when the mirror ignored `Range`.** The single-segment path computed a segment length
  of zero.

### Changed

- The **Download game** button moved out from between the two process buttons; it now sits on its own
  row, right-aligned above **Coexistence processing**.
- Both native libraries rebuilt with **16 KB page alignment** (Dimensity 9400+ and similar).
- `update.json` is valid JSON again (changelogs contained raw newlines).
- The standalone library no longer crashes on every second launch, and its config recovers properly
  after a Minecraft update.

### Native

- Signature coverage extended to **1.21.90 – 1.26.60**; `1.26.60` is the first fallback entry.
- Standalone: replaced the synchronous hook with a background thread plus self-verification.
- With preloader: added dual-channel logging, native crash dumps, a logcat snapshot and a device info
  header.

---

## 2.3

- 16 KB page alignment for the standalone library.
- Updated signatures to cover 1.26.50.
- Removed the unused `AppTheme` styles.

## 2.2

- Added the download page with OneDrive support.
- Added 繁體中文 and English.

## 2.1

- Rewrote the repacking pipeline: streaming rewrite, signature v2/v3, unified 2008-01-01 timestamps.

## 2.0

- First public build: normal and coexistence processing, on-device repacking and signing.
