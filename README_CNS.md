
![BannerFinal](https://github.com/user-attachments/assets/ca82914c-e30e-406d-8d2e-487bda6263af)

<h1 align="center"> 项目状态 </h1>

<div align="center">

[![Java CI](https://img.shields.io/github/actions/workflow/status/TASEmulators/freej2me-plus/ant.yml?style=for-the-badge&label=FreeJ2ME-Plus%20Core)](https://github.com/jpcsp/jpcsp/blob/master/.github/workflows/main.yml)
[![Website](https://img.shields.io/website?url=https%3A%2F%2Fjpcsp.org%2F&style=for-the-badge&label=FreeJ2ME-Plus%20Webpage)](https://tasemulators.github.io/freej2me-plus/)
![Java version](https://img.shields.io/badge/Java-6-44cc11?style=for-the-badge&label=Minimum%20Java%20VM)
![License](https://img.shields.io/badge/license-GPLv3-red?style=for-the-badge&label=Project%20License)
![Open Issues](https://img.shields.io/github/issues/TASEmulators/freej2me-plus?style=for-the-badge)
![Last Commit](https://img.shields.io/github/last-commit/TASEmulators/freej2me-plus?style=for-the-badge)

</div>

<h1 align="center"> 下载链接 </h1>

<div align="center">

[![Nightly Releases](https://img.shields.io/github/v/release/TASEmulators/freej2me-plus?label=Bleeding%20Edge%20Builds:&style=for-the-badge)](https://github.com/TASEmulators/freej2me-plus/releases/tag/nightlies)
[![Latest Stable Release](https://img.shields.io/badge/version-v1.51-blue?label=Latest%20Stable%20Release:&style=for-the-badge)](https://github.com/TASEmulators/freej2me-plus/releases/tag/1.51)

</div>

---

# :question: 简介

### FreeJ2ME-Plus 是一个携带 Libretro 核心和 AWT 前端的 J2ME 模拟器，旨在运行于任何可以运行 Java VM 的装置上。

### 请注意：现版本并不支援在 RetroArch 前端中载入中文档名的 JAR，所以仅更新核心翻译，您需自行在上游获取原始码，而后使用该处的翻译补丁替换并编译之。

### 原作者 :
#### - David Richardson [Recompile@retropie]
#### - Saket Dandawate  [Hex@retropie]

### 現維護者:
#### - Paulo Sousa [AShiningRay]

---

# :bar_chart: 兼容清单

### 您可以在 [此处](https://tasemulators.github.io/freej2me-plus/) 查阅已测试的相容性清单。

----

# :gear: :coffee: 构建 FreeJ2ME-Plus

**为了完成编译，您需要准备 [Java JDK](https://www.java.com/zh-CN/download/) 以及 [Apache Ant](https://www.ant.org/manual/index.html) 环境。 **

>
> 在专案路径下打开控制台，执行以下命令（没错，非常简单）：
>
>```
> > ant
>```
>
> 编译结果在专案下的 `/build` 资料夹：
>
> `freej2me.jar` -> AWT 执行档，可直接双击启动。
>
> `freej2me-lr.jar` -> Libretro 核心依赖，作为『BIOS』执行 J2ME 档案，因此其必须放置在 RetroArch 的 `/system` 资料夹下。
>

**NOTE: 若您想使用 Libretro 前端，请按照以下步骤编译核心文件。 **

# :gear: :video_game: 构建 Libretro Core

### Linux

### **为了完成编译，您需要使环境可以执行 `make` 指令。 **

> 在专案路径下打开控制台，执行以下命令
>
>```
> > cd src/libretro
> > make
>```
>
>编译所得的核心档为 `freej2me_libretro.so`，请将其放置在 Libretro 前端配置档所规定的核心路径下（不同版本的 RetroArch 核心预设路径均不同，但一般是『retroarch/cores』）。
>
>`freej2me-lr.jar` 亦然，其预设路径一般是『retroarch/system』。
>

### **NOTE: 核心无法在容器/沙盒中正确工作，除非其可以呼叫同一容器或是沙盒中的 Java 执行阶段。若您使用 Flatpak 或是 Snap 之类的前端版本，请记住该点。 **
>

---

### Windows

### 为了完成编译，您需要使用 mingw 或 MSYS2 64 模拟 Linux 环境。

### 本指南使用 [MSYS2 64](​​https://www.msys2.org/)，因为它设定简单，且更接近 Linux 的语法。

>
> 一般情况下，您的所有编译工作将在 `C:\msys64\home\UserName` 下完成。
>
> 不过在此之前，我们需要安装编译所需的依赖：
>
>```
> > pacman -S mingw-w64-ucrt-x86_64-gcc
> > pacman -S make
>```
>
> 下载好专案后，将其解压到上述路径，如 `C:\msys64\home\UserName\freej2me-plus`。
>
>```
> > cd freej2me-plus/src/libretro
> > make
>```
>
>编译所得的核心档为 `freej2me_libretro.dll`，请将其放置在 Libretro 前端配置档所规定的核心路径下（Windows 版的 RetroArch 核心预设在『retroarch/cores』）。
>
> `freej2me-lr.jar` 亦然，其预设路径一般是『retroarch/system』。

### Windows 核心已在 Windows 7、10 和 11 x64 上测试。

----
# :memo: AWT 前端使用指南

### 双击 freej2me.jar，即可启动 AWT 前端的 GUI。

### 您可透过 `File` 或是 拖拽，将 JAR/JAD/KJX/MSD 在前端中打开。

### 选单中也提供了多种配置项：

<img width="252" height="390" alt="image" src="https://github.com/user-attachments/assets/64737dd3-eea6-4437-b236-aeb163841f21" />
<img width="252" height="390" alt="image" src="https://github.com/user-attachments/assets/5b105d46-d83b-4d1f-9913-687d281523d4" />
<img width="254" height="390" alt="image" src="https://github.com/user-attachments/assets/95054a08-35fb-4e99-87f2-ba36a9a8629c" />

<img width="968" height="399" alt="image" src="https://github.com/user-attachments/assets/f28042c3-1d7f-47be-8942-0e5a5b2cf2b9" />

<h1> </h1>

### 您也可以使用控制台参数启动：

### `java -jar freej2me.jar 'file:///path/to/midlet.jar' fullscreen width height scale keyLayout framerate dojaversion`

### 參數簡介

- `fullscreen` :`1 = 是, 0 = 否`
- `width` :`熒幕寬度`
- `height` :`熒幕高度`
- `scale` :`視窗縮放比例，如『2』即為放大 2 倍`
- `keyLayout` :`鍵值佈局`
  - `0 -> Default`
  - `1 -> LG`
  - `2 -> Motorola/Softbank`
  - `3 -> Motorola Triplets`
  - `4 -> Motorola V8`
  - `5 -> Nokia Keyboard`
  - `6 -> Sagem`
  - `7 -> Siemens`
  - `8 -> Sharp`
  - `9 -> SKT`
  - `10 -> KDDI`
- `framerate` :`運行幀率` 
  - 一般為 '10' ~ '60'，但其可以是任何值
- `dojaversion` :`DoJa/Star API 版本`
  - `10 -> Default`
  - `20 -> DoJa 2.0 & International 1.5`
  - `30 -> DoJa 3.0 & International 2.5`
  - `35 -> DoJa 3.5`
  - `40 -> DoJa 4.0`
  - `41 -> DoJa 4.1`
  - `50 -> DoJa 5.0`
  - `51 -> DoJa 5.1`
  - `100 -> Star 1.0`
  - `110 -> Star 1.1`
  - `120 -> Star 1.2`
  - `130 -> Star 1.3`
  - `150 -> Star 1.5`
  - `200 -> Star 2.0`

<h1> </h1>

### 路径之外的任何参数均为可选项。

### _Notes:_

**在 Microsoft Windows 下使用时，请注意路径需要额外的 `/`。如，`C:\path\to\midlet.jar` 应为 `file:///C:\path\to\midlet.jar`。 **

**FreeJ2ME 将 Rms 和 Config 储存模拟器的同路径下， Config 中的设定值优于命令列所传递的值。 **

---

# :mag: 模组和依赖

- #### JLayer(MPEG Player): - LGPLv2.1 License, compatible with GPLv3

- #### libsdl4j: zlib License, compatible with GPLv3

- #### ObjectWeb's ASM: BSD 3-Clause License, not directly compatible with GPLv3, but can be used as long as the original license is published alongside GPLv3 (check the 'License' tab)

- #### Libretro's API: MIT License, compatible with GPLv3

---

# :busts_in_silhouette: 協助

### If you're a developer:

  1) Open an Issue
  2) Try solving that issue
  3) Post on the Issue if you have a possible solution
  4) Submit a PR implementing the solution

### If you're an user:

  1) Open an Issue
  2) Explain it in as much detail as you can (FreeJ2ME-Plus version, jar used, md5 hash, as well as the issue with logs and images if possible)
  3) Post a save file close to where the issue manifests, or note the steps required to reproduce it