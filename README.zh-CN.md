<div align="center">

<img height="120" src="https://raw.githubusercontent.com/yinghuajimew/yhjmew.no.ore.ui/main/app/src/main/res/drawable-xxhdpi/ic_launcher.png">

<h1 align="center">去 oreUI 打包</h1>

<p>从 Minecraft 基岩版安装包中移除 oreUI、恢复 jsonUI，还能一键打个共存版。</p>

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

[English](./README.md) · 中文 · [更新日志](./CHANGELOG.zh-CN.md)

</div>

<br/>

> [!WARNING]
> 这是**非官方**工具，会对 Minecraft 安装包重新打包并重新签名。
> 由它引起的任何问题请不要上报给 Mojang 或微软，也不要用在你不舍得丢的账号上。

> [!TIP]
> **应用会替你把活干完。** 不需要电脑、不需要反编译、不需要改文件 ——
> 在应用里选个版本，等它下完，点「处理」，拿到的就是能直接装的 APK。

<details>
<summary><kbd>目录</kbd></summary>

- [✨ 它是干什么的](#-它是干什么的)
- [🚀 开始使用](#-开始使用)
- [📥 内置下载](#-内置下载)
- [⚙️ 设置项](#️-设置项)
- [🖼️ 界面截图](#️-界面截图)
- [🧩 两个 so 的区别](#-两个-so-的区别)
- [📄 更新日志](#-更新日志)
- [👥 鸣谢](#-鸣谢)
- [📜 开源协议](#-开源协议)

</details>

<br/>

## ✨ 它是干什么的

Minecraft 基岩版从 1.21 起换上了新的界面层 **oreUI**。有些设备、启动器和资源包用旧界面
**jsonUI** 反而更顺手，这个工具就是改包把它换回去。

全程在手机上完成：

| 步骤 | 做了什么 |
|---|---|
| **读取** | 从 APK 里读出 `AndroidManifest.xml`、`resources.arsc` 和 DEX |
| **改写** | 注入加载器 DEX、改写包名、移除 oreUI 相关资源 |
| **签名** | 用你选的签名文件按 v2/v3 签名 |
| **规整** | 所有 ZIP 条目时间戳统一到 2008-01-01，并按 16KB 页对齐 |

输出文件和原包放在同一个目录。

两种模式：

- **普通处理** —— 保留原包名，装上去就是替换现有的 Minecraft。
- **共存处理** —— 改掉包名（比如 `com.mojang.minecraftpe2`），改好的包能和原版**同时安装**。
  两边共用同一个存档目录。

## 🚀 开始使用

1. 到 [Releases][version-link] 下最新版 APK。
2. 装上，弹出权限时给「所有文件访问权限」—— 读原包、把改好的包写回
   `/storage/emulated/0/Download/` 都要靠它。
3. 手上有安装包就点「普通处理」或「共存处理」去选；没有就点「下载游戏」。
4. 等日志提示完成，安装生成的那个 APK。

> [!IMPORTANT]
> **要求：** Android 6.0+、arm64-v8a、Minecraft 基岩版 **1.21 及以上**。
> 更早的版本没有对应的 so 特征码，改了也用不了。

## 📥 内置下载

不用自己去到处找安装包。

- **只列正式版** —— 测试版没有对应特征码，下了也没用。
- **只显示 1.21 及以上**，原因同上。
- **只走 OneDrive** —— 其余网盘要么得登录，要么只能在它自家 App 里下。
- 下载目录是**公共目录** `/storage/emulated/0/Download/Minecraft_oreUI/`，文件管理器找得到，
  别的工具也能直接用。
- 支持**暂停、继续、删除**，而且下完可以在**下载列表里直接点「处理」**，
  不用再跑回文件选择器把同一个包找一遍。
- **分段下载**。网盘支持 HTTP `Range`，所以文件会切成几条连接并行拉。
  这是提速最大的一块 —— 一个 700MB 的包单连接要半小时，分段之后几分钟。
- **线程数可以自己调**：设置里有 `1 / 2 / 4 / 8 / 16` 预设，最后一个是自定义（1~16）。
  默认 4 条。填 1 就是不分段。

进度在应用里能看，通知栏也能看，展开通知还能暂停/继续。

> [!NOTE]
> 为什么默认 4 条？连接数不是越多越快。网盘会限流，段数再多下去，
> 多出来的握手和被限流的代价反而比省下的时间更高。

## ⚙️ 设置项

| 设置 | 可选 | 说明 |
|---|---|---|
| 签名 | 默认 MT / zihao_il / 自定义 `.bks` | 用哪个证书签名 |
| so | 独立版 / 带 preloader | 见下文 |
| 下载线程数 | 1 / 2 / 4 / 8 / 16 / 自定义 | 默认 4 |
| 语言 | 跟随系统 / 简体中文 / 繁體中文 / English | 默认跟随系统 |
| 更新方式 | 自动下载 / 仅提醒 | |

## 🖼️ 界面截图

<details>
<summary><kbd>📱 查看界面</kbd></summary>

<div align="center">
  <br/>
  <img src="./Screenshots/home.png" width="80%" alt="主界面">
  <br/>
  <img src="./Screenshots/downloads.png" width="80%" alt="下载列表">
  <br/>
  <img src="./Screenshots/settings.png" width="80%" alt="设置">
  <br/>
</div>

</details>

## 🧩 两个 so 的区别

真正干活的是注入进 Minecraft 进程的一个小 `.so`，有两个构建：

| | 独立版 | 带 preloader |
|---|---|---|
| 依赖 | Dobby，自带 | `pl::hook` + preloader |
| 仓库 | [ForceCloseOreUI-android](https://github.com/yinghuajimew/ForceCloseOreUI-android) | [yinghuaji-ForceCloseOreUI](https://github.com/yinghuajimew/yinghuaji-ForceCloseOreUI) |
| 说明 | 自己维护，不需要外部 `.so` | 跟随原作者，要成对使用 preloader |

两个都覆盖 **1.21.90 ~ 1.26.60**，并且按 16KB 页对齐构建，
所以天玑 9400+ 这类 16KB 页设备也能用。

## 📄 更新日志

见 **[CHANGELOG.zh-CN.md](./CHANGELOG.zh-CN.md)**。

## 👥 鸣谢

[@Yejdhi](https://github.com/Yejdhi) —— 注入器与加载器部分<br/>
[@zihao-il](https://github.com/zihao-il) —— [bbk-docs](https://github.com/zihao-il/bbk-docs)
接口文档与版本镜像<br/>
[@LeviMC](https://github.com/LiteLDev) —— 思路与方法

## 📜 开源协议

**[AGPL 3.0](./LICENSE)**。

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
