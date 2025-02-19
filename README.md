# freej2me

![Java CI](https://github.com/TASEmulators/freej2me-plus/actions/workflows/ant.yml/badge.svg)
![Libretro Cores](https://github.com/TASEmulators/freej2me-plus/actions/workflows/libretro.yml/badge.svg)

J2ME 模擬器，自帶 libretro、awt 以及 sdl2 前端.

該 Fork 將 libretro 前端進行了簡體中文翻譯。

我不懂程式碼，所以無法為該專案做出實質性的貢獻，非常抱歉。

專案原開發者 :
- David Richardson [Recompile@retropie]
- Saket Dandawate  [Hex@retropie]

---

## 如何操控？

* `Q` `W` 映射為『左右選擇鍵』。
* `方向鍵` 映射為『導航鍵』。若鍵碼佈局為 `Default`，則方向鍵將映射為 `2` `4` `6` `8`。
* 數字鍵盤模擬手機運作，`1` `2` `3` 和 `7` `8` `9` 互相映射。
* `E` `R` 映射為 `*` `#`。
* 若鍵碼佈局為 `Default`，`Enter` 映射為 `OK` 或者 `5`。
* `ESC` 為設定選單。在 libretro 中，這個按鍵為 `F1`。
* 在 AWT 前端 (freej2me.jar) 中，`Ctrl+C` 可以擷取螢幕， `+`/`-` 可以控制視窗縮放。

這裡提供了更多的鍵值映射資訊： [here](KEYMAP.md) 

## 連結
Latest build:

  Java: https://nightly.link/TASEmulators/freej2me-plus/workflows/ant/devel

  Libretro cores: https://nightly.link/TASEmulators/freej2me-plus/workflows/libretro/devel

  Screenshots: https://imgur.com/a/2vAeC

  Compatibility List: https://tasemulators.github.io/freej2me-plus/

----
**編譯 FreeJ2ME Jar:**

> 操作將在 Windows 中完成。
>
> 為了完成編譯，您需要準備 [Java 8](https://www.java.com/zh-TW/download/) 以及 [Apache Ant](https://ant.dev.org.tw/manual/install.html)。
>
> 打開命令提示字元，執行以下操作（`freej2me/`替換為專案的絕對根路徑）：
>
>```
> > cd freej2me/
> > ant
>```
> 編譯結束后，打開根路徑下出現的 `build` 資料夾:
>
> `freej2me.jar` -> 獨立的 AWT 可執行檔，目前主要的獨立版本。
> 
> `freej2me-lr.jar` -> Libretro 核心依賴，需放置在 RetroArch 根路徑的 `system` 資料夾下。
>
>`freej2me-sdl.jar` -> SDL2 可執行檔，支援 libTAS 和 手把搖桿。在未來可能會成為獨立版本。
>
> 如果您想在 Libretro 中使用 jar，您仍需按照以下步驟編譯核心文件。

**編譯 Libretro 核心**

> **Linux:**
> 請在該專案的根路徑下打開終端，並執行以下命令：
>```
> > cd src/libretro
> > make
>```
> 該命令將在 `src/libretro/` 下建立 `freej2me_libretro.so` 文件，這需要和上文中編譯的 `freej2me-lr.jar` 協同使用。
>
> 把 `freej2me_libretro.so` 放在 `cores/`，`freej2me-lr.jar` 放在 `system`—— 現在，RetroArch 中應該可以正常執行 J2ME 程式。
>
> NOTE: 核心無法在容器或是沙箱中工作，除非沙箱中的 Java 可以和核心響應！這是您使用 Flatpak 或者 Snap 時需要注意的。
>

> **Windows:**
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
> 該命令將在 `src/libretro/` 下建立 `freej2me_libretro.dll` 文件，這需要和上文中編譯的 `freej2me-lr.jar` 協同使用。
>
> 把 `freej2me_libretro.dll` 放在 `cores/`，`freej2me-lr.jar` 放在 `system`—— 現在，RetroArch 中應該可以正常執行 J2ME 程式。
>
>NOTE: Windows 核心已在 Windows 10 和 11 x64 上測試。

----
**Usage (applies to AWT and SDL):**

Launching the AWT frontend (freej2me.jar) will bring up a filepicker to select the MIDlet to run.

Alternatively it can be launched from the command line: `java -jar freej2me.jar 'file:///path/to/midlet.jar' [width] [height] [scale]`
Where _width_, _height_ (dimensions of the simulated screen) and _scale_ (initial scale factor of the window) are optional arguments.

The SDL2 frontend (freej2me-sdl.jar) accepts the same command-line arguments format, aside from the _scale_ option which is unavailable. **NOTE**: This flavor requires libSDL 2.24.0-1 or newer in order to even launch. Make sure you have it installed in your system, or placed alongside the jar for it to load.

When running under Microsoft Windows please do note paths require an additional `/` prefixed. For example, `C:\path\to\midlet.jar` should be passed as `file:///C:\path\to\midlet.jar`

Special note for Windows: It is recommended to use Adoptium's [OpenJDK JRE](https://adoptium.net/temurin/releases/?os=windows&arch=x64&package=jre), instead of Oracle JRE. Late versions of Oracle introduced a bootstrapper javaw.exe, which will leave the actual javaw.exe process behind once the game is closed in RetroArch. Unfortunately, there is no good way for the core to determine which javaw is the _actual_ one, so you will either need to edit your system env to add the actual javaw.exe's path and delete the `javapath` one, or simply use Adoptium's JRE which does not have this issue.

FreeJ2ME keeps savedata and config at the working directory it is run from. Currently any resolution specified at the config file takes precedence over the values passed via command-line.

---

## Modules and external dependencies used:

### JLayer(MPEG Player): - LGPLv2.1 License, compatible with GPLv3

### libsdl4j: zlib License, compatible with GPLv3

### ObjectWeb's ASM: BSD 3-Clause License, not directly compatible with GPLv3, but can be used as long as the original license is published alongside GPLv3 (check the 'License' tab)

### Libretro's API: MIT License, compatible with GPLv3

# How to contribute as a developer:
  1) Open an Issue
  2) Try solving that issue
  3) Post on the Issue if you have a possible solution
  4) Submit a PR implementing the solution

**If you are not a developer, just open an issue normally.**
