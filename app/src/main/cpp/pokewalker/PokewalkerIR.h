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
void OnCartReset(u32 gamecode, u32 irversion);
int OnGameSend(const u8* data, int len);
int OnGamePoll(u8* out, int maxLen);

// Virtual walker operations for the UI. Return false and set error on failure.
std::string StatusJson();
bool AddSteps(u32 steps, int daysAgo, std::string* error);
bool ResetWalker(std::string* error);
bool ImportImage(const std::vector<u8>& image, std::string* error);
bool ExportImage(std::vector<u8>* image, std::string* error);

/// True for Pokémon HeartGold / SoulSilver game codes (IPK* / IPG*).
bool IsPokewalkerGame(u32 gamecode);

}

#endif
