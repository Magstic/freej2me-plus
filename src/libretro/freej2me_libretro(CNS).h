/*
	This file is part of FreeJ2ME.

	FreeJ2ME is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.

	FreeJ2ME is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
	GNU General Public License for more details.

	You should have received a copy of the GNU General Public License
	along with FreeJ2ME.  If not, see http://www.gnu.org/licenses/
*/
#include "libretro.h"

#define PIPE_READ_BUFFER_SIZE 32767
#define DEFAULT_FPS 60
#define BASE_WIDTH 320
#define BASE_HEIGHT 240
#define MAX_WIDTH 800
#define MAX_HEIGHT 800

/* Used as a limit to the string of core option updates to be sent to the Java app */
#define PIPE_MAX_LEN 255


#define PHONE_KEYS 19

/* Input mapping variables and descriptions */
static const struct retro_controller_description port_1[] =
{
    { "Joypad",    RETRO_DEVICE_JOYPAD },
    { "Keyboard",  RETRO_DEVICE_KEYBOARD },
    { "Empty",     RETRO_DEVICE_NONE },
    { 0 },
};

/* No use having more than one input port on this core */
static const struct retro_controller_info ports[] =
{
    { port_1, 2 },
    { 0 },
};


#define FRAMES_DROPPED_MSG 0
#define INVALID_STATUS_MSG 1
#define COULD_NOT_START_MSG 2
#define CORE_HAS_LOADED_MSG 3

static const struct retro_message_ext messages[] =
{
   /* Message string to be displayed/logged */
   {"Too many frames dropped!!! Please restart the core.", 8000, 3, RETRO_LOG_ERROR, RETRO_MESSAGE_TARGET_ALL, RETRO_MESSAGE_TYPE_NOTIFICATION, 0},
   {"Invalid status received!!! Please restart the core.", 8000, 3, RETRO_LOG_ERROR, RETRO_MESSAGE_TARGET_ALL, RETRO_MESSAGE_TYPE_NOTIFICATION, 0},
   {"FreeJ2ME could not start!!! \nMake sure > freej2me-lr.jar < is in the 'system' dir and that you have Java 8 or newer installed.", 15000, 3, RETRO_LOG_ERROR, RETRO_MESSAGE_TARGET_ALL, RETRO_MESSAGE_TYPE_NOTIFICATION, 0},
   {"FreeJ2ME child process loaded successfully!", 4000, 1, RETRO_LOG_INFO, RETRO_MESSAGE_TARGET_OSD, RETRO_MESSAGE_TYPE_NOTIFICATION, 0},
   {"", 0, 0, 0, 0, 0, 0}
};

/* This is responsible for exposing the joypad input mappings to the frontend */
static const struct retro_input_descriptor desc[] =
{
    { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_LEFT,                                     "Arrow Left" },
    { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_UP,	                                      "Arrow Up" },
    { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_DOWN,                                     "Arrow Down" },
    { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_RIGHT,                                    "Arrow Right" },
    { 0, RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_LEFT, RETRO_DEVICE_ID_ANALOG_X,           "Num 4(-), Num 6(+) : " },
    { 0, RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_LEFT, RETRO_DEVICE_ID_ANALOG_Y,           "Num 2(-), Num 8(+) : " },
    { 0, RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_RIGHT, RETRO_DEVICE_ID_ANALOG_X,          "Pointer Horizontal Move"},
    { 0, RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_RIGHT, RETRO_DEVICE_ID_ANALOG_Y,          "Pointer Vertical Move"},
    { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_B,                                        "Num 7" },
    { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_A,                                        "Num 9" },
    { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_X,                                        "Num 0" },
    { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_Y,                                        "OK/Fire" },
    { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_L,                                        "Num 1" },
    { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_R,                                        "Num 3" },
    { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_R2,                                       "Num #" },
    { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_L2,                                       "Num *" },
    { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_L3,                                       "Num 5/Pointer Press" },
    { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_SELECT,                                   "Left Soft Key" },
    { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_START,                                    "Right Soft Key" },

    { 0 },
};

