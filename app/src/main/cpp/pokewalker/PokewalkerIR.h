/*
    Infrared front end for melonDS Android: routes the emulated cartridge's IR
    traffic to the virtual Pokéwalker, to a TCP peer (walker emulators such as
    picowalker-sdl-tcp or PocketWalker), or nowhere.
    GPL-3.0-or-later, see PwCommon.h.
*/

#ifndef POKEWALKER_POKEWALKERIR_H
#define POKEWALKER_POKEWALKERIR_H

#include <string>
#include <vector>
#include "PwCommon.h"

namespace pokewalker::ir
{

enum class Mode : int
{
    Off = 0,
    Virtual = 1,
    Tcp = 2,
};

struct Config
{
    Mode mode = Mode::Off;
    std::string eepromPath;
    std::string logPath;   // empty = no log file
    int tcpPort = 8081;
    bool logPackets = false;
};

/// Thread-safe; may be called at any time from the UI.
void Configure(const Config& config);

// Called from the emulator thread through melonDS::Platform.
/// `rom`/`romlen` may be null/0; when given, HG/SS copies are checked once per image.
void OnCartReset(u32 gamecode, u32 irversion, const u8* rom = nullptr, u32 romlen = 0);
int OnGameSend(const u8* data, int len);
int OnGamePoll(u8* out, int maxLen);

/// Called by the frontend after every emulated frame with the DS main RAM and
/// melonDS's KeyInput, to trace HG/SS screens into the log. Cheap when inactive.
void OnFrame(const u8* mainRam, u32 mainRamMask, u32 keyInput);
/// The frontend reports touch-screen presses here (any thread).
void SetTouching(bool touching);

// Virtual walker operations for the UI. Return false and set error on failure.
std::string StatusJson();
bool AddSteps(u32 steps, int daysAgo, std::string* error);
bool ResetWalker(std::string* error);
bool ImportImage(const std::vector<u8>& image, std::string* error);
bool ExportImage(std::vector<u8>* image, std::string* error);

// The walker's own games.
bool RadarEncounter(int* slot, std::string* json, std::string* error);
bool StoreCaught(int slot, std::string* error);
bool DowsingStart(u16* item, std::string* json, std::string* error);
bool StoreDowsed(u16 item, std::string* error);

// The walker's own screen (WalkerDevice) for the on-screen Pokéwalker.
void DeviceReset();
/// 0 left, 1 middle, 2 right.
void DevicePress(int button);
/// Advances and draws the screen; copies the 96x64 shades (0-3, row by row)
/// into out when the picture changed. Returns flags: bit 0 changed, bits 4-7
/// sound to play, bits 8-9 volume (0-2), bits 12-15 shade (0-9).
int DeviceFrame(u8* out, size_t len);
/// Opens one of the walker's apps directly (see WalkerDevice::OpenApp).
void DeviceOpenApp(int index);
/// A tap on the walker's screen at walker pixel (x, y) (see WalkerDevice::Touch).
bool DeviceTouch(int x, int y);
/// The walker's home screen for widgets (96x64 shades). Returns false when the
/// walker is not open.
bool WidgetFrame(u8* out, size_t len);

/// True for Pokémon HeartGold / SoulSilver game codes (IPK* / IPG*).
bool IsPokewalkerGame(u32 gamecode);

/// Adds a line from the app (e.g. the settings a game was started with) to the log.
void Note(const std::string& text);

/// Copies a melonDS core warning/error into the Pokéwalker log (rate limited).
/// Safe to call from any thread, including from inside the emulator core.
void LogCoreMessage(bool error, const char* text);

}

#endif
