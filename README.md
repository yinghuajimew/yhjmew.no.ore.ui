<div align="center">

<img height="120" src="https://raw.githubusercontent.com/yinghuajimew/yhjmew.no.ore.ui/main/app/src/main/res/drawable-xxhdpi/ic_launcher.png">

<h1 align="center">No OreUI</h1>

<p>Strip OreUI from a Minecraft Bedrock APK and get classic JSON-UI back — plus a one-tap coexistence build.</p>

<!-- SHIELD GROUP -->
[![Version][version-shield]][version-link]
[![Android][android-shield]][android-link]
[![License][license-shield]][license-link]
[![Issues][issues-shield]][issues-link]

[![Stars][stars-shield]][stars-link]
[![Forks][forks-shield]][forks-link]

<!-- 社交与外部链接 -->
[![Bilibili][bilibili-shield]][bilibili-link]
[![QQ Group][qq-shield]][qq-link]
[![Telegram][tg-shield]][tg-link]

English · [中文](./README.zh-CN.md) · [Change log](./CHANGELOG.md)

</div>

<br/>

> [!WARNING]
> This is an **unofficial** tool. It repackages and re-signs a Minecraft installation package.
> Do not report problems caused by it to Mojang or Microsoft, and do not use it on accounts you care about losing.

> [!TIP]
> **The app does the work for you.** You do not need a PC, a decompiler, or a text editor —
> pick a version inside the app, let it download, tap *Process*, and you get an installable APK.

<details>
<summary><kbd>Table of contents</kbd></summary>

