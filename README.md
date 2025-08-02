# FreeJ2ME

![Java CI](https://github.com/TASEmulators/freej2me-plus/actions/workflows/ant.yml/badge.svg)
![Libretro Cores](https://github.com/TASEmulators/freej2me-plus/actions/workflows/libretro.yml/badge.svg)

**繁體中文** | [简体中文](README_CNS.md) | [English](README_EN.md)

J2ME 模擬器，携帶 Libretro、AWT 前端.

該 Fork 目前主要維護 RetroArch 的簡體中文和繁體中文翻譯。

請先從 [Upstream](https://github.com/TASEmulators/freej2me-plus) 下載 Code，然後使用該處的中文檔案進行覆蓋並編譯。

您也可以直接在 Releases 中下載已編譯的版本。

專案原作者：
- David Richardson [Recompile@retropie]
- Saket Dandawate  [Hex@retropie]

---

## 控制說明

* `Q` `W` 鍵分別對應左右軟鍵 (Softkey)。
* `方向鍵` 用於導航。若手機按鍵佈局設為 `Standard`，方向鍵則對應 `2`、`4`、`6`、`8`。
* 數字鍵作用與預期相符，小鍵盤的數字是反向對應的（`1` `2` `3` 與 `7` `8` `9` 互相交換，如同手機的九宮格鍵盤）。
* `E` `R` 鍵可作為 `*` `#` 鍵的替代。
* `Enter` 鍵在 `Standard` 模式下作為「OK/Fire」或 `5` 鍵。
* `ESC` 鍵可呼叫設定選單。在 RetroArch 中，此功能對應的按鍵為 `F1`。
* 在 AWT 前端 (freej2me.jar) 中，`Ctrl+C` 可以擷取螢幕，`+` / `-` 可以控制視窗的縮放比例。

點擊 [此處](KEYMAP.md) 查看更多按鍵綁定資訊。

## 連結

[![Nightly Builds](https://img.shields.io/badge/Nightly_Builds-blue.svg)](https://github.com/TASEmulators/freej2me-plus/releases/tag/nightlies)
[![Screenshots](https://img.shields.io/badge/Screenshots-green.svg)](https://imgur.com/a/2vAeC)
[![Compatibility List](https://img.shields.io/badge/Compatibility%20List-orange.svg)](https://tasemulators.github.io/freej2me-plus/)

----
## 編譯説明

### 編譯 FreeJ2ME Jar

#### Linux
>
> 為了完成編譯，您需要準備 [Java 8](https://docs.azul.com/core/install/debian) 以及 [Apache Ant](https://ant.dev.org.tw/manual/install.html)。
>
> 打開終端機，執行以下操作（`freej2me/`替換為專案的絕對根路徑）：
>
>```
> > cd freej2me/
> > ant
>```
>
#### Windows
>
> 為了完成編譯，您需要準備 [Java 8](https://www.java.com/zh-TW/download/) 以及 [Apache Ant](https://ant.dev.org.tw/manual/install.html)。
>
> 打開命令提示字元，執行以下操作（`freej2me/`替換為專案的絕對根路徑）：
>
>```
> > cd freej2me/
> > ant
>```
> 編譯結果將儲存在根目錄下的 build 資料夾中：
>
> `freej2me.jar` -> 獨立的 AWT 可執行檔，目前主要的獨立版本。
> 
> `freej2me-lr.jar` -> Libretro 核心依賴。它扮演著核心的「BIOS」並負責執行 J2ME jar 檔案，因此必須放置在 RetroArch 的 `system` 資料夾下。
>
>`freej2me-sdl.jar` -> SDL2 可執行檔，支援 libTAS 和 控制器。在未來可能會成為獨立版本。
>
> 如果您想在 Libretro 中使用 jar，您仍需按照以下步驟編譯核心檔案。

### 編譯 Libretro 核心

#### Linux
> 
> 請在該專案的根路徑下打開終端，並執行以下命令：
>```
> > cd src/libretro
> > make
>```
> 該命令將在 `src/libretro/` 下建立 `freej2me_libretro.so` 檔案，這需要和上文中編譯的 `freej2me-lr.jar` 協同使用。
>
> 把 `freej2me_libretro.so` 放在 `cores/`，`freej2me-lr.jar` 放在 `system`—— 現在，RetroArch 中應該可以正常執行 J2ME 程式。
>
> NOTE: 核心無法在容器或是沙箱中工作，除非沙箱中的 Java 可以和核心響應！這是您使用 Flatpak 或者 Snap 時需要注意的。
>

#### Windows
> 
> 若想在 Windows 上編譯核心，您需要使用 mingw 或 MSYS2 64 模擬 Linux 環境。
>
> 本指南使用 MSYS2 64，因為其設定簡單，且更接近 Linux 的語法。
>
> 安裝 [MSYS2-x86_64](https://www.msys2.org/)。常規情況下，您的所有編譯工作將在 `C:\msys64\home\UserName` 下完成。
>
> 不過在此之前，我們需要安裝編譯的依賴：
>
>```
> > pacman -S mingw-w64-ucrt-x86_64-gcc
> > pacman -S make
>```
> 下載好專案后，將其解壓到上述路徑下，如 `C:\msys64\home\UserName\freej2me-plus（專案根路徑）`:
>
>```
> > cd freej2me-plus/src/libretro
> > make
>```
> 該命令將在 `src/libretro/` 下建立 `freej2me_libretro.dll` 檔案，這需要和上文中編譯的 `freej2me-lr.jar` 協同使用。
>
> 把 `freej2me_libretro.dll` 放在 `cores/`，`freej2me-lr.jar` 放在 `system`—— 現在，RetroArch 中應該可以正常執行 J2ME 程式。
>
>NOTE: Windows 核心已在 Windows 7、10 和 11 x64 上測試。

----

## 使用方式（AWT 前端）

啟動 AWT 前端（freej2me.jar）時會顯示一個檔案選擇器，讓您選取要執行的 MIDlet。

或者，也可以透過命令列啟動：`java -jar freej2me.jar 'file:///path/to/midlet.jar' [fullscreen? 1=yes, 0=no] [width] [height] [scale] [keyLayout] [framerate]`
除了檔案路徑外，所有參數都是可選的（甚至路徑也是可選的，此時 FreeJ2ME-Plus 會正常開啟）。

---

## 使用的模組和依賴:

### JLayer(MPEG Player): - LGPLv2.1 License, compatible with GPLv3

### libsdl4j: zlib License, compatible with GPLv3

### ObjectWeb's ASM: BSD 3-Clause License, not directly compatible with GPLv3, but can be used as long as the original license is published alongside GPLv3 (check the 'License' tab)

### Libretro's API: MIT License, compatible with GPLv3

# 協助我們改進:
  1) Open an Issue
  2) Try solving that issue
  3) Post on the Issue if you have a possible solution
  4) Submit a PR implementing the solution

**如果您不是開發者，僅需正常提出 Issue 即可。**