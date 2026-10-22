/*
    Follows HeartGold/SoulSilver (USA) through its screens by reading a few words of
    the DS main RAM after every frame, so the log can show what the game did when
    its Pokéwalker screen was chosen (which screen ran, which step it reached, which
    buttons were held). Addresses come from the pret/pokeheartgold decompilation.
    GPL-3.0-or-later, see PwCommon.h.
*/

#ifndef POKEWALKER_GAMETRACE_H
#define POKEWALKER_GAMETRACE_H

#include <string>
#include <vector>
#include "PwCommon.h"

namespace pokewalker
{

class GameTrace
{
public:
    /// Arms the trace for the US releases of HeartGold and SoulSilver, disarms it otherwise.
    void Reset(u32 gamecode);
    bool Active() const { return active && !stopped; }

    /// `ram`/`mask`: DS main RAM and its address mask. `keyInput`: melonDS's KeyInput
    /// (KEYINPUT in bits 0-9, X/Y in bits 16-17, all active low). Appends one line per
    /// change worth logging.
    void OnFrame(const u8* ram, u32 mask, u32 keyInput, bool touching, std::vector<std::string>* lines);

    /// Short description of the screen the game is on, for the status display.
    const std::string& CurrentScreen() const { return screenName; }

    /// Step names of the Pokéwalker screen's first part (the PC Box checks), or "".
    static const char* FirstPartStepHint(u32 step);

private:
    void Emit(std::vector<std::string>* lines, const std::string& line);

    bool active = false;
    bool stopped = false;
    bool everPlausible = false;
    int linesWritten = 0;
    int badFrames = 0;
    int idleFrames = 0;
    u64 frame = 0;

    bool haveScreen = false;
    s32 lastOverlay = 0;
    u32 lastInit = 0;
    std::string screenName;

    bool inPokewalker = false;
    bool havePwState = false;
    u32 pwExec = 0, pwProc = 0, pwPart = 0, pwNext = 0, pwStep = 0;
    u64 pwLeftFrame = 0;

    bool haveInput = false;
    u32 lastButtons = 0;
    bool lastTouch = false;
};

}

#endif