- [✨ What it does](#-what-it-does)
- [🚀 Getting started](#-getting-started)
- [📥 Built-in downloader](#-built-in-downloader)
- [⚙️ Settings](#️-settings)
- [🖼️ Screenshots](#️-screenshots)
- [🧩 The two native libraries](#-the-two-native-libraries)
- [📄 Changelog](#-changelog)
- [👥 Thanks](#-thanks)
- [📜 License](#-license)

</details>

<br/>

## ✨ What it does

Minecraft Bedrock (1.21+) ships a new UI layer called **OreUI**. Some devices, launchers and
resource packs work better with the old **JSON-UI**, so this tool rewrites the package to bring it
back.

Everything runs on-device:

| Step | What happens |
|---|---|
| **Unpack** | Reads `AndroidManifest.xml`, `resources.arsc` and the DEX from the APK |
| **Patch** | Injects a loader DEX, rewrites the package name, drops the OreUI assets |
| **Re-sign** | Signs with APK Signature Scheme v2/v3 using a signature you pick |
| **Normalise** | Rewrites every ZIP entry timestamp to 2008-01-01 and aligns to 16 KB pages |

Output goes to the same folder as the source APK.

Two modes:

- **Normal processing** — keeps the original package name, so it replaces the installed Minecraft.
- **Coexistence processing** — renames the package (e.g. `com.mojang.minecraftpe2`), so the patched
  build installs **alongside** the untouched one. Both share the same world folder.

## 🚀 Getting started

1. Download the latest APK from [Releases][version-link].
2. Install it and grant **All files access** when asked — it is needed to read the APK and write the
   patched one back to `/storage/emulated/0/Download/`.
3. Tap **Download game** if you do not already have an APK, or **Normal / Coexistence processing**
   to pick one from storage.
4. Wait for the log to say it is done, then install the resulting APK.

> [!IMPORTANT]
> **Requirements:** Android 6.0+, arm64-v8a, Minecraft Bedrock **1.21 or newer**.
> Anything older has no matching signature for the native library and will not be patched.

## 📥 Built-in downloader

You do not have to hunt for the APK yourself.

- **Only official releases** are listed — snapshots and betas have no matching signature, so they
  would be useless.
- **Only versions 1.21 and above** appear, for the same reason.
- **OneDrive mirror only.** The other mirrors need an account or only work inside the provider's own
  app.
- Downloads go to the **public** folder `/storage/emulated/0/Download/Minecraft_oreUI/`, so a file
  manager can find them and any other tool can use them.
- **Pause, resume and delete** are supported, and the downloaded package can be processed **directly
  from the download list** — no need to go back to the file picker.
- **Segmented download.** The mirror supports HTTP `Range`, so the file is split into several
  parallel connections. This is the single biggest speed difference; a 700 MB package that took half
  an hour on one connection finishes in minutes.
- **Thread count is yours to choose** in Settings: presets `1 / 2 / 4 / 8 / 16`, plus a custom value
  from 1 to 16. Default is 4. Setting it to 1 disables splitting entirely.

Progress is shown in the app **and** in a notification you can expand to pause or resume.

> [!NOTE]
> Why 4 by default? More connections is not always faster. The mirror throttles, and beyond a handful
> of segments the extra handshakes and throttling start to cost more than they save.

## ⚙️ Settings

| Setting | Options | Notes |
|---|---|---|
| Signature | MT default / zihao_il / custom `.bks` | Which key signs the output |
| Native library | Standalone / with preloader | See below |
| Download threads | 1 / 2 / 4 / 8 / 16 / custom | Default 4 |
| Language | System / 简体中文 / 繁體中文 / English | Follows the system by default |
| Update policy | Auto download / Notify only | |

## 🖼️ Screenshots

<details>
<summary><kbd>📱 View the app</kbd></summary>

<div align="center">
  <br/>
  <img src="./Screenshots/home.png" width="80%" alt="Home">
  <br/>
  <img src="./Screenshots/downloads.png" width="80%" alt="Download list">
  <br/>
  <img src="./Screenshots/settings.png" width="80%" alt="Settings">
  <br/>
</div>

</details>

## 🧩 The two native libraries

The patching itself is done by a small native `.so` that hooks into Minecraft's process. Two builds
exist:

| | Standalone | With preloader |
|---|---|---|
| Dependency | Dobby, self-contained | `pl::hook` + preloader |
| Repo | [ForceCloseOreUI-android](https://github.com/yinghuajimew/ForceCloseOreUI-android) | [yinghuaji-ForceCloseOreUI](https://github.com/yinghuajimew/yinghuaji-ForceCloseOreUI) |
| Notes | Maintained here, no external `.so` needed | Follows the original author, needs the preloader pair |

Both cover **1.21.90 – 1.26.60** and are built with 16 KB page alignment, so they work on devices
with 16 KB pages (Dimensity 9400+ and similar).

## 📄 Changelog

See **[CHANGELOG.md](./CHANGELOG.md)**.

## 👥 Thanks

[@Yejdhi](https://github.com/Yejdhi) — loader / injection work<br/>
[@zihao-il](https://github.com/zihao-il) — the [bbk-docs](https://github.com/zihao-il/bbk-docs) API
reference and the version mirror<br/>
[@LeviMC](https://github.com/LiteLDev) — ideas and methods

## 📜 License

**[AGPL 3.0](./LICENSE)**.

<br/>

<!-- 底部链接定义 -->

[version-shield]: https://img.shields.io/badge/Version-v2.4-369eff?style=flat-square&labelColor=black
[version-link]: https://github.com/yinghuajimew/yhjmew.no.ore.ui/releases

[android-shield]: https://img.shields.io/badge/Android-6.0%2B-3ddc84?style=flat-square&labelColor=black&logo=android&logoColor=white
[android-link]: https://developer.android.com

[issues-shield]: https://img.shields.io/github/issues/yinghuajimew/yhjmew.no.ore.ui?color=ff80eb&labelColor=black&style=flat-square
[issues-link]: https://github.com/yinghuajimew/yhjmew.no.ore.ui/issues

[stars-shield]: https://img.shields.io/github/stars/yinghuajimew/yhjmew.no.ore.ui?color=ffcb47&labelColor=black&style=flat-square
[stars-link]: https://github.com/yinghuajimew/yhjmew.no.ore.ui/stargazers

[forks-shield]: https://img.shields.io/github/forks/yinghuajimew/yhjmew.no.ore.ui?color=8ae8ff&labelColor=black&style=flat-square
[forks-link]: https://github.com/yinghuajimew/yhjmew.no.ore.ui/network/members

[license-shield]: https://img.shields.io/badge/license-AGPL_3.0-blue?style=flat-square&labelColor=black
[license-link]: ./LICENSE

[bilibili-shield]: https://img.shields.io/badge/Bilibili-FB7299?style=flat-square&labelColor=black&logo=bilibili&logoColor=white
[bilibili-link]: https://space.bilibili.com/

[qq-shield]: https://img.shields.io/badge/QQ_group-12B7F5?style=flat-square&labelColor=black&logo=qq&logoColor=white
[qq-link]: https://qm.qq.com/cgi-bin/qm/qr?_wv=1027&group_code=127681066

[tg-shield]: https://img.shields.io/badge/Telegram-26A5E4?style=flat-square&labelColor=black&logo=telegram&logoColor=white
[tg-link]: https://t.me/Minecraft_not_oreUI
