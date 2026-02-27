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

// The max amount of phone keys currently supported (might increase since KDDI and SKT/SK-VM phones tend to have more)
#define PHONE_KEYS 20

static const char *supported_encodings[] = 
{
    "-Dfile.encoding=ISO_8859_1",
    "-Dfile.encoding=Shift_JIS",
    "-Dfile.encoding=EUC_KR"
};

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
#define IMPROPER_CHILDPROC_MSG 2
#define SYSTEM_NOT_FOUND_MSG 3
#define COULD_NOT_START_MSG 4
#define UNEXPECTED_CLOS_MSG 5
#define PIPE_WRITE_FAIL_MSG 6
#define PIPE_READ_FAIL_MSG 7
#define CHILDPROC_CLOSED_MSG 8
#define CORE_HAS_LOADED_MSG 9

static const struct retro_message_ext messages[] =
{
    /* Message string to be displayed/logged */
    {"Too many frames dropped! Please restart the core.", 5000, 3, RETRO_LOG_ERROR, RETRO_MESSAGE_TARGET_ALL, RETRO_MESSAGE_TYPE_NOTIFICATION, 0},
    {"Invalid status received! Please restart the core.", 5000, 3, RETRO_LOG_ERROR, RETRO_MESSAGE_TARGET_ALL, RETRO_MESSAGE_TYPE_NOTIFICATION, 0},
    {"FreeJ2ME failed to setup pipes for communication!!! \nPlease restart the core.", 15000, 3, RETRO_LOG_ERROR, RETRO_MESSAGE_TARGET_ALL, RETRO_MESSAGE_TYPE_NOTIFICATION, 0},
#ifdef __linux__
    {"FreeJ2ME system files not found! \nMake sure > freej2me-lr.jar < is in the 'system' dir.", 15000, 3, RETRO_LOG_ERROR, RETRO_MESSAGE_TARGET_ALL, RETRO_MESSAGE_TYPE_NOTIFICATION, 0},
#elif _WIN32
    {"FreeJ2ME system files not found! \nMake sure > freej2me-lr.jar < is in the 'system' dir.", 15000, 3, RETRO_LOG_ERROR, RETRO_MESSAGE_TARGET_ALL, RETRO_MESSAGE_TYPE_NOTIFICATION, 0},
#endif
    {"FreeJ2ME could not start! \nMake sure that you have Java 6 or newer installed.", 15000, 3, RETRO_LOG_ERROR, RETRO_MESSAGE_TARGET_ALL, RETRO_MESSAGE_TYPE_NOTIFICATION, 0},
    {"FreeJ2ME closed unexpectedly!!! \nPlease restart the core.", 15000, 3, RETRO_LOG_ERROR, RETRO_MESSAGE_TARGET_ALL, RETRO_MESSAGE_TYPE_NOTIFICATION, 0},
    {"Pipe Write failed! Might be trivial, if you notice issues, please restart.", 5000, 3, RETRO_LOG_WARN, RETRO_MESSAGE_TARGET_ALL, RETRO_MESSAGE_TYPE_NOTIFICATION, 0},
    {"Pipe Read failed! Might be trivial, if you notice issues, please restart.", 5000, 3, RETRO_LOG_WARN, RETRO_MESSAGE_TARGET_ALL, RETRO_MESSAGE_TYPE_NOTIFICATION, 0},
    {"FreeJ2ME not running! Either the game crashed, or it was closed.", 5000, 3, RETRO_LOG_WARN, RETRO_MESSAGE_TARGET_ALL, RETRO_MESSAGE_TYPE_NOTIFICATION, 0},
    {"FreeJ2ME child process loaded successfully!", 3000, 1, RETRO_LOG_INFO, RETRO_MESSAGE_TARGET_OSD, RETRO_MESSAGE_TYPE_NOTIFICATION, 0},
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
    { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_SELECT,                                   "Left Softkey" },
    { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_START,                                    "Right Softkey" },
    { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_R3,                                       "CLR" },

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
        "高級",
        "Free-J2ME 核心相關設定。"
    },
    {
        "speed_hacks",
        "優化",
        "Free-J2ME 優化相關設定。"
    },
    {
        "compat_settings",
        "相容",
        "Free-J2ME 兼容相關設定。"
    },
    {
        "m3g_debug",
        "M3G 偵錯",
        "Free-J2ME M3G 渲染相關設定。"
    },
    {
        "mcv3_debug",
        "MascotCapsuleV3 Debug Settings",
        "Debug settings related to FreeJ2ME's MascotCapsuleV3 renderer."
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
            { "208x173",   NULL },
            { "176x208",   NULL },
            { "176x220",   NULL },
            { "220x176",   NULL },
            { "208x208",   NULL },
            { "180x320",   NULL },
            { "320x180",   NULL },
            { "240x240",   NULL },
            { "208x320",   NULL },
            { "240x320",   NULL },
            { "320x240",   NULL },
            { "240x400",   NULL },
            { "400x240",   NULL },
            { "240x432",   NULL },
            { "240x480",   NULL },
            { "360x360",   NULL },
            { "352x416",   NULL },
            { "360x640",   NULL },
            { "640x360",   NULL },
            { "640x480",   NULL },
            { "345x800",   NULL },
            { "800x345",   NULL },
            { "480x800",   NULL },
            { "800x480",   NULL },
            { NULL, NULL },
        },
        "240x320"
    },
    {
        "freej2me_dojaversion",
        "System > DoJa API Version",
        "DoJa API 版本",
        "DoCoMo 的 Java VM 實作被分為多組 API，且其間存在不相容情況。該設定允許核心使用特定版本的 Doja/Star API，以解決圖形、音訊或是運行方面的問題。",
        "DoCoMo 的 Java VM 實作被分為多組 API，且其間存在不相容情況。該設定允許核心使用特定版本的 Doja/Star API，以解決圖形、音訊或是運行方面的問題。",
        "system_settings",
        {
            { "10"   "DoJa-1.0" },
            { "20",  "DoJa-2.0 & 1.5 OE" },
            { "30",  "DoJa-3.0 & 2.5 OE" },
            { "35",  "DoJa-3.5" },
            { "40",  "DoJa-4.0" },
            { "41",  "DoJa-4.1" },
            { "50",  "DoJa-5.0" },
            { "51",  "DoJa-5.1" },
            { "100", "Star-1.0" },
            { "110", "Star-1.1" },
            { "120", "Star-1.2" },
            { "130", "Star-1.3" },
            { "150", "Star-1.5" },
            { "200", "Star-2.0" },
            { NULL, NULL },
        },
        "200"
    },
    {
        "freej2me_rotate",
        "System > Rotate Screen",
        "Rotate Screen",
        "一些遊戲通常需要旋轉螢幕。該選項允許你以 90° 為單位來旋轉熒幕，通常使用 270° 為基準。",
        "一些遊戲通常需要旋轉螢幕。該選項允許你以 90° 為單位來旋轉熒幕，通常使用 270° 為基準。",
        "system_settings",
        {
            { "0",   "Disabled" },
            { "90",  "90 degrees"  },
            { "180", "180 degrees"  },
            { "270", "270 degrees"  },
            { NULL, NULL },
        },
        "0"
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
            { "KDDI",                NULL },
            { "LG",                  NULL },
            { "Motorola/SoftBank",   NULL },
            { "Motorola Triplets",   NULL },
            { "Motorola V8",         NULL },
            { "Nokia Full Keyboard", NULL },
            { "Sagem",               NULL },
            { "Sharp",               NULL },
            { "Siemens",             NULL },
            { "SKT",                 NULL },
            { NULL, NULL },
        },
        "Default"
    },
    {
        "freej2me_backlightcolor",
        "System > LCD Backlight Color",
        "LCD 背光",
        "單色遊戲適用。這些遊戲會點亮 / 熄滅螢幕以產生額外效果（如‘Nokia 3410（綠）’、‘Nokia 6310i（青）’、『西門子 C55（橘）』）。若遊戲為全彩或是您不喜歡背光效果，請停用該選項。",
        "單色遊戲適用。這些遊戲會點亮 / 熄滅螢幕以產生額外效果（如‘Nokia 3410（綠）’、‘Nokia 6310i（青）’、『西門子 C55（橘）』）。若遊戲為全彩或是您不喜歡背光效果，請停用該選項。",
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
            { "Auto", "Disabled" },
            { "60",   "60 FPS"   },
            { "55",   "55 FPS"   },
            { "50",   "50 FPS"   },
            { "45",   "45 FPS"   },
            { "40",   "40 FPS"   },
            { "35",   "35 FPS"   },
            { "30",   "30 FPS"   },
            { "25",   "25 FPS"   },
            { "20",   "20 FPS"   },
            { "15",   "15 FPS"   },
            { "10",   "10 FPS"   },
            { NULL, NULL },
        },
        "Auto"
    },
    {
        "freej2me_sound",
        "System > Virtual Phone Sound (Core Restart required)",
        "模擬手機聲音",
        "一些遊戲的音訊尚未被編解碼器支持，該選項可模擬手機加載和播放音訊 / 音調的功能。若出現音訊未支援的情況，請嘗試停用核心的音訊。若遊戲無法運作或是開啟過久出現錯誤，請嘗試停用該選項。",
        "一些遊戲的音訊尚未被編解碼器支持，該選項可模擬手機加載和播放音訊 / 音調的功能。若出現音訊未支援的情況，請嘗試停用核心的音訊。若遊戲無法運作或是開啟過久出現錯誤，請嘗試停用該選項。",
        "system_settings",
        {
            { "on",  "啟用" },
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
        "freej2me_midisearchvms",
        "System > VirtualMIDISynth (Core Restart required)",
        "VirtualMIDISynth（需重載核心）",
        "啟用後，核心將搜尋系統中的 VirtualMIDISynth 設備並將其作為外部 MIDI 輸出。這可以獲得更高品質的 MIDI 播放效果。需要預先安裝 VirtualMIDISynth。",
        "啟用後，核心將搜尋系統中的 VirtualMIDISynth 設備並將其作為外部 MIDI 輸出。這可以獲得更高品質的 MIDI 播放效果。需要預先安裝 VirtualMIDISynth。",
        "system_settings",
        {
            { "on",  "啟用" },
            { "off", "禁用" },
            { NULL, NULL },
        },
        "on"
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
        "freej2me_fontoffset",
        "System > Font Size Offset",
        "字體尺寸",
        "調整字體的尺寸偏移量，以使其變大或變小。對於某些顯示過大或過小的自訂字體也有幫助。",
        "調整字體的尺寸偏移量，以使其變大或變小。對於某些顯示過大或過小的自訂字體也有幫助。",
        "system_settings",
        {
            { "-4", "-4 pt" },
            { "-3", "-3 pt" },
            { "-2", "-2 pt" },
            { "-1", "-1 pt" },
            { "0", " 0 pt (Default)" },
            { "1", " 1 pt" },
            { "2", " 2 pt" },
            { "3", " 3 pt" },
            { "4", " 4 pt" },
            { NULL, NULL },
        },
        "0"
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
        "Advanced Settings > Dump Audio Streams",
        "Dump Audio Streams",
        "*偵錯用* 該選項允許核心將音訊串流資料匯出至『$SYSTEM/FreeJ2MEDumps/Audio/appname/*』。",
        "*偵錯用* 該選項允許核心將音訊串流資料匯出至『$SYSTEM/FreeJ2MEDumps/Audio/appname/*』。",
        "advanced_settings",
        {
            { "off",  "禁用"            },
            { "on",  "啟用"              },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_dumpgraphicsdata",
        "Advanced Settings > Dump Graphics Data (Stub)",
        "Dump Graphics Data (Stub)",
        "*偵錯用* 該選項允許核心將接收到的圖形資料匯出至『$SYSTEM/FreeJ2MEDumps/Graphics/appname/*』。",
        "*偵錯用* 該選項允許核心將接收到的圖形資料匯出至『$SYSTEM/FreeJ2MEDumps/Graphics/appname/*』。",
        "advanced_settings",
        {
            { "off",  "禁用"            },
            { "on",  "啟用"              },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_deletetempkjxfiles",
        "Advanced Settings > Delete KJX files' temporary JAR/JAD",
        "清理 KJX 檔的臨時 JAR/JAD",
        "該選項可清理在執行 KJX 檔時解包出的 JAR/JAD，如果禁用，這些檔案則會保存在『$SYSTEM/FreeJ2MEDumps/KDDI/』，這對於備份或是在其他不支援 KJX 的模擬器上執行它們會很有幫助。",
        "該選項可清理在執行 KJX 檔時解包出的 JAR/JAD，如果禁用，這些檔案則會保存在『$SYSTEM/FreeJ2MEDumps/KDDI/』，這對於備份或是在其他不支援 KJX 的模擬器上執行它們會很有幫助。",
        "advanced_settings",
        {
            { "off",  "禁用"            },
            { "on",  "啟用"              },
            { NULL, NULL },
        },
        "on"
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
        "Mouse"
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
        "設定使用搖桿操控遊標時，其在 Y 軸上的速度。",
        "設定使用搖桿操控遊標時，其在 Y 軸上的速度。",
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
            { "Blue",   "藍"             },
            { "Yellow", "黃"           },
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
            { "Blue",   "藍"             },
            { "Yellow", "黃"           },
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
            { "Blue",   "藍"             },
            { "Yellow", "黃"           },
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
        "J2ME 規範要求所有影像（包括完全空白的影像及虛擬手機的 LCD 螢幕）都必須建立 Alpha 通道。此選項針對那些通常以完全不透明方式繪製的影像，在建立時省略其 Alpha 通道，從而減少不必要的處理開銷。根據遊戲不同，這能帶來顯著的效能提升，且影響可忽略不計。",
        "J2ME 規範要求所有影像（包括完全空白的影像及虛擬手機的 LCD 螢幕）都必須建立 Alpha 通道。此選項針對那些通常以完全不透明方式繪製的影像，在建立時省略其 Alpha 通道，從而減少不必要的處理開銷。根據遊戲不同，這能帶來顯著的效能提升，且影響可忽略不計。",
        "speed_hacks",
        {
            { "on",  "啟用" },
            { "off", "禁用" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_spdhackm3ghalfres",
        "Speed Hacks > Render M3G at Half Resolution",
        "M3G 半解析度渲染",
        "FreeJ2ME-Plus 對 M3G（Mobile 3D Graphics） 使用軟體渲染。若程式較為複雜，或是熒幕解析度過高，可能會導致效能問題。若 CPU 無法進行全解析度渲染，請啟用此選項。",
        "FreeJ2ME-Plus 對 M3G（Mobile 3D Graphics） 使用軟體渲染。若程式較為複雜，或是熒幕解析度過高，可能會導致效能問題。若 CPU 無法進行全解析度渲染，請啟用此選項。",
        "speed_hacks",
        {
            { "on",  "Enabled"            },
            { "off", "Disabled (Default)" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_spdhackmcv3halfres",
        "Speed Hacks > Render MascotCapsuleV3 at Half Resolution",
        "Render MascotCapsuleV3 at Half Resolution",
        "FreeJ2ME-Plus also uses a software renderer for MascotCapsuleV3 (courtesy of Roman Lahin, @rmn20), which can be intensive in more complex applications and higher phone resolutions. Use this if your cpu cannot keep up with full resolution rendering.",
        "FreeJ2ME-Plus also uses a software renderer for MascotCapsuleV3 (courtesy of Roman Lahin, @rmn20), which can be intensive in more complex applications and higher phone resolutions. Use this if your cpu cannot keep up with full resolution rendering.",
        "speed_hacks",
        {
            { "on",  "Enabled"            },
            { "off", "Disabled (Default)" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_spdhackmcv3nolight",
        "Speed Hacks > Disable MascotCapsuleV3 lighting",
        "Disable MascotCapsuleV3 lighting",
        "FreeJ2ME-Plus allows disabling all lighting operations on its MCV3 renderer. Helps games that use complex lighting setups, otherwise, doesn't have much of a performance impact.",
        "FreeJ2ME-Plus allows disabling all lighting operations on its MCV3 renderer. Helps games that use complex lighting setups, otherwise, doesn't have much of a performance impact.",
        "speed_hacks",
        {
            { "on",  "Enabled"            },
            { "off", "Disabled (Default)" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_spdhackfpsunlock",
        "Speed Hacks > Framerate Unlock Hack",
        "FPS Hack",
        "攔截 Java 方法中的延遲與同步調用，以提升應用的內部幀率。更高的激進等級會增加攔截的範圍與方法類型。『Safe』僅攔截與繪圖函數在同一方法中的 sleep() 調用，『Extended』會攔截所有 sleep() 調用，『Aggressive』甚至會攔截系統層級的時間相關調用。『FPS 限制』為非『Auto』時效果最佳。",
        "攔截 Java 方法中的延遲與同步調用，以提升應用的內部幀率。更高的激進等級會增加攔截的範圍與方法類型。『Safe』僅攔截與繪圖函數在同一方法中的 sleep() 調用，『Extended』會攔截所有 sleep() 調用，『Aggressive』甚至會攔截系統層級的時間相關調用。『FPS 限制』為非『Auto』時效果最佳。",
        "speed_hacks",
        {
            { "0",  "禁用"    },
            { "1",  "Safe"                  },
            { "2",  "Extended"              },
            { "3",  "Aggressive"            },
            { NULL, NULL },
        },
        "0"
    },
    {
        "freej2me_compatfantasyzonefix",
        "Compatibility Settings > Fix for Fantasy Zone 176x208 weird mirroring",
        "修復《Fantasy Zone(176x208)》的奇異鏡像問題",
        "《Fantasy Zone(176x208)》 的 MIDP 版在鏡像操作上完全違反規範。該遊戲在其他所有模擬器上都有問題，甚至在非 Nokia S40 的真機上也無法正常運作。該設定可修復此問題，但會破壞其他使用相同 S40 繪製路徑的應用程式。",
        "《Fantasy Zone(176x208)》 的 MIDP 版在鏡像操作上完全違反規範。該遊戲在其他所有模擬器上都有問題，甚至在非 Nokia S40 的真機上也無法正常運作。該設定可修復此問題，但會破壞其他使用相同 S40 繪製路徑的應用程式。",
        "compat_settings",
        {
            { "on",  "啟用" },
            { "off", "禁用" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_compattranstooriginongfxreset",
        "Compatibility Settings > Translate to origin on gfx reset",
        "圖像重設時平移至原點",
        "《Fantasy Zone》的『128x128』版本 依賴圖形物件在每次繪圖前平移至原點。啟用該選項可改善這類情況，並解決繪製區域莫名持續移動的問題。",
        "《Fantasy Zone》的『128x128』版本 依賴圖形物件在每次繪圖前平移至原點。啟用該選項可改善這類情況，並解決繪製區域莫名持續移動的問題。",
        "compat_settings",
        {
            { "on",  "啟用" },
            { "off", "禁用" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_compatimmediaterepaintcalls",
        "Compatibility Settings > Process canvas repaint calls immediately",
        "即時處理畫布重繪呼叫",
        "預設情況下，J2ME 會將 Canvas 的重繪呼叫加入佇列，應用需呼叫『serviceRepaints()』或使用『Serial calls』來同步繪製。某些應用程式誤用重繪佇列，可能導致死鎖而造成凍結。該選項可用於解決應用程式無故凍結的問題。",
        "預設情況下，J2ME 會將 Canvas 的重繪呼叫加入佇列，應用需呼叫『serviceRepaints()』或使用『Serial calls』來同步繪製。某些應用程式誤用重繪佇列，可能導致死鎖而造成凍結。該選項可用於解決應用程式無故凍結的問題。",
        "compat_settings",
        {
            { "on",  "啟用" },
            { "off", "禁用" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_compatoverrideplatcheck",
        "Compatibility Settings > Override Mobile Platform checks",
        "覆寫行動平台檢查",
        "部分應用程式會檢查特定的平台字串（如 'Nokia', 'Siemens S60'），若 FreeJ2ME 的平台字串不符預期，便會拒絕執行。該選項會將模擬器的平台字串覆寫為遊戲期望的內容，以通過檢查。該選項利大於弊，故預設啟用。",
        "部分應用程式會檢查特定的平台字串（如 'Nokia', 'Siemens S60'），若 FreeJ2ME 的平台字串不符預期，便會拒絕執行。該選項會將模擬器的平台字串覆寫為遊戲期望的內容，以通過檢查。該選項利大於弊，故預設啟用。",
        "compat_settings",
        {
            { "on",  "啟用" },
            { "off", "禁用" },
            { NULL, NULL },
        },
        "on"
    },
    {
        "freej2me_compatsiemensfriendlydraw",
        "Compatibility Settings > Siemens-friendly drawing methods",
        "西門子友好型繪圖方法",
        "符合 MIDP 規範的 J2ME 繪圖操作無需檢查負值平移即可正常繪製圖像。然而，一些西門子應用程式（如《Swedish Touring Car Championship》）在預設行為下無法正常運作。該選項會嘗試以一種更接近西門子虛擬機器可能採用的繪圖方式來修正平移。請注意，啟用此選項將會破壞那些使用負值平移且專為標準 J2ME 規範設計的遊戲。",
        "符合 MIDP 規範的 J2ME 繪圖操作無需檢查負值平移即可正常繪製圖像。然而，一些西門子應用程式（如《Swedish Touring Car Championship》）在預設行為下無法正常運作。該選項會嘗試以一種更接近西門子虛擬機器可能採用的繪圖方式來修正平移。請注意，啟用此選項將會破壞那些使用負值平移且專為標準 J2ME 規範設計的遊戲。",
        "compat_settings",
        {
            { "on",  "啟用" },
            { "off", "禁用" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_compatignorevolumechanges",
        "Compatibility Settings > Ignore volume changes",
        "音量變更忽略",
        "在 J2ME 子系統中，媒體播放可能是因廠商而在實作或使用上的不同，而導致差異最大的模塊。有些程式甚至會對已經停止的串流進行音量變更，這可能致使其他正在播放的媒體出現問題（如『Sonic 2』 的 MIDP 版）。啟用此選項可改善此類情況。",
        "在 J2ME 子系統中，媒體播放可能是因廠商而在實作或使用上的不同，而導致差異最大的模塊。有些程式甚至會對已經停止的串流進行音量變更，這可能致使其他正在播放的媒體出現問題（如『Sonic 2』 的 MIDP 版）。啟用此選項可改善此類情況。",
        "compat_settings",
        {
            { "on",  "啟用" },
            { "off", "禁用" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_compatmcv3horfovfix",
        "Compatibility Settings > MascotCapsuleV3 Horizontal FOV Fix",
        "MascotCapsuleV3 Horizontal FOV Fix",
        "Might help games meant for portrait resolutions work better in landscape resolutions.",
        "Might help games meant for portrait resolutions work better in landscape resolutions.",
        "compat_settings",
        {
            { "on",  "Enabled"            },
            { "off", "Disabled (Default)" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_m3grenderuntextured",
        "M3G Debug Settings > Draw only vertex colors",
        "僅渲染頂點顏色",
        "*偵錯用* 使 M3G 僅渲染帶有頂點顏色、無紋理的多邊形。對於偵錯「顏色混合 (Blending)」以及「頂點著色接縫 (Vertex coloring seams)」問題很有幫助。",
        "*偵錯用* 使 M3G 僅渲染帶有頂點顏色、無紋理的多邊形。對於偵錯「顏色混合 (Blending)」以及「頂點著色接縫 (Vertex coloring seams)」問題很有幫助。",
        "m3g_debug",
        {
            { "on",  "啟用" },
            { "off", "禁用" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_m3grenderwireframe",
        "M3G Debug Settings > Draw Wireframe",
        "渲染線框",
        "*偵錯用* 使 M3G 僅渲染線框。對於偵錯「三角形裁剪 (Triangle clipping)」和「剔除 (Culling)」問題很有幫助。",
        "*偵錯用* 使 M3G 僅渲染線框。對於偵錯「三角形裁剪 (Triangle clipping)」和「剔除 (Culling)」問題很有幫助。",
        "m3g_debug",
        {
            { "on",  "啟用" },
            { "off", "禁用" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_mcv3showheap",
        "MascotCapsuleV3 Debug Settings > Show Heap Usage",
        "Show Heap Usage",
        "Shows how much Heap is being used by MascotCapsuleV3's renderer.",
        "Shows how much Heap is being used by MascotCapsuleV3's renderer.",
        "mcv3_debug",
        {
            { "on",  "Enabled"            },
            { "off", "Disabled (Default)" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_mcv3showtimestats",
        "MascotCapsuleV3 Debug Settings > Show Time Stats",
        "Show Time Stats",
        "Shows frametime statistics for the most important blocks of MascotCapsuleV3's renderer.",
        "Shows frametime statistics for the most important blocks of MascotCapsuleV3's renderer.",
        "mcv3_debug",
        {
            { "on",  "Enabled"            },
            { "off", "Disabled (Default)" },
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
            { "208x173",   NULL },
            { "176x208",   NULL },
            { "176x220",   NULL },
            { "220x176",   NULL },
            { "208x208",   NULL },
            { "180x320",   NULL },
            { "320x180",   NULL },
            { "240x240",   NULL },
            { "208x320",   NULL },
            { "240x320",   NULL },
            { "320x240",   NULL },
            { "240x400",   NULL },
            { "400x240",   NULL },
            { "240x432",   NULL },
            { "240x480",   NULL },
            { "360x360",   NULL },
            { "352x416",   NULL },
            { "360x640",   NULL },
            { "640x360",   NULL },
            { "640x480",   NULL },
            { "345x800",   NULL },
            { "800x345",   NULL },
            { "480x800",   NULL },
            { "800x480",   NULL },
            { NULL, NULL },
        },
        "240x320"
    },
    {
        "freej2me_dojaversion",
        "DoJa API Version",
        "DoCoMo's Java VM implementation is separated into a set of different APIs with some breaking changes between major versions. This setting allows you to set a specific version that might fix any transparency, audio and gameplay issues on the DoJa/Star app you are running.",
        {
            { "10"   "DoJa-1.0" },
            { "20",  "DoJa-2.0 & 1.5 OE" },
            { "30",  "DoJa-3.0 & 2.5 OE" },
            { "35",  "DoJa-3.5" },
            { "40",  "DoJa-4.0" },
            { "41",  "DoJa-4.1" },
            { "50",  "DoJa-5.0" },
            { "51",  "DoJa-5.1" },
            { "100", "Star-1.0" },
            { "110", "Star-1.1" },
            { "120", "Star-1.2" },
            { "130", "Star-1.3" },
            { "150", "Star-1.5" },
            { "200", "Star-2.0" },
            { NULL, NULL },
        },
        "200"
    },
    {
        "freej2me_rotate",
        "Rotate Screen",
        "For applications that expect the screen to be rotated, this option allows you to set the rotation in 90-degree steps. 270 degrees is the most commonly used",
        {
            { "0",   "Disabled" },
            { "90",  "90 degrees"  },
            { "180", "180 degrees"  },
            { "270", "270 degrees"  },
            { NULL, NULL },
        },
        "0"
    },
    {
        "freej2me_phone",
        "Phone Key Layout",
        "Due to the different mobile phone manufacturers on the J2ME space, it's usual to have some games expecting a certain phone's key layout like Nokia's for example. If a game is not responding to the inputs correctly, try changing this option.",
        {
            { "Default",             NULL },
            { "KDDI",                NULL },
            { "LG",                  NULL },
            { "Motorola/SoftBank",   NULL },
            { "Motorola Triplets",   NULL },
            { "Motorola V8",         NULL },
            { "Nokia Full Keyboard", NULL },
            { "Sagem",               NULL },
            { "Sharp",               NULL },
            { "Siemens",             NULL },
            { "SKT",                 NULL },
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
            { "55",   "55 FPS"   },
            { "50",   "50 FPS"   },
            { "45",   "45 FPS"   },
            { "40",   "40 FPS"   },
            { "35",   "35 FPS"   },
            { "30",   "30 FPS"   },
            { "25",   "25 FPS"   },
            { "20",   "20 FPS"   },
            { "15",   "15 FPS"   },
            { "10",   "10 FPS"   },
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
        "Selects whether you want to use a custom text font or not. 'Default' uses the font bundled with the system or Java VM, while 'Custom' allows you to place a custom font on '<freej2me-lr.jar folder>/freej2me_system/customFont' and use it on J2ME apps to simulate a specific phone's font family. Do note that some fonts may end up being too large or too small to fit in some screen sizes, so you might need to adjust the size offset.",
        {
            { "off", "Default" },
            { "on",  "Custom" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_fontoffset",
        "Font Size Offset",
        "Adjust the offset used for font sizing in order to make text bigger or smaller. Also helps with custom fonts that might be too big or small by default.",
        {
            { "-4", "-4 pt" },
            { "-3", "-3 pt" },
            { "-2", "-2 pt" },
            { "-1", "-1 pt" },
            { "0", " 0 pt (Default)" },
            { "1", " 1 pt" },
            { "2", " 2 pt" },
            { "3", " 3 pt" },
            { "4", " 4 pt" },
            { NULL, NULL },
        },
        "0"
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
        "freej2me_dumpgraphicsdata",
        "Dump Graphics Data (Stub)",
        "This option allows FreeJ2ME to dump incoming Graphics Data into $SYSTEM/FreeJ2MEDumps/Audio/appname/*, mostly useful for debugging",
        {
            { "off",  "Disable"            },
            { "on",  "Enable"              },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_deletetempkjxfiles",
        "Delete KJX files' temporary JAR/JAD",
        "Disabling this option allows FreeJ2ME to keep the decompiled JAR and JAD files from a KDDI KJX container in $SYSTEM/FreeJ2MEDumps/KDDI/, useful if you want to archive those files outside their KJX container or try running them somewhere that doesn't handle KJX files",
        {
            { "off",  "Disable"            },
            { "on",  "Enable"              },
            { NULL, NULL },
        },
        "on"
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
        "J2ME dictates that all images, including fully blank ones, have to be created with an alpha channel, and this includes the virtual phone's LCD screen. However, FreeJ2ME can create those without an alpha channel instead, cutting back on alpha processing for those images that usually are always fully painted with no transparency. Provides a measurable performance boost depending on the app with little to no side effects",
        {
            { "on",  "Enabled"            },
            { "off", "Disabled (Default)" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_spdhackm3ghalfres",
        "Render M3G at Half Resolution",
        "FreeJ2ME-Plus uses a software renderer for M3G (Mobile 3D Graphics), which can be intensive in more complex applications and higher phone resolutions. Use this if your cpu cannot keep up with full resolution rendering.",
        {
            { "on",  "Enabled"            },
            { "off", "Disabled (Default)" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_spdhackmcv3halfres",
        "Render MascotCapsuleV3 at Half Resolution",
        "FreeJ2ME-Plus also uses a software renderer for MascotCapsuleV3 (courtesy of Roman Lahin, @rmn20), which can be intensive in more complex applications and higher phone resolutions. Use this if your cpu cannot keep up with full resolution rendering.",
        {
            { "on",  "Enabled"            },
            { "off", "Disabled (Default)" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_spdhackmcv3nolight",
        "Disable MascotCapsuleV3 lighting",
        "FreeJ2ME-Plus allows disabling all lighting operations on its MCV3 renderer. Helps games that use complex lighting setups, otherwise, doesn't have much of a performance impact.",
        {
            { "on",  "Enabled"            },
            { "off", "Disabled (Default)" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_spdhackfpsunlock",
        "Framerate Unlock Hack",
        "Hijacks calls to Java methods normally used for delays and synchronization in order to increase the app's internal framerate. Higher aggressiveness levels increase the scope and type of calls intercepted. 'Safe' tackles only sleep() calls that reside in the same function of a rendering call, 'Extended' extends it to all sleep() calls, and 'Aggressive' goes beyond and hijacks system calls used for timing as well. Works best when the FPS limiter is set to anything other than 'Auto'.",
        {
            { "0",  "Disabled (Default)"    },
            { "1",  "Safe"                  },
            { "2",  "Extended"              },
            { "3",  "Aggressive"            },
            { NULL, NULL },
        },
        "0"
    },
    {
        "freej2me_compatfantasyzonefix",
        "Fix for Fantasy Zone 176x208 weird mirroring",
        "Fantasy Zone 176x208's MIDP version goes entirely out of spec with its mirroring operation. It's broken on every other emulator out there and even on actual devices that aren't some Nokia S40 devices. This setting fixes it at the expense of breaking other applications that use the same draw path for S40.",
        {
            { "on",  "Enabled"            },
            { "off", "Disabled (Default)" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_compattranstooriginongfxreset",
        "Translate to origin on gfx reset",
        "Some apps like Fantasy Zone's 128x128 version rely on the graphics object being translated to the origin before every draw, this compatibility setting helps with that, and any case where the drawn area keeps moving in any given direction for no reason.",
        {
            { "on",  "Enabled"            },
            { "off", "Disabled (Default)" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_compatimmediaterepaintcalls",
        "Process canvas repaint calls immediately",
        "By default, J2ME expects canvas repaints to be queued up, and applications can either request serviceRepaints() or use serial calls to synchronize rendering. However, some apps might cause deadlocks by improper usage of the repaint queue and in turn, freeze. This setting may help cases where an app is freezing for no apparent reason.",
        {
            { "on",  "Enabled"            },
            { "off", "Disabled (Default)" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_compatoverrideplatcheck",
        "Override Mobile Platform checks",
        "Some applications check against specific platform strings (such as 'Nokia', 'Siemens S60'), whenever this happens, FreeJ2ME's platform string doesn't match what they expect so they refuse to run. This setting overrides any platform strings by FreeJ2ME's own. This option helps far more than breaks, so it's on by default",
        {
            { "on",  "Enabled"            },
            { "off", "Disabled (Default)" },
            { NULL, NULL },
        },
        "on"
    },
    {
        "freej2me_compatsiemensfriendlydraw",
        "Siemens-friendly drawing methods",
        "MIDP-Compliant J2ME drawing operations do no need to check for negative translation values in order to draw images properly. However, some Siemens apps like STCC (Swedish Touring Car Championship) won't work properly with the default behavior. This option tries to correct translations in a way that is closer to what Siemens' VM probably does drawing. Note that enabling this will break jars that use negative translations but are tailored for the J2ME specification.",
        {
            { "on",  "Enabled"            },
            { "off", "Disabled (Default)" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_compatignorevolumechanges",
        "Ignore volume changes",
        "Media playback is probably the J2ME subsystem whose implementation and utilization varies the most by vendor. Some applications go as far as setting volume changes to streams they already stopped beforehand, which can cause playback issues on other media that's currently playing. Sonic 2's MIDP versions are some such cases... enabling this option helps them.",
        {
            { "on",  "Enabled"            },
            { "off", "Disabled (Default)" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_compatmcv3horfovfix",
        "MascotCapsuleV3 Horizontal FOV Fix",
        "Might help games meant for portrait resolutions work better in landscape resolutions.",
        {
            { "on",  "Enabled"            },
            { "off", "Disabled (Default)" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_m3grenderuntextured",
        "Draw only vertex colors",
        "Enabling this makes M3G render only vertex colored, untextured polygons. Useful for debugging blending and vertex coloring seams.",
        {
            { "on",  "Enabled"            },
            { "off", "Disabled (Default)" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_m3grenderwireframe",
        "Draw Wireframe",
        "Enabling this makes M3G render only wireframes. Useful for debugging triangle clipping and culling.",
        {
            { "on",  "Enabled"            },
            { "off", "Disabled (Default)" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_mcv3showheap",
        "Show Heap Usage",
        "Shows how much Heap is being used by MascotCapsuleV3's renderer.",
        {
            { "on",  "Enabled"            },
            { "off", "Disabled (Default)" },
            { NULL, NULL },
        },
        "off"
    },
    {
        "freej2me_mcv3showtimestats",
        "Show Time Stats",
        "Shows frametime statistics for the most important blocks of MascotCapsuleV3's renderer.",
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
        "Phone Resolution (Core Restart may be required); 240x320|96x65|101x64|101x80|128x128|130x130|120x160|128x160|132x176|208x173|176x208|176x220|220x176|208x208|180x320|320x180|240x240|208x320|320x240|240x400|400x240|240x432|240x480|360x360|352x416|360x640|640x360|640x480|345x800|800x345|480x800|800x480"
    },
    { /* DoJa API Version */
        "freej2me_dojaversion",
        "DoJa API Version; 200|10|20|30|35|40|41|50|51|100|110|120|130|150",
    },
    { /* Screen Rotation */
        "freej2me_rotate",
        "Rotate Screen; 0|90|180|270" 
    },
    { /* Phone Control Type */
        "freej2me_phone",
        "Phone Key Layout; Default|KDDI|LG|Motorola/SoftBank|Motorola Triplets|Motorola V8|Nokia Full Keyboard|Sagem|Sharp|Siemens|SKT"
    },
    { /* LCD Backlight Color */
        "freej2me_backlightcolor",
        "LCD Backlight Color; Green|Disabled|Cyan|Orange|Violet|Red"
    },
    { /* Game FPS limit */
        "freej2me_fps",
        "Game FPS Limit; Auto|60|55|50|45|40|35|30|25|20|15|10" 
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
    { /* Custom Text Font */
        "freej2me_fontoffset",
        "Font Size Offset; 0|-4|-3|-2|-1|1|2|3|4"
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
    { /* Dump Graphics Streams */
        "freej2me_dumpgraphicsdata",
        "Dump Graphics Data (Stub); off|on",
    },
    { /* Dump KJX files' temporary JAR/JAD */
        "freej2me_deletetempkjxfiles",
        "Delete KJX files' temporary JAR/JAD; on|off",
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
        "No Alpha on Blank Images(SpeedHack); off|on"
    },
    { /* Render M3G at Half Resolution speed hack */
        "freej2me_spdhackm3ghalfres",
        "Render M3G at Half Resolution(SpeedHack); off|on",
    },
    { /* Render MascotCapsuleV3 at Half Resolution */
        "freej2me_spdhackmcv3halfres",
        "Render MascotCapsuleV3 at Half Resolution(SpeedHack); off|on",
    },
    { /* Disable MascotCapsuleV3 lighting */
        "freej2me_spdhackmcv3nolight",
        "Disable MascotCapsuleV3 lighting(SpeedHack); off|on",
    },
    { /* Framerate Unlock Hack */
        "freej2me_spdhackfpsunlock",
        "Framerate Unlock Hack; 0|1|2|3"
    },
    { /* Fix for Fantasy Zone 176x208 setting */
        "freej2me_compatfantasyzonefix",
        "Fix for Fantasy Zone 176x208 weird mirroring; off|on"
    },
    { /* Translate to origin on gfx reset setting */
        "freej2me_compattranstooriginongfxreset",
        "Translate to origin on gfx reset; off|on"
    },
    { /* Process canvas repaint calls immediately */
        "freej2me_compatimmediaterepaintcalls",
        "Process canvas repaint calls immediately; off|on"
    },
    { /* Override Mobile Platform checks */
        "freej2me_compatoverrideplatcheck",
        "Override Mobile Platform checks; on|off",
    },
    { /* Siemens-friendly drawing methods */
        "freej2me_compatsiemensfriendlydraw",
        "Siemens-friendly drawing methods; off|on",
    },
    { /* Ignore volume changes */
        "freej2me_compatignorevolumechanges",
        "Ignore volume changes; off|on",
    },
    { /* MascotCapsuleV3 Horizontal FOV Fix */
        "freej2me_compatmcv3horfovfix",
        "MascotCapsuleV3 Horizontal FOV Fix; off|on",
    },
    { /* M3G draw only vertex colors */
        "freej2me_m3grenderuntextured",
        "M3G Draw only vertex colors; off|on"
    },
    { /* M3G draw wireframe */
        "freej2me_m3grenderwireframe",
        "M3G Draw Wireframe; off|on"
    },
    { /* MascotCapsuleV3 Show Heap Usage */
        "freej2me_mcv3showheap",
        "MascotCapsuleV3 Show Heap Usage; off|on"
    },
    { /* MascotCapsuleV3 Show Time Stats */
        "freej2me_mcv3showtimestats",
        "MascotCapsuleV3 Show Time Stats; off|on"
    },
    { NULL, NULL },
};
