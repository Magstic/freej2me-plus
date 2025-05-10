# FreeJ2ME

![Java CI](https://github.com/TASEmulators/freej2me-plus/actions/workflows/ant.yml/badge.svg)
![Libretro Cores](https://github.com/TASEmulators/freej2me-plus/actions/workflows/libretro.yml/badge.svg)

**简体中文** | [繁體中文](https://github.com/Magstic/freej2me-plus_CN/blob/devel/README_CNT.md) | [English](https://github.com/Magstic/freej2me-plus_CN/blob/devel/README.md)

J2ME 模拟器，自带 Libretro、AWT 以及 SDL2 前端.

该 Fork 目前主要维护繁体中文翻译。

请先从 [upstream](https://github.com/TASEmulators/freej2me-plus) 下载 Code，然后使用该处的中文文件进行覆盖并编译。

项目原开发者 :
- David Richardson [Recompile@retropie]
- Saket Dandawate  [Hex@retropie]

---

## 如何操控？

* `Q` `W` 映射为『左右选择键』。
* `方向键` 映射为『导航键』。若键码布局为 `Default`，则方向键将映射为 `2` `4` `6` `8`。
* 数字键盘模拟手机运作，`1` `2` `3` 和 `7` `8` `9` 互相映射。
* `E` `R` 映射为 `*` `#`。
* 若键码布局为 `Default`，`Enter` 映射为 `OK` 或者 `5`。
* `ESC` 为设定选单。在 libretro 中，这个按键为 `F1`。
* 在 AWT 前端 (freej2me.jar) 中，`Ctrl+C` 可以撷取屏幕， `+`/`-` 可以控制视窗缩放。

这里提供了更多的键值映射资讯： [here](KEYMAP.md) 

## 连结
Latest build (English Core):

  Java: https://nightly.link/TASEmulators/freej2me-plus/workflows/ant/devel

  Libretro cores: https://nightly.link/TASEmulators/freej2me-plus/workflows/libretro/devel

  Screenshots: https://imgur.com/a/2vAeC

  Compatibility List: https://tasemulators.github.io/freej2me-plus/

----
**编译 FreeJ2ME Jar:**

> **Linux:**
>
> 为了完成编译，您需要准备 [Java 8](https://docs.azul.com/core/install/debian) 以及 [Apache Ant](https://ant.dev.org.tw/manual/install.html)。
>
> 打开终端机，执行以下操作（`freej2me/`替换为项目的绝对根路径）：
>
>```
> > cd freej2me/
> > ant
>```
>
> **Windows:**
>
> 为了完成编译，您需要准备 [Java 8](https://www.java.com/zh-TW/download/) 以及 [Apache Ant](https://ant.dev.org.tw/manual/install.html)。
>
> 打开命令提示字元，执行以下操作（`freej2me/`替换为项目的绝对根路径）：
>
>```
> > cd freej2me/
> > ant
>```
> 编译结果保存在根路径下出现的 `build` 资料夹:
>
> `freej2me.jar` -> 独立的 AWT 可执行档，目前主要的独立版本。
> 
> `freej2me-lr.jar` -> Libretro 核心依赖，需放置在 RetroArch 根路径的 `system` 资料夹下。
>
>`freej2me-sdl.jar` -> SDL2 可执行档，支援 libTAS 和 手把摇杆。在未来可能会成为独立版本。
>
> 如果您想在 Libretro 中使用 jar，您仍需按照以下步骤编译核心档案。

**编译 Libretro 核心**

> **Linux:**
> 
> 请在该项目的根路径下打开终端，并执行以下命令：
>```
> > cd src/libretro
> > make
>```
> 该命令将在 `src/libretro/` 下建立 `freej2me_libretro.so` 档案，这需要和上文中编译的 `freej2me-lr.jar` 协同使用。
>
> 把 `freej2me_libretro.so` 放在 `cores/`，`freej2me-lr.jar` 放在 `system`—— 现在，RetroArch 中应该可以正常执行 J2ME 程序。
>
> NOTE: 核心无法在容器或是沙箱中工作，除非沙箱中的 Java 可以和核心响应！这是您使用 Flatpak 或者 Snap 时需要注意的。
>

> **Windows:**
> 
> 若想在 Windows 上编译核心，您需要使用 mingw 或 MSYS2 64 模拟 Linux 环境。
>
> 本指南使用 MSYS2 64，因为其设定简单，且更接近 Linux 的语法。
>
> 安装 [MSYS2-x86_64](https://www.msys2.org/)。常规情况下，您的所有编译工作将在 `C:\msys64\home\UserName` 下完成。
>
> 不过在此之前，我们需要安装编译的依赖：
>
>```
> > pacman -S mingw-w64-ucrt-x86_64-gcc
> > pacman -S make
>```
> 下载好项目后，将其解压到上述路径下，如 `C:\msys64\home\UserName\freej2me-plus（项目根路径）`:
>
>```
> > cd freej2me-plus/src/libretro
> > make
>```
> 该命令将在 `src/libretro/` 下建立 `freej2me_libretro.dll` 档案，这需要和上文中编译的 `freej2me-lr.jar` 协同使用。
>
> 把 `freej2me_libretro.dll` 放在 `cores/`，`freej2me-lr.jar` 放在 `system`—— 现在，RetroArch 中应该可以正常执行 J2ME 程序。
>
>NOTE: Windows 核心已在 Windows 10 和 11 x64 上测试。

----

**使用方式（适用于 AWT 与 SDL 前端）：**

启动 AWT 前端（freej2me.jar）时会显示 MIDlet 软件档案选择器。

亦可透过终端直接启动：`java -jar freej2me.jar 'file:///path/to/midlet.jar' [width] [height] [scale]`，其中，`[width] [height] [scale]` 为可选参数。

SDL2 前端（freej2me-sdl.jar）使用相同命令列参数格式，惟不支援缩放比例选项。

注意：此版本需 libSDL 2.24.0-1 或更高版本。请确认系统已安装该函式库，或将其置于 jar 档案同目录。

在 Windows 系统执行时请注意：档案路径需额外添加 `/` 前缀。例如 `C:\path\to\midlet.jar` 应键入为 `file:///C:\path\to\midlet.jar`

Windows 系统特别提示：建议使用 Adoptium 的 [OpenJDK JRE](https://adoptium.net/temurin/releases/?os=windows&arch=x64&package=jre) 替代 Oracle JRE。Oracle 后期版本引入的 javaw.exe 引导程序，会导致 RetroArch 关闭游戏后残留 javaw.exe 进程。由于核心程序无法有效判别 javaw 是否运作，建议解决方式为：为系统环境变数加入 javaw.exe 路径，并删除 javapath，或直接使用无此问题的 Adoptium JRE。

FreeJ2ME 将存档资料与设定档储存于执行时的工作目录。当前若设定档中指定分辨率，将优先使用设定档数值而非命令列传入参数。

---

## 使用的模组和依赖:

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
