// Tests for the HeartGold/SoulSilver screen trace (GameTrace.cpp) on a simulated
// DS main RAM laid out like the game's (see the header of GameTrace.cpp).

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "GameTrace.h"

using namespace pokewalker;

static int failures = 0, checks = 0;
#define CHECK(cond)                                                                 \
    do {                                                                            \
        checks++;                                                                   \
        if (!(cond)) { failures++; std::printf("  FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); } \
    } while (0)

namespace
{

constexpr u32 kMask = 0x3FFFFF;
constexpr u32 kMainState = 0x02111868;
constexpr u32 kKeysReleased = 0x007F03FF;

struct Ram
{
    std::vector<u8> b = std::vector<u8>(kMask + 1, 0);
    void W32(u32 addr, u32 v)
    {
        u32 o = addr & kMask;
        b[o] = (u8)v; b[o + 1] = (u8)(v >> 8); b[o + 2] = (u8)(v >> 16); b[o + 3] = (u8)(v >> 24);
    }
    void Main(s32 overlay, u32 mgr, s32 queued = -1, u32 queuedTemplate = 0)
    {
        W32(kMainState, (u32)overlay); W32(kMainState + 4, mgr);
        W32(kMainState + 8, (u32)queued); W32(kMainState + 12, queuedTemplate);
    }
    void Manager(u32 mgr, u32 init, u32 exec, u32 exitFn, u32 execState, u32 procState, u32 data)
    {
        W32(mgr, init); W32(mgr + 4, exec); W32(mgr + 8, exitFn); W32(mgr + 12, 0xFFFFFFFF);
        W32(mgr + 16, execState); W32(mgr + 20, procState); W32(mgr + 24, 0); W32(mgr + 28, data);
    }
};

constexpr u32 Code(char a, char b, char c, char d)
{
    return (u32)(u8)a | ((u32)(u8)b << 8) | ((u32)(u8)c << 16) | ((u32)(u8)d << 24);
}

struct Run
{
    GameTrace trace;
    Ram ram;
    std::vector<std::string> all;
    std::vector<std::string> Frame(u32 keys = kKeysReleased, bool touching = false)
    {
        std::vector<std::string> lines;
        trace.OnFrame(ram.b.data(), kMask, keys, touching, &lines);
        all.insert(all.end(), lines.begin(), lines.end());
        return lines;
    }
    bool Logged(const std::string& part) const
    {
        for (const std::string& l : all)
            if (l.find(part) != std::string::npos) return true;
        return false;
    }
};

void TestPokewalkerVisit()
{
    std::printf("main menu -> Pokewalker screen -> title screen\n");
    Run r;
    r.trace.Reset(Code('I', 'P', 'G', 'E'));
    CHECK(r.trace.Active());

    // Boot: zeroed memory is plausible but shows no screen.
    for (int i = 0; i < 5; i++) CHECK(r.Frame().empty());

    // Main menu.
    const u32 menuMgr = 0x0220A000;
    r.ram.Manager(menuMgr, 0x0222A001, 0x0222A101, 0x0222A201, 2, 5, 0x02230000);
    r.ram.Main(74, menuMgr);
    auto lines = r.Frame();
    CHECK(lines.size() == 1 && lines[0].find("main menu (overlay 74)") != std::string::npos);
    CHECK(r.trace.CurrentScreen() == "main menu (overlay 74)");
    CHECK(r.Frame().empty());
    lines = r.Frame(kKeysReleased & ~1u); // A held
    CHECK(lines.size() == 1 && lines[0] == "buttons held: A");
    lines = r.Frame(kKeysReleased & ~1u, true);
    CHECK(lines.size() == 1 && lines[0] == "touch screen: pressed");
    lines = r.Frame();
    CHECK(lines.size() == 2);

    // Between screens for one frame, then the Pokewalker screen starts.
    r.ram.Main(-1, 0, 112, 0x021F5728);
    CHECK(r.Frame().empty());
    const u32 pwMgr = 0x0220A000; // the heap hands out the same block again
    const u8 code[8] = {0x38, 0xB5, 0x04, 0x1C, 0x00, 0x20, 0x01, 0x1C};
    std::memcpy(&r.ram.b[0x021E76CC & kMask], code, sizeof(code));
    r.ram.Manager(pwMgr, 0x021E76CD, 0x021E7831, 0x021E7911, 1, 0, 0);
    r.ram.Main(112, pwMgr);
    lines = r.Frame();
    CHECK(lines.size() == 3);
    CHECK(r.Logged("screen: Pokewalker screen (overlay 112), code 021E76CD"));
    CHECK(r.Logged("Pokewalker program in memory: matches the original"));
    CHECK(r.Logged("Pokewalker screen: starting"));

    const u32 data = 0x02280000;
    r.ram.Manager(pwMgr, 0x021E76CD, 0x021E7831, 0x021E7911, 2, 0, data);
    lines = r.Frame();
    CHECK(lines.size() == 1 && lines[0].find("running, fading in, part 0, next 0, step 0x00 (checking the PC Boxes") !=
                                   std::string::npos);
    r.ram.W32(data + 8, 0x14);
    lines = r.Frame();
    CHECK(lines.size() == 1 && lines[0].find("five open spaces") != std::string::npos);
    CHECK(r.Frame().empty());
    r.ram.W32(data + 4, 0xB);
    r.ram.W32(data + 8, 0x1A);
    lines = r.Frame();
    CHECK(lines.size() == 1 && lines[0].find("next 11, step 0x1A (leaving)") != std::string::npos);
    r.ram.Manager(pwMgr, 0x021E76CD, 0x021E7831, 0x021E7911, 3, 0, data);
    lines = r.Frame();
    CHECK(lines.size() == 1 && lines[0].find("Pokewalker screen: closing") != std::string::npos);

    // Back to the title screen; buttons are still watched for a few seconds.
    r.ram.Main(-1, 0, 60, 0x02200000);
    CHECK(r.Frame().empty());
    r.ram.Manager(0x0220B000, 0x021E5901, 0x021E5A01, 0x021E5B01, 1, 0, 0);
    r.ram.Main(60, 0x0220B000);
    lines = r.Frame(kKeysReleased & ~(1u << 3)); // Start
    CHECK(lines.size() == 2);
    CHECK(r.Logged("screen: intro / title screen (overlay 60)"));
    CHECK(r.Logged("buttons held: Start"));
    for (int i = 0; i < 300; i++) r.Frame();
    size_t before = r.all.size();
    r.Frame(kKeysReleased & ~(1u << 16));      // X on the title screen, long after: not logged
    CHECK(r.all.size() == before);
}

void TestWrongProgramAndLayout()
{
    std::printf("unexpected code, other releases, unrecognised memory\n");
    Run r;
    r.trace.Reset(Code('I', 'P', 'K', 'E'));
    const u32 mgr = 0x0220A000;
    r.ram.Manager(mgr, 0x021E8001, 0x021E8101, 0x021E8201, 2, 0, 0);
    r.ram.Main(112, mgr);
    r.Frame();
    CHECK(r.Logged("Pokewalker program in memory: NOT the original (functions 021E8001 021E8101 021E8201"));

    // Garbage for long enough stops the trace.
    r.ram.Main(5000, 0x12345678);
    for (int i = 0; i < 1199; i++) r.Frame();
    CHECK(r.trace.Active());
    r.Frame();
    CHECK(!r.trace.Active());
    CHECK(r.Logged("trace stopped: the game's memory does not look like the original US release any more"));

    Run eu;
    eu.trace.Reset(Code('I', 'P', 'K', 'P'));
    CHECK(!eu.trace.Active());
    eu.ram.Main(74, mgr);
    CHECK(eu.Frame().empty());
}

void TestLineLimit()
{
    std::printf("line limit\n");
    Run r;
    r.trace.Reset(Code('I', 'P', 'G', 'E'));
    const u32 mgr = 0x0220A000;
    r.ram.Manager(mgr, 0x0222A001, 0x0222A101, 0x0222A201, 2, 5, 0x02230000);
    r.ram.Main(74, mgr);
    for (int i = 0; i < 4000; i++) r.Frame(i & 1 ? kKeysReleased : (kKeysReleased & ~2u));
    CHECK(r.all.size() == 1500);
    CHECK(r.all.back() == "trace stopped after 1500 lines");
    CHECK(!r.trace.Active());
}

}

int main()
{
    TestPokewalkerVisit();
    TestWrongProgramAndLayout();
    TestLineLimit();
    std::printf("%d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
