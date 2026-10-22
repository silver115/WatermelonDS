/*
    HeartGold/SoulSilver screen trace. GPL-3.0-or-later, see PwCommon.h.

    Everything read here is described by the pret/pokeheartgold decompilation:
      - src/main.c: struct UnkStruct_02111868 _02111868 = { mainOverlayId,
        overlayManager, queuedMainOverlayId, queuedMainOverlayTemplate, ... }
      - include/overlay_manager.h: struct OverlayManager = { template {init, exec,
        exit, ovy_id}, exec_state, proc_state, args, data, ... }
      - asm/overlay_112.s: the Pokewalker screen (ov112_App_MainMenu_SelectOption_
        ConnectToPokewalker), whose data starts with {part, next part, step}.
    HeartGold and SoulSilver (USA) share these addresses: the overlays placed after
    the static module are identical between them except for version immediates.
*/

#include "GameTrace.h"

#include <cstdio>
#include <cstring>

namespace pokewalker
{

namespace
{

constexpr u32 Code(char a, char b, char c, char d)
{
    return (u32)(u8)a | ((u32)(u8)b << 8) | ((u32)(u8)c << 16) | ((u32)(u8)d << 24);
}

constexpr u32 kMainState = 0x02111868;
constexpr s32 kOverlayCount = 129;
constexpr s32 kOvyField = 1, kOvyLoadSave = 36, kOvyIntroTitle = 60, kOvyMainMenu = 74, kOvyPokewalker = 112;

// The Pokewalker screen's init/exec/exit functions (Thumb) and its first instructions:
// push {r3-r5, lr}; add r4, r0, #0; mov r0, #0; add r1, r0, #0
constexpr u32 kPwInit = 0x021E76CD, kPwExec = 0x021E7831, kPwExit = 0x021E7911;
constexpr u32 kPwInitAddr = 0x021E76CC;
constexpr u8 kPwInitCode[8] = {0x38, 0xB5, 0x04, 0x1C, 0x00, 0x20, 0x01, 0x1C};

constexpr int kMaxLines = 1500;
constexpr int kBadFramesBeforeGivingUp = 1200; // about 20 seconds
constexpr int kIdleFramesWorthNoting = 120;
constexpr u64 kInputFramesAfterPokewalker = 180;
constexpr u32 kButtonMask = 0x000303FF; // A..L, X, Y

bool InMainRam(u32 a) { return a >= 0x02000000 && a < 0x02400000; }

u32 Rd32(const u8* ram, u32 mask, u32 addr)
{
    u32 o = addr & mask & ~3u;
    return (u32)ram[o] | ((u32)ram[o + 1] << 8) | ((u32)ram[o + 2] << 16) | ((u32)ram[o + 3] << 24);
}

std::string Hex32(u32 v)
{
    char b[12];
    std::snprintf(b, sizeof(b), "%08X", v);
    return b;
}

std::string Hex8(u32 v)
{
    char b[12];
    std::snprintf(b, sizeof(b), "0x%02X", v);
    return b;
}

std::string ScreenName(s32 overlay)
{
    switch (overlay)
    {
    case -1: return "a screen without overlay";
    case kOvyField: return "overworld (overlay 1)";
    case kOvyLoadSave: return "loading the saved game (overlay 36)";
    case kOvyIntroTitle: return "intro / title screen (overlay 60)";
    case kOvyMainMenu: return "main menu (overlay 74)";
    case kOvyPokewalker: return "Pokewalker screen (overlay 112)";
    default: return "overlay " + std::to_string(overlay);
    }
}

const char* PhaseName(u32 execState)
{
    switch (execState)
    {
    case 0:
    case 1: return "starting";
    case 2: return "running";
    default: return "closing";
    }
}

const char* MainStepName(u32 procState)
{
    switch (procState)
    {
    case 0: return "fading in";
    case 1: return "opening a part";
    case 2: return "in a part";
    case 3: return "closing a part";
    case 4: return "fading out";
    default: return "unknown state";
    }
}

std::string ButtonNames(u32 pressed)
{
    static const char* const names[10] = {"A", "B", "Select", "Start", "Right", "Left", "Up", "Down", "R", "L"};
    std::string s;
    auto add = [&s](const char* n) {
        if (!s.empty()) s += "+";
        s += n;
    };
    for (int i = 0; i < 10; i++)
        if (pressed & (1u << i)) add(names[i]);
    if (pressed & (1u << 16)) add("X");
    if (pressed & (1u << 17)) add("Y");
    return s.empty() ? "none" : s;
}

}

const char* GameTrace::FirstPartStepHint(u32 step)
{
    switch (step)
    {
    case 0x00: return "checking the PC Boxes and the Pokewalker record";
    case 0x14: return "message: at least five open spaces are needed in a PC Box";
    case 0x15: return "waiting for a message, then leaving";
    case 0x16: return "leaving the Pokewalker screen";
    case 0x17: return "message: put a Pokemon in a PC Box first";
    case 0x18: return "printing a message";
    case 0x19: return "playing an animation";
    case 0x1A: return "leaving";
    default: return "";
    }
}

void GameTrace::Reset(u32 gamecode)
{
    *this = GameTrace();
    active = gamecode == Code('I', 'P', 'K', 'E') || gamecode == Code('I', 'P', 'G', 'E');
}

void GameTrace::Emit(std::vector<std::string>* lines, const std::string& line)
{
    if (stopped) return;
    if (++linesWritten >= kMaxLines)
    {
        lines->push_back("trace stopped after " + std::to_string(kMaxLines) + " lines");
        stopped = true;
        return;
    }
    lines->push_back(line);
}

void GameTrace::OnFrame(const u8* ram, u32 mask, u32 keyInput, bool touching, std::vector<std::string>* lines)
{
    if (!active || stopped || !ram) return;
    frame++;

    s32 overlay = (s32)Rd32(ram, mask, kMainState);
    u32 mgr = Rd32(ram, mask, kMainState + 4);
    s32 queued = (s32)Rd32(ram, mask, kMainState + 8);
    u32 queuedTemplate = Rd32(ram, mask, kMainState + 12);
    bool plausible = overlay >= -1 && overlay < kOverlayCount && queued >= -1 && queued < kOverlayCount &&
                     (mgr == 0 || (InMainRam(mgr) && (mgr & 3) == 0)) &&
                     (queuedTemplate == 0 || InMainRam(queuedTemplate));
    u32 init = 0, exec = 0, exitFn = 0, execState = 0, procState = 0, data = 0;
    if (plausible && mgr)
    {
        init = Rd32(ram, mask, mgr);
        exec = Rd32(ram, mask, mgr + 4);
        exitFn = Rd32(ram, mask, mgr + 8);
        s32 templateOverlay = (s32)Rd32(ram, mask, mgr + 12);
        execState = Rd32(ram, mask, mgr + 16);
        procState = Rd32(ram, mask, mgr + 20);
        data = Rd32(ram, mask, mgr + 28);
        plausible = InMainRam(init & ~1u) && InMainRam(exec & ~1u) && execState <= 3 &&
                    templateOverlay >= -1 && templateOverlay < kOverlayCount;
    }
    if (!plausible)
    {
        if (++badFrames == kBadFramesBeforeGivingUp)
            Emit(lines, std::string("trace stopped: the game's memory does not look like the original US release") +
                            (everPlausible ? " any more" : ""));
        if (badFrames >= kBadFramesBeforeGivingUp) stopped = true;
        return;
    }
    badFrames = 0;
    everPlausible = true;

    // Which screen is running. The manager pointer goes to zero for at least one frame
    // between screens, so a fresh start of the same screen is logged as well.
    if (mgr)
    {
        if (!haveScreen || overlay != lastOverlay || init != lastInit || idleFrames > 0)
        {
            haveScreen = true;
            lastOverlay = overlay;
            lastInit = init;
            screenName = ScreenName(overlay);
            Emit(lines, "screen: " + screenName + ", code " + Hex32(init));
        }
        idleFrames = 0;
    }
    else if (haveScreen && ++idleFrames == kIdleFramesWorthNoting)
    {
        screenName = "none";
        Emit(lines, "screen: none for two seconds, next queued: " +
                        (queuedTemplate ? ScreenName(queued) : std::string("nothing")));
    }

    // Inside the Pokewalker screen, follow its parts and steps.
    bool pw = mgr && overlay == kOvyPokewalker;
    if (pw && !inPokewalker)
    {
        inPokewalker = true;
        havePwState = false;
        u8 code[8];
        for (u32 i = 0; i < 8; i++) code[i] = ram[(kPwInitAddr + i) & mask];
        bool templateOk = init == kPwInit && exec == kPwExec && exitFn == kPwExit;
        bool codeOk = std::memcmp(code, kPwInitCode, sizeof(code)) == 0;
        if (templateOk && codeOk)
            Emit(lines, "Pokewalker program in memory: matches the original");
        else
        {
            char bytes[40];
            std::snprintf(bytes, sizeof(bytes), "%02X %02X %02X %02X %02X %02X %02X %02X", code[0], code[1], code[2],
                          code[3], code[4], code[5], code[6], code[7]);
            Emit(lines, "Pokewalker program in memory: NOT the original (functions " + Hex32(init) + " " + Hex32(exec) +
                            " " + Hex32(exitFn) + ", code " + bytes + ")");
        }
    }
    else if (!pw && inPokewalker)
    {
        inPokewalker = false;
        pwLeftFrame = frame;
    }
    if (pw)
    {
        bool haveData = InMainRam(data);
        u32 part = haveData ? Rd32(ram, mask, data) : 0;
        u32 next = haveData ? Rd32(ram, mask, data + 4) : 0;
        u32 step = haveData ? Rd32(ram, mask, data + 8) : 0;
        if (!havePwState || execState != pwExec || procState != pwProc || part != pwPart || next != pwNext ||
            step != pwStep)
        {
            havePwState = true;
            pwExec = execState;
            pwProc = procState;
            pwPart = part;
            pwNext = next;
            pwStep = step;
            std::string line = std::string("Pokewalker screen: ") + PhaseName(execState);
            if (execState == 2) line += std::string(", ") + MainStepName(procState);
            if (haveData)
            {
                line += ", part " + std::to_string(part) + ", next " + std::to_string(next) + ", step " + Hex8(step);
                const char* hint = part == 0 ? FirstPartStepHint(step) : "";
                if (*hint) line += std::string(" (") + hint + ")";
            }
            Emit(lines, line);
        }
    }

    // Buttons and touches while choosing the Pokewalker and while it runs.
    bool watchInput = (mgr && (overlay == kOvyMainMenu || overlay == kOvyPokewalker)) ||
                      (pwLeftFrame && frame - pwLeftFrame < kInputFramesAfterPokewalker);
    u32 pressed = ~keyInput & kButtonMask;
    if (watchInput)
    {
        if (!haveInput)
        {
            if (pressed) Emit(lines, "buttons held: " + ButtonNames(pressed));
            if (touching) Emit(lines, "touch screen: pressed");
        }
        else
        {
            if (pressed != lastButtons) Emit(lines, "buttons held: " + ButtonNames(pressed));
            if (touching != lastTouch) Emit(lines, touching ? "touch screen: pressed" : "touch screen: released");
        }
        haveInput = true;
        lastButtons = pressed;
        lastTouch = touching;
    }
    else
        haveInput = false;
}

}
