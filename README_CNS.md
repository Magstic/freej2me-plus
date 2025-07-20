# FreeJ2ME

![Java CI](https://github.com/TASEmulators/freej2me-plus/actions/workflows/ant.yml/badge.svg)
![Libretro Cores](https://github.com/TASEmulators/freej2me-plus/actions/workflows/libretro.yml/badge.svg)

[繁体中文](README_CNT.md) | **简体中文** | [English](README_EN.md)

J2ME 模拟器，自带 Libretro、AWT 以及 SDL2 前端。

该 Fork 目前主要维护 RetroArch 的简体中文和繁体中文翻译，以及 AWT （编码尚未支援）的简体中文翻译。

请先从 [upstream](https://github.com/TASEmulators/freej2me-plus) 下载代码，然后使用该处的中文文件进行覆盖并编译。

项目原作者：
- David Richardson [Recompile@retropie]
- Saket Dandawate  [Hex@retropie]

---

## 控制说明

* `Q` `W` 键分别对应左右软键 (Softkey)。
* `方向键` 用于导航。若手机按键布局设为 `Standard`，方向键则对应 `2`、`4`、`6`、`8`。
* 数字键作用与预期相符，小键盘的数字是反向对应的（`1` `2` `3` 与 `7` `8` `9` 互相交换，如同手机的九宫格键盘）。
* `E` `R` 键可作为 `*` `#` 键的替代。
* `Enter` 键在 `Standard` 模式下作为 ‘OK/开火键’ 或 `5` 键。
* `ESC` 键可调用设置菜单。在 RetroArch 中，此功能对应的按键为 `F1`。
* 在 AWT 前端 (freej2me.jar) 中，`Ctrl+C` 可以截屏，`+` / `-` 可以控制窗口的缩放比例。

点击 [此处](KEYMAP.md) 查看更多按键绑定信息。

## 链接

[![Nightly Builds](https://img.shields.io/badge/Nightly_Builds-blue.svg)](https://github.com/TASEmulators/freej2me-plus/releases/tag/nightlies)
[![Screenshots](https://img.shields.io/badge/Screenshots-green.svg)](https://imgur.com/a/2vAeC)
[![Compatibility List](https://img.shields.io/badge/Compatibility%20List-orange.svg)](https://tasemulators.github.io/freej2me-plus/)

----
## 编译说明

### 编译 FreeJ2ME Jar

#### Linux
>
> 为了完成编译，您需要准备 [Java 8](https://docs.azul.com/core/install/debian) 以及 [Apache Ant](https://www.ant.org/manual/index.html)。
>
> 打开终端，执行以下操作（`freej2me/` 替换为项目的绝对根路径）：
>
>```
> > cd freej2me/
> > ant
>```
>
#### Windows
>
> 为了完成编译，您需要准备 [Java 8](https://www.java.com/zh-CN/download/) 以及 [Apache Ant](https://www.ant.org/manual/index.html)。
>
> 打开命令提示符，执行以下操作（`freej2me/` 替换为项目的绝对根路径）：
>
>```
> > cd freej2me/
> > ant
>```
> 编译结果将保存在根目录下的 build 文件夹中：
>
> `freej2me.jar` -> 独立的 AWT 可执行文件，目前主要的独立版本。
> 
> `freej2me-lr.jar` -> Libretro 核心依赖。它扮演着核心的 “BIOS” 并负责执行 J2ME jar 文件，因此必须放置在 RetroArch 的 `system` 文件夹下。
>
> `freej2me-sdl.jar` -> SDL2 可执行文件，支持 libTAS 和游戏手柄。在未来可能会成为主要的独立版本。
>
> 如果您想在 Libretro 中使用 jar，您仍需按照以下步骤编译核心文件。

### 编译 Libretro 核心

#### Linux
> 
> 请在该项目的根路径下打开终端，并执行以下命令：
>```
> > cd src/libretro
> > make
>```
> 该命令将在 `src/libretro/` 下创建 `freej2me_libretro.so` 文件，这需要和上文中编译的 `freej2me-lr.jar` 协同使用。
>
> 将 `freej2me_libretro.so` 放置于 `cores/` 文件夹，`freej2me-lr.jar` 放置于 `system` 文件夹——现在，RetroArch 中应该可以正常执行 J2ME 程序。
>
> 注意：核心无法在容器或沙箱中工作，除非沙箱中的 Java 可以和核心响应！这是您使用 Flatpak 或者 Snap 时需要注意的。
>

#### Windows
> 
> 若想在 Windows 上编译核心，您需要使用 mingw 或 MSYS2 64 模拟 Linux 环境。
>
> 本指南使用 MSYS2 64，因为它设置简单，且更接近 Linux 的语法。
>
> 安装 [MSYS2-x86_64](https://www.msys2.org/)。一般情况下，您的所有编译工作将在 `C:\msys64\home\UserName` 下完成。
>
> 不过在此之前，我们需要安装编译所需的依赖：
>
>```
> > pacman -S mingw-w64-ucrt-x86_64-gcc
> > pacman -S make
>```
> 下载好项目后，将其解压到上述路径下，如 `C:\msys64\home\UserName\freej2me-plus` (项目根路径):
>
>```
> > cd freej2me-plus/src/libretro
> > make
>```
> 该命令将在 `src/libretro/` 下创建 `freej2me_libretro.dll` 文件，这需要和上文中编译的 `freej2me-lr.jar` 协同使用。
>
> 将 `freej2me_libretro.dll` 放置于 `cores/` 文件夹，`freej2me-lr.jar` 放置于 `system` 文件夹——现在，RetroArch 中应该可以正常执行 J2ME 程序。
>
> 注意：Windows 核心已在 Windows 7、10 和 11 x64 上测试。

----

## 使用方式 (适用于 AWT 与 SDL 前端)

启动 AWT 前端 (freej2me.jar) 时会显示一个文件选择器，让您选取要执行的 MIDlet。

或者，也可以通过命令行启动：`java -jar freej2me.jar 'file:///path/to/midlet.jar' [fullscreen? 1=yes, 0=no] [width] [height] [scale] [keyLayout] [framerate]`
除了文件路径外，所有参数都是可选的（甚至路径也是可选的，此时 FreeJ2ME-Plus 会正常开启）。

SDL2 前端 (freej2me-sdl.jar) 接受相同的命令行参数格式，但 **不支持** `scale` (缩放比例) 选项。

**注意**：此版本需要 libSDL2 2.24.0-1 或更高版本才能启动。请确认您的系统已安装该库，或将其置于 jar 文件的同层目录下以便加载。

在 Windows 系统运行时请注意：文件路径需额外添加一个 `/` 前缀。例如，`C:\path\to\midlet.jar` 应输入为 `file:///C:\path\to\midlet.jar`

FreeJ2ME 会将存档数据和配置文件保存于其运行时的工作目录。目前，若配置文件中指定了分辨率，将优先使用配置文件的数值，而非命令行传入的参数。

---

## 使用的模块和依赖:

### JLayer(MPEG Player): - LGPLv2.1 License, compatible with GPLv3

### libsdl4j: zlib License, compatible with GPLv3

### ObjectWeb's ASM: BSD 3-Clause License, not directly compatible with GPLv3, but can be used as long as the original license is published alongside GPLv3 (check the 'License' tab)

### Libretro's API: MIT License, compatible with GPLv3

# 帮助我们改进:
  1) Open an Issue
  2) Try solving that issue
  3) Post on the Issue if you have a possible solution
  4) Submit a PR implementing the solution

**如果您不是开发者，仅需正常提交 Issue 即可。**