/* TODO: Default keyboard input mappings, likely mirroring FreeJ2ME Standalone's defaults */

/* Categories for frontends that support config version 2 */
struct retro_core_option_v2_category option_categories[] =
{
    {
        "system_settings",
        "系统",
        "Free-J2ME 模拟相关设置。"
    },
    {
        "advanced_settings",
        "高级",
        "Free-J2ME 核心相关设置。"
    },
    {
        "speed_hacks",
        "优化",
        "Free-J2ME 优化相关设置。"
    },
    {
        "compat_settings",
        "兼容",
        "Free-J2ME 兼容相关设置。"
    },
};

/* Core config options if running on a frontend with support for config version 2 */
struct retro_core_option_v2_definition core_options[] =
{
    {
        "freej2me_resolution",
        "System > Phone Resolution (Core Restart may be required)",
        "系统分辨率（需重启核心）",
        "J2ME 游戏的分辨率并非固定。若游戏窗口过小或被截断，请尝试调整该选项。若游戏在运行时更改分辨率出现错误，请重启游戏。",
        "J2ME 游戏的分辨率并非固定。若游戏窗口过小或被截断，请尝试调整该选项。若游戏在运行时更改分辨率出现错误，请重启游戏。",
        "system_settings",
        {
            { "96x65",     NULL },
            { "101x64",    NULL },
            { "101x80",    NULL },
            { "128x128",   NULL },
            { "130x130",   NULL },
            { "120x160",   NULL },
            { "128x160",   NULL },
            { "132x176",   NULL },
            { "176x208",   NULL },
            { "176x220",   NULL },
            { "220x176",   NULL },
            { "208x208",   NULL },
            { "180x320",   NULL },
            { "320x180",   NULL },
            { "208x320",   NULL },
            { "240x320",   NULL },
            { "320x240",   NULL },
            { "240x400",   NULL },
            { "400x240",   NULL },
            { "240x432",   NULL },
            { "240x480",   NULL },
            { "352x416",   NULL },
            { "360x640",   NULL },
            { "640x360",   NULL },
            { "640x480",   NULL },
            { "480x800",   NULL },
            { "800x480",   NULL },
            { NULL, NULL },
        },
        "240x320"
    },
    {
        "freej2me_rotate",
        "System > Rotate Screen",
        "屏幕旋转",
        "一些游戏（特别是触屏游戏）通常需要旋转屏幕。",
        "一些游戏（特别是触屏游戏）通常需要旋转屏幕。",
        "system_settings",
        {
            { "off", "禁用" },
            { "on",  "启用"  },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_phone",
        "System > Phone Key Layout",
        "键值布局",
        "J2ME 平台存在不同的手机制造商，这导致游戏之间的按键布局也不尽相同。若游戏的按键响应非常奇怪，请尝试调整该选项。",
        "J2ME 平台存在不同的手机制造商，这导致游戏之间的按键布局也不尽相同。若游戏的按键响应非常奇怪，请尝试调整该选项。",
        "system_settings",
        {
            { "Default",             NULL },
            { "LG",                  NULL },
            { "Motorola/SoftBank",   NULL },
            { "Motorola Triplets",   NULL },
            { "Motorola V8",         NULL },
            { "Nokia Full Keyboard", NULL },
            { "Sagem",               NULL },
            { "Siemens",             NULL },
            { "Siemens Old",         NULL },
            { NULL, NULL },
        },
        "Default"
    },
    {
        "freej2me_backlightcolor",
        "System > LCD Backlight Color",
        "LCD 背光",
        "单色游戏适用。这些游戏会点亮 / 熄灭屏幕以产生额外效果（如『诺基亚 3410（绿）』、『诺基亚 6310i（青）』、『西门子 C55（橙）』）。若游戏为全彩或是您不喜欢背光效果，请禁用该选项。",
        "单色游戏适用。这些游戏会点亮 / 熄灭屏幕以产生额外效果（如『诺基亚 3410（绿）』、『诺基亚 6310i（青）』、『西门子 C55（橙）』）。若游戏为全彩或是您不喜欢背光效果，请禁用该选项。",
        "system_settings",
        {
            { "Disabled", "禁用" },
            { "Green",    "绿色" },
            { "Cyan",     "青色" },
            { "Orange",   "橘色" },
            { "Violet",   "紫色" },
            { "Red",      "红色" },
            { NULL, NULL },
        },
        "Green"
    },
    {
        "freej2me_fps",
        "System > Game FPS Limit",
        "FPS 限制",
        "J2ME 在处理同步时的自由度很大，一些 FPS 不设限的游戏在运行时帧率可能会爆炸。请根据实际情况酌情配置该选项。",
        "J2ME 在处理同步时的自由度很大，一些 FPS 不设限的游戏在运行时帧率可能会爆炸。请根据实际情况酌情配置该选项。",
        "system_settings",
        {
            { "Auto", "自动" },
            { "60",   "60 FPS"   },
            { "30",   "30 FPS"   },
            { "15",   "15 FPS"   },
            { NULL, NULL },
        },
        "Auto"
    },
    {
        "freej2me_sound",
        "System > Virtual Phone Sound (Core Restart required)",
        "模拟手机声音",
        "一些游戏的音频尚未被编解码器支持，该选项可模拟手机加载和播放音频 / 音调的功能。若出现音频未被支持的情况，请尝试禁用核心的音频。若游戏无法运行或是打开过久出现错误，请尝试禁用该选项。",
        "一些游戏的音频尚未被编解码器支持，该选项可模拟手机加载和播放音频 / 音调的功能。若出现音频未被支持的情况，请尝试禁用核心的音频。若游戏无法运行或是打开过久出现错误，请尝试禁用该选项。",
        "system_settings",
        {
            { "on",  "启用"  },
            { "off", "禁用" },
            { NULL, NULL },
        },
        "on"
    },
    {
        "freej2me_midifont",
        "System > MIDI Soundfont",
        "MIDI 音色库",
        "『默认』使用系统或者虚拟机自带的音色库，而『自订』则允许您在『<freej2me-lr.jar folder>/freej2me_system/customMIDI』路径下放置 SF2 音色库来获得更好的听感体验。警告：大型音色库可能无法正常工作！",
        "『默认』使用系统或者虚拟机自带的音色库，而『自订』则允许您在『<freej2me-lr.jar folder>/freej2me_system/customMIDI』路径下放置 SF2 音色库来获得更好的听感体验。警告：大型音色库可能无法正常工作！",
        "system_settings",
        {
            { "off", "默认" },
            { "on",  "自订" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_analogasentirekeypad",
        "System > Use Analog As Entire Keypad",
        "使用摇杆作为数字键盘",
        "在一些游戏中，可以通过将摇杆映射为完整的数字键盘，以此获取更为舒适的游戏体验。代表性游戏：《Time Crisis Elite》，《Rayman Raving Rabbids》。",
        "在一些游戏中，可以通过将摇杆映射为完整的数字键盘，以此获取更为舒适的游戏体验。代表性游戏：《Time Crisis Elite》，《Rayman Raving Rabbids》。",
        "system_settings",
        {
            { "off", "禁用" },
            { "on",  "启用" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_logginglevel",
        "Advanced Settings > Logging Level",
        "日志级别",
        "*调试用* 该选项允许核心将指定或更高级别的日志记录到『freej2me_system/FreeJ2ME.log』。",
        "*调试用* 该选项允许核心将指定或更高级别的日志记录到『freej2me_system/FreeJ2ME.log』。",
        "advanced_settings",
        {
            { "0",  "Disable_禁用"           },
            { "1",  "Debug_调试"             },
            { "2",  "Info_信息"              },
            { "3",  "Warning_警告"           },
            { "4",  "Error_错误"             },
            { NULL, NULL },
        },
        "0"
    },
    {
        "freej2me_dumpaudiostreams",
        "Advanced Settings > Dump Audio Streams",
        "Dump 音频流",
        "*调试用* 该选项允许核心将音频流数据导出至『$SYSTEM/FreeJ2MEDumps/Audio/appname/*』。",
        "*调试用* 该选项允许核心将音频流数据导出至『$SYSTEM/FreeJ2MEDumps/Audio/appname/*』。",
        "advanced_settings",
        {
            { "off",  "禁用"            },
            { "on",  "启用"              },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_pointertype",
        "Advanced Settings > Pointer Type",
        "光标类型",
        "设置核心所用的光标类型。请注意，仅有鼠标支持缩放操作。",
        "设置核心所用的光标类型。请注意，仅有鼠标支持缩放操作。",
        "advanced_settings",
        {
            { "Mouse",  "鼠标"                    },
            { "Touch",  "触摸屏"              },
            { "None",   "无光标 / 手柄模拟" },
            { NULL, NULL },
        },
        "Touch"
    },
    {
        "freej2me_pointerxspeed",
        "Advanced Settings > Pointer X Speed",
        "光标 X 轴速度",
        "设置使用摇杆操控光标时，其在 X 轴上的速度。",
        "设置使用摇杆操控光标时，其在 X 轴上的速度。",
        "advanced_settings",
        {
            { "2",  "慢速"    },
            { "4",  "正常"  },
            { "8",  "快速"    },
            { "16", "极快"  },
            { NULL, NULL },
        },
        "4"
    },
    {
        "freej2me_pointeryspeed",
        "Advanced Settings > Pointer Y Speed",
        "光标 Y 轴速度",
        "设置使用摇杆操控光标时，其在 Y 轴上的速度。",
        "设置使用摇杆操控光标时，其在 Y 轴上的速度。",
        "advanced_settings",
        {
            { "2",  "慢速"    },
            { "4",  "正常"  },
            { "8",  "快速"    },
            { "16", "极快"  },
            { NULL, NULL },
        },
        "4"
    },
    {
        "freej2me_pointerinnercolor",
        "Advanced Settings > Pointer Inner Color",
        "光标内部颜色",
        "设置光标的内部颜色。",
        "设置光标的内部颜色。",
        "advanced_settings",
        {
            { "Black",  "黑"            },
            { "Red",    "红"              },
            { "Green",  "绿"            },
            { "Blue",   "蓝"             },
            { "Yellow", "黄"           },
            { "Pink",   "粉"             },
            { "Cyan",   "青"             },
            { "White",  "白"  },
            { NULL, NULL },
        },
        "White"
    },
    {
        "freej2me_pointeroutercolor",
        "Advanced Settings > Pointer Outline Color",
        "光标轮廓颜色",
        "设置光标的轮廓颜色。",
        "设置光标的轮廓颜色。",
        "advanced_settings",
        {
            { "Black",  "黑"            },
            { "Red",    "红"              },
            { "Green",  "绿"            },
            { "Blue",   "蓝"             },
            { "Yellow", "黄"           },
            { "Pink",   "粉"             },
            { "Cyan",   "青"             },
            { "White",  "白"  },
            { NULL, NULL },
        },
        "Black"
    },
    {
        "freej2me_pointerclickcolor",
        "Advanced Settings > Pointer Click Indicator Color",
        "光标点击颜色",
        "设置光标的内部颜色。",
        "设置光标的内部颜色。",
        "advanced_settings",
        {
            { "Black",  "黑"            },
            { "Red",    "红"              },
            { "Green",  "绿"            },
            { "Blue",   "蓝"             },
            { "Yellow", "黄"           },
            { "Pink",   "粉"             },
            { "Cyan",   "青"             },
            { "White",  "白"  },
            { NULL, NULL },
        },
        "Yellow"
    },
    {
        "freej2me_spdhacknoalpha",
        "Speed Hacks > No Alpha on Blank Images (Restart Required)",
        "无 Alpha 空白图像（需重启核心）",
        "J2ME 规定所有图像都必须有 Alpha 通道。Free-J2ME 可以避免 Alpha 通道创建这些图像，从而为部分游戏提供优良的性能提升。",
        "J2ME 规定所有图像都必须有 Alpha 通道。Free-J2ME 可以避免 Alpha 通道创建这些图像，从而为部分游戏提供优良的性能提升。",
        "speed_hacks",
        {
            { "on",  "启用"            },
            { "off", "禁用" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_compatnonfatalnullimages",
        "Compatibility Settings > Don't throw Exception on null images",
        "不对 Null 图像抛出异常",
        "根据 J2ME 规范，处理或加载 Null 图像时必须抛出 NullPointerException。如果游戏没有异常处理机制（少数情况），那么游戏将会卡死。如：无异常处理机制的《House M.D.》启用该选项即可正常运行。不过，这可能会使进行异常处理机制的游戏出现错误。）",
        "根据 J2ME 规范，处理或加载 Null 图像时必须抛出 NullPointerException。如果游戏没有异常处理机制（少数情况），那么游戏将会卡死。如：无异常处理机制的《House M.D.》启用该选项即可正常运行。不过，这可能会使进行异常处理机制的游戏出现错误。",
        "compat_settings",
        {
            { "on",  "启用"            },
            { "off", "禁用" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_compatcliprectongfxreset",
        "Compatibility Settings > Do clipRect instead of setClip on gfx reset",
        "图形重置时使用 clipRect 替代 setClip",
        "《Fantasy Zone》的 128x128 版本依赖图形重置时调用 clipRect() 而非 setClip()。这是目前兼容性列表中唯一需要该特殊设置的游戏，启用该选项可能导致其他游戏渲染异常。",
        "《Fantasy Zone》的 128x128 版本依赖图形重置时调用 clipRect() 而非 setClip()。这是目前兼容性列表中唯一需要该特殊设置的游戏，启用该选项可能导致其他游戏渲染异常。",
        "compat_settings",
        {
            { "on",  "启用"            },
            { "off", "禁用" },
            { NULL, NULL },
        },
        "off"
    },
    { NULL, NULL, NULL, NULL, NULL, NULL, {{0}}, NULL },
};

/* Core Options v2 struct that allows us to categorize the core options on frontends that support config version 2 */
struct retro_core_options_v2 core_exposed_options =
{
    option_categories,
    core_options
};


/* ---------------------------------- config version 1 properties below ---------------------------------- */

/* Core config options if running on a frontend with support for config version 1 */
struct retro_core_option_definition core_options_v1 [] =
{
    {
        "freej2me_resolution",
        "Phone Resolution (Core Restart may be required)",
        "Not all J2ME games run at the same screen resolution. If the game's window is too small, or has sections of it cut off, try increasing or decreasing the internal screen resolution. Some games also break when the screen size is updated while it's running, so in those cases, a restart is required.",
        {
            { "96x65",     NULL },
            { "101x64",    NULL },
            { "101x80",    NULL },
            { "128x128",   NULL },
            { "130x130",   NULL },
            { "120x160",   NULL },
            { "128x160",   NULL },
            { "132x176",   NULL },
            { "176x208",   NULL },
            { "176x220",   NULL },
            { "220x176",   NULL },
            { "208x208",   NULL },
            { "180x320",   NULL },
            { "320x180",   NULL },
            { "208x320",   NULL },
            { "240x320",   NULL },
            { "320x240",   NULL },
            { "240x400",   NULL },
            { "400x240",   NULL },
            { "240x432",   NULL },
            { "240x480",   NULL },
            { "352x416",   NULL },
            { "360x640",   NULL },
            { "640x360",   NULL },
            { "640x480",   NULL },
            { "480x800",   NULL },
            { "800x480",   NULL },
            { NULL, NULL },
        },
        "240x320"
    },
    {
        "freej2me_rotate",
        "Rotate Screen",
        "Some games, especially ones that support touch controls, tend to expect the screen to be rotated. This option comes in handy on those cases.",
        {
            { "off", "Disabled" },
            { "on",  "Enabled"  },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_phone",
        "Phone Key Layout",
        "Due to the different mobile phone manufacturers on the J2ME space, it's usual to have some games expecting a certain phone's key layout like Nokia's for example. If a game is not responding to the inputs correctly, try changing this option.",
        {
            { "Default",             NULL },
            { "LG",                  NULL },
            { "Motorola/SoftBank",   NULL },
            { "Motorola Triplets",   NULL },
            { "Motorola V8",         NULL },
            { "Nokia Full Keyboard", NULL },
            { "Sagem",               NULL },
            { "Siemens",             NULL },
            { "Siemens Old",         NULL },
            { NULL, NULL },
        },
        "Default"
    },
    {
        "freej2me_backlightcolor",
        "LCD Backlight Color",
        "Mostly used for monochrome games, where they request the screen to be lit/unlit for additional effects. This option allows you to select a color for the backlight to mimic some of these devices, like Green (Nokia 3410), Cyan (Nokia 6310i), Orange (Siemens C55), etc. If the game you're running has colored graphics and requests screen backlight anyway, or you don't like those backlight effects, disable this option.",
        {
            { "Disabled", NULL },
            { "Green",    NULL },
            { "Cyan",     NULL },
            { "Orange",   NULL },
            { "Violet",   NULL },
            { "Red",      NULL },
            { NULL, NULL },
        },
        "Green"
    },
    {
        "freej2me_fps",
        "Game FPS Limit",
        "The J2ME platform allows a great deal of freedom when dealing with synchronization, so while many games are locked to a certain framerate internally, others allow for variable framerates when uncapped at the cost of higher CPU usage, and some even run faster than intended when they get over a certain FPS threshold. Use the option that best suits the game at hand.",
        {
            { "Auto", "Disabled" },
            { "60",   "60 FPS"   },
            { "30",   "30 FPS"   },
            { "15",   "15 FPS"   },
            { NULL, NULL },
        },
        "Auto"
    },
    {
        "freej2me_sound",
        "Virtual Phone Sound (Core Restart required)",
        "Enables or disables the virtual phone's ability to load and play audio samples/tones. Some games require support for codecs not yet implemented, or have issues that can be worked around by disabling audio in FreeJ2ME (ID Software games such as DOOM II RPG having memory leaks with MIDI samples being one example). If a game doesn't run or has issues during longer sessions, try disabling this option.",
        {
            { "on",  "On"  },
            { "off", "Off" },
            { NULL, NULL },
        },
        "on"
    },
    {
        "freej2me_midifont",
        "MIDI Soundfont",
        "Selects which kind of MIDI soundfont to use. 'Default' uses the soundfont bundled with the system or Java VM, while 'Custom' allows you to place a custom soundfont on '<freej2me-lr.jar folder>/freej2me_system/customMIDI' and use it on J2ME apps to simulate a specific phone or improve MIDI sound quality. WARNING: Big soundfonts greatly increase the emulator's RAM footprint and processing requirements, while smaller ones can actually help it perform better.",
        {
            { "off", "Default" },
            { "on",  "Custom" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_analogasentirekeypad",
        "Use Analog As Entire Keypad",
        "A few games like Time Crisis Elite and Rayman Raving Rabbids can benefit from having the analog serve as the entire keypad for smoother gameplay (in TC Elite's case, with num 5 as pressing the analog too). If you have a game that appears to benefit from this by using the diagonal keypad keys instead of allowing for num2 and num4 to be pressed simultaneously for the same effect for example, try enabling it.",
        {
            { "off", "Disabled" },
            { "on",  "Enabled" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_logginglevel",
        "Logging Level",
        "When enabled, this option allows FreeJ2ME to log messages of the specified level and higher into 'freej2me_system/FreeJ2ME.log' to facilitate debugging",
        {
            { "0",  "Disable"           },
            { "1",  "Debug"             },
            { "2",  "Info"              },
            { "3",  "Warning"           },
            { "4",  "Error"             },
            { NULL, NULL },
        },
        "0"
    },
    {
        "freej2me_dumpaudiostreams",
        "Dump Audio Streams",
        "This option allows FreeJ2ME to dump incoming Audio Data into $SYSTEM/FreeJ2MEDumps/Audio/appname/*, mostly useful for debugging",
        {
            { "off",  "Disable"            },
            { "on",  "Enable"              },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_pointertype",
        "Pointer Type",
        "This option sets the type of pointer used by FreeJ2ME, can be set to use a Mouse, a Touchscreen or neither. Please note that only Mouse supports drag and drop motions",
        {
            { "Mouse",  "Mouse"                    },
            { "Touch",  "Touchscreen"              },
            { "None",   "No Pointer/Joypad Analog" },
            { NULL, NULL },
        },
        "Mouse"
    },
    {
        "freej2me_pointerxspeed",
        "Pointer X Speed",
        "This option sets the horizontal speed of the on-screen pointer when controlled by a joypad's analog stick.",
        {
            { "2",  "Slow"    },
            { "4",  "Normal"  },
            { "8",  "Fast"    },
            { "16", "Faster"  },
            { NULL, NULL },
        },
        "4"
    },
    {
        "freej2me_pointeryspeed",
        "Pointer Y Speed",
        "This option sets the vertical speed of the on-screen pointer when controlled by a joypad's analog stick.",
        {
            { "2",  "Slow"    },
            { "4",  "Normal"  },
            { "8",  "Fast"    },
            { "16", "Faster"  },
            { NULL, NULL },
        },
        "4"
    },
    {
        "freej2me_pointerinnercolor",
        "Pointer Inner Color",
        "This option sets the on-screen pointer's inner color.",
        {
            { "Black",  "Black"            },
            { "Red",    "Red"              },
            { "Green",  "Green"            },
            { "Blue",   "Blue"             },
            { "Yellow", "Yellow"           },
            { "Pink",   "Pink"             },
            { "Cyan",   "Cyan"             },
            { "White",  "White (Default)"  },
            { NULL, NULL },
        },
        "White"
    },
    {
        "freej2me_pointeroutercolor",
        "Pointer Outline Color",
        "This option sets the on-screen pointer's outline color.",
        {
            { "Black",  "Black (Default)"  },
            { "Red",    "Red"              },
            { "Green",  "Green"            },
            { "Blue",   "Blue"             },
            { "Yellow", "Yellow"           },
            { "Pink",   "Pink"             },
            { "Cyan",   "Cyan"             },
            { "White",  "White"            },
            { NULL, NULL },
        },
        "Black"
    },
    {
        "freej2me_pointerclickcolor",
        "Pointer Click Indicator Color",
        "This option sets the on-screen pointer's click indicator color.",
        {
            { "Black",  "Black"            },
            { "Red",    "Red"              },
            { "Green",  "Green"            },
            { "Blue",   "Blue"             },
            { "Yellow", "Yellow (Default)" },
            { "Pink",   "Pink"             },
            { "Cyan",   "Cyan"             },
            { "White",  "White"            },
            { NULL, NULL },
        },
        "Yellow"
    },
    {
        "freej2me_spdhacknoalpha",
        "No Alpha on Blank Images (Restart Required)",
        "J2ME dictates that all images, including fully blank ones, have to be created with an alpha channel, and this includes the virtual phone's LCD screen. However, FreeJ2ME can create those without an alpha channel instead, cutting back on alpha processing for those images that usually are always fully painted with no transparency. Provides a Moderate to Large performance boost depending on the app with little to no side effects",
        {
            { "on",  "Enabled"            },
            { "off", "Disabled (Default)" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_compatnonfatalnullimages",
        "Don't throw Exception on null images",
        "In the J2ME spec, processing or loading null images must result in a NullPointerException being thrown. This has the effect of basically freezing the app's execution unless the jar has some sort of exception handling in place (which is often the case). However, 'House M.D.', for one, doesn't, and results in the app freezing by not handling the exception it just received. Enabling this allows it to be playable, at the cost of breaking games that handle null images properly.",
        {
            { "on",  "Enabled"            },
            { "off", "Disabled (Default)" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_compatcliprectongfxreset",
        "Do clipRect instead of setClip on gfx reset",
        "Fantasy Zone's 128x128 version relies on a clipRect() call being issued whenever a fullscreen draw is made instead of setClip(). So far, it seems to be the only jar in the compatibility list that needs this, and enabling it will break quite a few others.",
        {
            { "on",  "Enabled"            },
            { "off", "Disabled (Default)" },
            { NULL, NULL },
        },
        "off"
    },
    { NULL, NULL, NULL, {{0}}, NULL },
};


/* ---------------------------------- properties for frontends without support for CORE_OPTIONS below ---------------------------------- */

/* Core config variables if running on a legacy frontend without support for CORE_OPTIONS */
static const struct retro_variable vars[] =
{
    { /* Screen Resolution */
        "freej2me_resolution",
        "Phone Resolution (Core Restart may be required); 240x320|96x65|101x64|101x80|128x128|130x130|120x160|128x160|132x176|176x208|176x220|220x176|208x208|180x320|320x180|208x320|320x240|240x400|400x240|240x432|240x480|352x416|360x640|640x360|640x480|480x800|800x480" 
    },
    { /* Screen Rotation */
        "freej2me_rotate",
        "Rotate Screen; off|on" 
    },
    { /* Phone Control Type */
        "freej2me_phone",
        "Phone Key Layout; Default|LG|Motorola/SoftBank|Motorola Triplets|Motorola V8|Nokia Full Keyboard|Sagem|Siemens|Siemens Old" 
    },
    { /* LCD Backlight Color */
        "freej2me_backlightcolor",
        "LCD Backlight Color; Green|Disabled|Cyan|Orange|Violet|Red"
    },
    { /* Game FPS limit */
        "freej2me_fps",
        "Game FPS Limit; Auto|60|30|15" 
    },
    { /* Virtual Phone Sound */
        "freej2me_sound",
        "Virtual Phone Sound (Core Restart required); on|off"
    },
    { /* MIDI Soundfont */
        "freej2me_midifont",
        "MIDI Soundfont; off|on"
    },
    { /* Use Analog As Entire Keypad */
        "freej2me_analogasentirekeypad",
        "Use Analog As Entire Keypad; off|on"
    },
    { /* Logging Level */
        "freej2me_logginglevel",
        "Dump Audio Streams; 0|1|2|3|4"
    },
    { /* Dump Audio Streams */
        "freej2me_dumpaudiostreams",
        "Dump Audio Streams; off|on"
    },
    { /* Pointer Type */
        "freej2me_pointertype",
        "Pointer Type; Mouse|Touch|None"
    },
    { /* Screen Pointer X Speed */
        "freej2me_pointerxspeed",
        "Pointer X Speed; 4|1|2|8|16"
    },
    { /* Screen Pointer Y Speed */
        "freej2me_pointeryspeed",
        "Pointer Y Speed; 4|1|2|8|16"
    },
    { /* Pointer's inner color */
        "freej2me_pointerinnercolor",
        "Pointer Inner Color; White|Red|Green|Blue|Yellow|Pink|Cyan|Black"
    },
    { /* Pointer's outline color */
        "freej2me_pointeroutercolor",
        "Pointer Outline Color; Black|Red|Green|Blue|Yellow|Pink|Cyan|White"
    },
    { /* Pointer's click indicator color */
        "freej2me_pointerclickcolor",
        "Pointer Click Indicator Color; Yellow|Black|Red|Green|Blue|Pink|Cyan|White"
    },
    { /* No Alpha on Blank Images speed hack */
        "freej2me_spdhacknoalpha",
        "No Alpha on Blank Images(SpeedHack); off|on",
    },
    { /* No Alpha on Blank Images compat setting */
        "freej2me_compatnonfatalnullimages",
        "Don't throw Exception on null images; off|on",
    },
    { /* No Alpha on Blank Images compat setting */
        "freej2me_compatcliprectongfxreset",
        "Do clipRect instead of setClip on gfx reset; off|on",
    },
    { NULL, NULL },
};
