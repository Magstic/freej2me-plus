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
        "系統",
        "Free-J2ME 模擬相關設定。"
    },
    {
        "advanced_settings",
        "高级",
        "Free-J2ME 核心相關設定。"
    },
    {
        "speed_hacks",
        "优化",
        "Free-J2ME 優化相關設定。"
    },
    {
        "compat_settings",
        "兼容",
        "Free-J2ME 兼容相關設定。"
    },
};

/* Core config options if running on a frontend with support for config version 2 */
struct retro_core_option_v2_definition core_options[] =
{
    {
        "freej2me_resolution",
        "System > Phone Resolution (Core Restart may be required)",
        "解析度（需重載核心）",
        "J2ME 遊戲的解析度並非固定。若遊戲視窗過小或被截斷，請嘗試調整該選項。若遊戲在運行時更改解析度出現錯誤，請重啟遊戲。",
        "J2ME 遊戲的解析度並非固定。若遊戲視窗過小或被截斷，請嘗試調整該選項。若遊戲在運行時更改解析度出現錯誤，請重啟遊戲。",
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
        "熒幕旋轉",
        "一些遊戲（特別是觸控遊戲）通常需要旋轉螢幕。",
        "一些遊戲（特別是觸控遊戲）通常需要旋轉螢幕。",
        "system_settings",
        {
            { "off", "禁用" },
            { "on",  "啟用"  },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_phone",
        "System > Phone Key Layout",
        "鍵值佈局",
        "J2ME 平台存在不同的手機製造商，這導致遊戲之間的按鍵佈局也不盡相同。若遊戲的按鍵響應非常奇怪，請嘗試調整該選項。",
        "J2ME 平台存在不同的手機製造商，這導致遊戲之間的按鍵佈局也不盡相同。若遊戲的按鍵響應非常奇怪，請嘗試調整該選項。",
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
        "單色遊戲適用。這些遊戲會點亮 / 熄滅螢幕以產生額外效果（如‘Nokia 3410（綠）’、‘Nokia 6310i（青）’、『西門子 C55（橘子）』）。若遊戲為全彩或是您不喜歡背光效果，請停用該選項。",
        "單色遊戲適用。這些遊戲會點亮 / 熄滅螢幕以產生額外效果（如‘Nokia 3410（綠）’、‘Nokia 6310i（青）’、『西門子 C55（橘子）』）。若遊戲為全彩或是您不喜歡背光效果，請停用該選項。",
        "system_settings",
        {
            { "Disabled", "禁用" },
            { "Green",    "綠色" },
            { "Cyan",     "青色" },
            { "Orange",   "橘色" },
            { "Violet",   "紫色" },
            { "Red",      "紅色" },
            { NULL, NULL },
        },
        "Green"
    },
    {
        "freej2me_fps",
        "System > Game FPS Limit",
        "FPS 限制",
        "J2ME 在處理同步時的自由度很大，一些 FPS 不設限的遊戲在運行時幀率可能會爆炸。請根據實際情況酌情配置該選項。",
        "J2ME 在處理同步時的自由度很大，一些 FPS 不設限的遊戲在運行時幀率可能會爆炸。請根據實際情況酌情配置該選項。",
        "system_settings",
        {
            { "Auto", "Auto" },
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
        "一些遊戲的音訊尚未被編解碼器支持，該選項可模擬手機加載和播放音訊 / 音調的功能。若出現音訊未支援的情況，請嘗試停用核心的音訊。若遊戲無法運作或是開啟過久出現錯誤，請嘗試停用該選項。",
        "一些遊戲的音訊尚未被編解碼器支持，該選項可模擬手機加載和播放音訊 / 音調的功能。若出現音訊未支援的情況，請嘗試停用核心的音訊。若遊戲無法運作或是開啟過久出現錯誤，請嘗試停用該選項。",
        "system_settings",
        {
            { "on",  "啟用"  },
            { "off", "禁用" },
            { NULL, NULL },
        },
        "on"
    },
    {
        "freej2me_midifont",
        "System > MIDI Soundfont",
        "MIDI 音源",
        "『預設』使用系統或 VM 自帶的音色庫，而『自訂』則允許您在『<freej2me-lr.jar folder>/freej2me_system/customMIDI』路徑下放置 SF2 音色庫來獲得更好的聽感體驗。警告：大型音色庫可能無法正常運作！",
        "『預設』使用系統或 VM 自帶的音色庫，而『自訂』則允許您在『<freej2me-lr.jar folder>/freej2me_system/customMIDI』路徑下放置 SF2 音色庫來獲得更好的聽感體驗。警告：大型音色庫可能無法正常運作！",
        "system_settings",
        {
            { "off", "預設" },
            { "on",  "自訂" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_textfont",
        "System > Text Font",
        "文本字體",
        "『預設』使用系統或 VM 自帶的字體，而『自訂』則允許您在『<freej2me-lr.jar folder>/freej2me_system/customFont』路徑下放置字體來模擬特定手機的字體。注意：在特定螢幕解析度上，某些字體可能會偏大或偏小。",
        "『預設』使用系統或 VM 自帶的字體，而『自訂』則允許您在『<freej2me-lr.jar folder>/freej2me_system/customFont』路徑下放置字體來模擬特定手機的字體。注意：在特定螢幕解析度上，某些字體可能會偏大或偏小。",
        "system_settings",
        {
            { "off", "預設" },
            { "on",  "自訂" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_analogasentirekeypad",
        "System > Use Analog As Entire Keypad",
        "使用搖桿作為數字鍵盤",
        "在某些遊戲中，可以透過將搖桿映射為完整的數字鍵盤，以獲取更舒適的遊戲體驗。代表性遊戲：《Time Crisis Elite》，《Rayman Raving Rabbids》。",
        "在某些遊戲中，可以透過將搖桿映射為完整的數字鍵盤，以獲取更舒適的遊戲體驗。代表性遊戲：《Time Crisis Elite》，《Rayman Raving Rabbids》。",
        "system_settings",
        {
            { "off", "禁用" },
            { "on",  "啟用" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_logginglevel",
        "Advanced Settings > Logging Level",
        "日誌級別",
        "*偵錯用* 此選項允許核心將指定或更高等級的日誌記錄到『freej2me_system/FreeJ2ME.log』。",
        "*偵錯用* 此選項允許核心將指定或更高等級的日誌記錄到『freej2me_system/FreeJ2ME.log』。",
        "advanced_settings",
        {
            { "0",  "禁用"           },
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
        "Advanced Settings > Dump Audio Streams",
        "Dump 音訊串流",
        "*偵錯用* 此選項可讓核心將音訊串流資料匯出至『$SYSTEM/FreeJ2MEDumps/Audio/appname/*』。",
        "*偵錯用* 此選項可讓核心將音訊串流資料匯出至『$SYSTEM/FreeJ2MEDumps/Audio/appname/*』。",
        "advanced_settings",
        {
            { "off",  "禁用"            },
            { "on",  "啟用"              },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_pointertype",
        "Advanced Settings > Pointer Type",
        "遊標類型",
        "設定核心所用的遊標類型。請注意，僅有滑鼠支援縮放操作。",
        "設定核心所用的遊標類型。請注意，僅有滑鼠支援縮放操作。",
        "advanced_settings",
        {
            { "Mouse",  "滑鼠"                    },
            { "Touch",  "觸控"              },
            { "None",   "無遊標 / 手把模擬" },
            { NULL, NULL },
        },
        "Touch"
    },
    {
        "freej2me_pointerxspeed",
        "Advanced Settings > Pointer X Speed",
        "遊標 X 軸速度",
        "設定使用搖桿操控遊標時，其在 X 軸上的速度。",
        "設定使用搖桿操控遊標時，其在 X 軸上的速度。",
        "advanced_settings",
        {
            { "2",  "慢速"    },
            { "4",  "正常"  },
            { "8",  "快速"    },
            { "16", "極快"  },
            { NULL, NULL },
        },
        "4"
    },
    {
        "freej2me_pointeryspeed",
        "Advanced Settings > Pointer Y Speed",
        "遊標 Y 軸速度",
        "設定使用搖桿操控遊標時，在 Y 軸上的速度。",
        "設定使用搖桿操控遊標時，在 Y 軸上的速度。",
        "advanced_settings",
        {
            { "2",  "慢速"    },
            { "4",  "正常"  },
            { "8",  "快速"    },
            { "16", "極快"  },
            { NULL, NULL },
        },
        "4"
    },
    {
        "freej2me_pointerinnercolor",
        "Advanced Settings > Pointer Inner Color",
        "遊標內部顏色",
        "設定遊標的內部顏色。",
        "設定遊標的內部顏色。",
        "advanced_settings",
        {
            { "Black",  "黑"            },
            { "Red",    "紅"              },
            { "Green",  "綠"            },
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
        "遊標輪廓顏色",
        "設定遊標的輪廓顏色。",
        "設定遊標的輪廓顏色。",
        "advanced_settings",
        {
            { "Black",  "黑"            },
            { "Red",    "紅"              },
            { "Green",  "綠"            },
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
        "遊標點選顏色",
        "設定遊標的內部顏色。",
        "設定遊標的內部顏色。",
        "advanced_settings",
        {
            { "Black",  "黑"            },
            { "Red",    "紅"              },
            { "Green",  "綠"            },
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
        "無 Alpha 空白影像（需重載核心）",
        "J2ME 規定所有影像都必須有 Alpha 通道。Free-J2ME 可以避免 Alpha 通道創造這些影像，從而為部分遊戲提供優良的效能提升。",
        "J2ME 規定所有影像都必須有 Alpha 通道。Free-J2ME 可以避免 Alpha 通道創造這些影像，從而為部分遊戲提供優良的效能提升。",
        "speed_hacks",
        {
            { "on",  "啟用"            },
            { "off", "禁用" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_compatnonfatalnullimages",
        "Compatibility Settings > Don't throw Exception on null images",
        "不對 Null 圖像拋出異常",
        "根據 J2ME 規範，處理或載入 Null 影像時必須拋出 NullPointerException。如果遊戲沒有異常處理機制（少數情況），那麼遊戲將會卡死。如：無異常處理機制的《House M.D.》啟用此選項即可正常運作。不過，這可能會使進行異常處理機制的遊戲出現錯誤。",
        "根據 J2ME 規範，處理或載入 Null 影像時必須拋出 NullPointerException。如果遊戲沒有異常處理機制（少數情況），那麼遊戲將會卡死。如：無異常處理機制的《House M.D.》啟用此選項即可正常運作。不過，這可能會使進行異常處理機制的遊戲出現錯誤。",
        "compat_settings",
        {
            { "on",  "啟用"            },
            { "off", "禁用" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_compatcliprectongfxreset",
        "Compatibility Settings > Do clipRect instead of setClip on gfx reset",
        "圖形重設時使用 clipRect 取代 setClip",
        "《Fantasy Zone》的 128x128 版本依賴圖​​形重設時呼叫 clipRect() 而非 setClip()。這是目前相容性清單中唯一需要該特殊設定的遊戲，啟用該選項可能導致其他遊戲渲染異常。",
        "《Fantasy Zone》的 128x128 版本依賴圖​​形重設時呼叫 clipRect() 而非 setClip()。這是目前相容性清單中唯一需要該特殊設定的遊戲，啟用該選項可能導致其他遊戲渲染異常。",
        "compat_settings",
        {
            { "on",  "啟用"            },
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
        "freej2me_textfont",
        "Text Font",
        "Selects whether you want to use a custom text font or not. 'Default' uses the font bundled with the system or Java VM, while 'Custom' allows you to place a custom font on '<freej2me-lr.jar folder>/freej2me_system/customFont' and use it on J2ME apps to simulate a specific phone's font family. Do note that some fonts may end up being too large or too small to fit in some screen sizes.",
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
    { /* Custom Text Font */
        "freej2me_textfont",
        "Text Font; off|on"
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
