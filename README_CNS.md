
![BannerFinal](https://github.com/user-attachments/assets/ca82914c-e30e-406d-8d2e-487bda6263af)

<h1 align="center"> 專案狀態 </h1>

<div align="center">

[![Java CI](https://img.shields.io/github/actions/workflow/status/TASEmulators/freej2me-plus/ant.yml?style=for-the-badge&label=FreeJ2ME-Plus%20Core)](https://github.com/jpcsp/jpcsp/blob/master/.github/workflows/main.yml)
[![Website](https://img.shields.io/website?url=https%3A%2F%2Fjpcsp.org%2F&style=for-the-badge&label=FreeJ2ME-Plus%20Webpage)](https://tasemulators.github.io/freej2me-plus/)
![Java version](https://img.shields.io/badge/Java-6-44cc11?style=for-the-badge&label=Minimum%20Java%20VM)
![License](https://img.shields.io/badge/license-GPLv3-red?style=for-the-badge&label=Project%20License)
![Open Issues](https://img.shields.io/github/issues/TASEmulators/freej2me-plus?style=for-the-badge)
![Last Commit](https://img.shields.io/github/last-commit/TASEmulators/freej2me-plus?style=for-the-badge)

</div>

<h1 align="center"> 下載連結 </h1>

<div align="center">

[![Nightly Releases](https://img.shields.io/github/v/release/TASEmulators/freej2me-plus?label=Bleeding%20Edge%20Builds:&style=for-the-badge)](https://github.com/TASEmulators/freej2me-plus/releases/tag/nightlies)
[![Latest Stable Release](https://img.shields.io/badge/version-v1.51-blue?label=Latest%20Stable%20Release:&style=for-the-badge)](https://github.com/TASEmulators/freej2me-plus/releases/tag/1.51)

</div>

---

# :question: 簡介

### FreeJ2ME-Plus 是一個携帶 Libretro 核心和 AWT 前端的 J2ME 模擬器，旨在運行於任何可以運行 Java VM 的裝置上。

### 請注意：現版本並不支援在 RetroArch 前端中載入中文檔名的 JAR，所以僅更新核心翻譯，您需自行在上游獲取原始碼，而後使用該處的翻譯補丁替換並編譯之。

### 原作者 :
#### - David Richardson [Recompile@retropie]
#### - Saket Dandawate  [Hex@retropie]

### 現維護者:
#### - Paulo Sousa [AShiningRay]

---

# :bar_chart: 相容清單

### 您可以在 [此處](https://tasemulators.github.io/freej2me-plus/) 查閲已測試的相容性清單。

----

# :gear: :coffee: 建置 FreeJ2ME-Plus

**為了完成編譯，您需要準備 [Java JDK](https://www.java.com/zh-CN/download/) 以及 [Apache Ant](https://www.ant.org/manual/index.html) 環境。**

>
> 在專案路徑下打开控制台，執行以下命令（沒錯，非常簡單）：
>
>```
> > ant
>```
>
> 編譯結果在專案下的 `/build` 資料夾：
>
> `freej2me.jar` -> AWT 執行檔，可直接雙擊啟動。
> 
> `freej2me-lr.jar` -> Libretro 核心依赖，作為『BIOS』執行 J2ME 檔案，因此其必須放置在 RetroArch 的 `/system` 資料夾下。
>

**NOTE: 若您想使用 Libretro 前端，請按照以下步骤编译核心文件。**

# :gear: :video_game: 建置 Libretro Core

### Linux

### **為了完成編譯，您需要使環境可以執行 `make` 指令。**

> 在專案路徑下打开控制台，執行以下命令
>
>```
> > cd src/libretro
> > make
>```
>
>編譯所得的核心檔為 `freej2me_libretro.so`，請將其放置在 Libretro 前端配置檔所規定的核心路徑下（不同版本的 RetroArch 核心預設路徑均不同，但一般是『retroarch/cores』）。
>
>`freej2me-lr.jar` 亦然，其預設路徑一般是『retroarch/system』。
>

### **NOTE: 核心無法在容器/沙盒中正確工作，除非其可以呼叫同一容器或是沙盒中的 Java 執行階段。若您使用 Flatpak 或是 Snap 之類的前端版本，請記住該點。**
>

---

### Windows

### 為了完成編譯，您需要使用 mingw 或 MSYS2 64 模擬 Linux 環境。

### 本指南使用 [MSYS2 64](https://www.msys2.org/)，因為它設定簡單，且更接近 Linux 的語法。

>
> 一般情況下，您的所有編譯工作將在 `C:\msys64\home\UserName` 下完成。
>
> 不過在此之前，我們需要安裝編譯所需的依賴：
>
>```
> > pacman -S mingw-w64-ucrt-x86_64-gcc
> > pacman -S make
>```
>
> 下載好專案後，將其解壓到上述路徑，如 `C:\msys64\home\UserName\freej2me-plus`。
>
>```
> > cd freej2me-plus/src/libretro
> > make
>```
>
>編譯所得的核心檔為 `freej2me_libretro.dll`，請將其放置在 Libretro 前端配置檔所規定的核心路徑下（Windows 版的 RetroArch 核心預設在『retroarch/cores』）。
>
> `freej2me-lr.jar` 亦然，其預設路徑一般是『retroarch/system』。

### Windows 核心已在 Windows 7、10 和 11 x64 上測試。

----
# :memo: AWT 前端使用指南

### 雙擊 freej2me.jar，即可啟動 AWT 前端的 GUI。

### 您可透過 `File` 或是 拖拽，將 JAR/JAD/KJX/MSD 在前端中打開。 

### 選單中也提供了多種配置項：

<img width="252" height="390" alt="image" src="https://github.com/user-attachments/assets/64737dd3-eea6-4437-b236-aeb163841f21" />
<img width="252" height="390" alt="image" src="https://github.com/user-attachments/assets/5b105d46-d83b-4d1f-9913-687d281523d4" />
<img width="254" height="390" alt="image" src="https://github.com/user-attachments/assets/95054a08-35fb-4e99-87f2-ba36a9a8629c" />

<img width="968" height="399" alt="image" src="https://github.com/user-attachments/assets/f28042c3-1d7f-47be-8942-0e5a5b2cf2b9" />

<h1> </h1>

### 您也可以使用控制台參數啟動：

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

### 路徑之外的任何參數均為可選項。

### _Notes:_

**在 Microsoft Windows 下使用時，請注意路徑需要額外的 `/`。如，`C:\path\to\midlet.jar` 應為 `file:///C:\path\to\midlet.jar`。**

**FreeJ2ME 將 Rms 和 Config 儲存模擬器的同路徑下， Config 中的設定值優於命令列所傳遞的值。**

---

# :mag: 模組和依賴

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