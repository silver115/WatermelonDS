// Tests for the on-screen walker (WalkerDevice.cpp): screens drawn from the
// walker's EEPROM images, the menu, Poké Radar with its battle and "switch?",
// dowsing, trainer card, Pokémon & items and settings.
//
// Set PW_DEVICE_DUMP=<dir> to also write each checked screen as a .pgm picture.

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <string>
#include <unistd.h>
#include <vector>

#include "EepromStore.h"
#include "PixelFont.h"
#include "PwCommon.h"
#include "VirtualPokewalker.h"
#include "WalkerDevice.h"

using namespace pokewalker;

static int failures = 0, checks = 0;
#define CHECK(cond)                                                                 \
    do {                                                                            \
        checks++;                                                                   \
        if (!(cond)) { failures++; std::printf("  FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); } \
    } while (0)

namespace
{

constexpr int W = WalkerDevice::Width, H = WalkerDevice::Height;

// ---- building a walker image with recognisable pictures -------------------------------

std::vector<u8> Encode(int w, int h, const std::vector<u8>& px)
{
    std::vector<u8> out((size_t)w * h / 4, 0);
    for (int s = 0; s < h / 8; s++)
        for (int c = 0; c < w; c++)
        {
            u8 hi = 0, lo = 0;
            for (int j = 0; j < 8; j++)
            {
                u8 v = px[(size_t)(s * 8 + j) * w + c];
                hi |= (u8)(((v >> 1) & 1) << j);
                lo |= (u8)((v & 1) << j);
            }
            out[(size_t)(s * w + c) * 2] = hi;
            out[(size_t)(s * w + c) * 2 + 1] = lo;
        }
    return out;
}

std::vector<u8> Decode(const u8* data, int w, int h)
{
    std::vector<u8> px((size_t)w * h);
    for (int s = 0; s < h / 8; s++)
        for (int c = 0; c < w; c++)
        {
            u8 hi = data[(s * w + c) * 2], lo = data[(s * w + c) * 2 + 1];
            for (int j = 0; j < 8; j++) px[(size_t)(s * 8 + j) * w + c] = (u8)((((hi >> j) & 1) << 1) | ((lo >> j) & 1));
        }
    return px;
}

void DrawText(std::vector<u8>& px, int w, int x, int y, const std::string& s, u8 v)
{
    for (char ch : s)
    {
        const u8* g = font::Glyph(ch);
        for (int r = 0; r < font::GlyphHeight; r++)
            for (int c = 0; c < font::GlyphWidth; c++)
                if ((g[r] & (1 << (font::GlyphWidth - 1 - c))) && x + c < w && y + r >= 0) px[(size_t)(y + r) * w + x + c] = v;
        x += font::Advance;
    }
}

// A framed picture with a label, like the game's images but easy to read in dumps.
std::vector<u8> Picture(int w, int h, const std::string& label, bool framed = true, u8 fill = 0)
{
    std::vector<u8> px((size_t)w * h, fill);
    if (framed)
        for (int x = 0; x < w; x++)
            for (int y = 0; y < h; y++)
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) px[(size_t)y * w + x] = 2;
    int tw = (int)label.size() * font::Advance - 1;
    DrawText(px, w, std::max(1, (w - tw) / 2), std::max(0, (h - font::GlyphHeight) / 2), label, 3);
    return Encode(w, h, px);
}

void Put(std::vector<u8>& img, u32 addr, const std::vector<u8>& data)
{
    std::memcpy(&img[addr], data.data(), data.size());
}

void PutReliable(std::vector<u8>& img, u32 a1, u32 a2, const u8* data, size_t len)
{
    u8 chk = EepromStore::ReliableChecksum(data, len);
    for (u32 a : {a1, a2})
    {
        std::memcpy(&img[a], data, len);
        img[a + len] = chk;
    }
}

struct Holdings
{
    u16 caught[3] = {0, 0, 0};
    u16 dowsed[3] = {0, 0, 0};
};

std::vector<u8> BuildWalker(const std::vector<u8>& factory, bool registered, bool hasPokemon, u16 watts,
                            const Holdings& held = {}, int radarSlotFavoured = 0)
{
    std::vector<u8> img = factory;
    // identity: flags; health: steps, watts, days, settings
    std::vector<u8> ident(img.begin() + ee::Identity1, img.begin() + ee::Identity1 + ee::IdentitySize);
    ident[id::Flags] = (u8)((registered ? id::FlagRegistered : 0) | (hasPokemon ? id::FlagHasPokemon : 0));
    PutReliable(img, ee::Identity1, ee::Identity2, ident.data(), ident.size());
    std::vector<u8> health(img.begin() + ee::Health1, img.begin() + ee::Health1 + ee::HealthSize);
    wr32be(&health[hd::TotalSteps], 56789);
    wr32be(&health[hd::TodaySteps], 1234);
    wr16be(&health[hd::TotalDays], 12);
    wr16be(&health[hd::Watts], watts);
    health[hd::Settings] = 0x24;
    PutReliable(img, ee::Health1, ee::Health2, health.data(), health.size());
    for (int k = 0; k < 7; k++) wr32be(&img[ee::History + k * 4], 1000u * (k + 1) + 11u);

    // route: walking Slowpoke, route Pokémon Pikachu / Pidgey / Rattata, ten items
    u8* ri = &img[ee::RouteInfo];
    wr16le(&ri[route::Species], 79);
    const u16 mons[3] = {25, 16, 19};
    for (int i = 0; i < 3; i++)
    {
        wr16le(&ri[route::Pokemon + i * 16], mons[i]);
        ri[route::Pokemon + i * 16 + route::Level] = (u8)(5 + i);
        wr16le(&ri[route::PokemonSteps + i * 2], i == radarSlotFavoured ? 0 : 60000);
        ri[route::PokemonPercent + i] = 100;
    }
    for (int i = 0; i < 10; i++)
    {
        wr16le(&ri[route::Items + i * 2], (u16)(17 + i));
        wr16le(&ri[route::ItemSteps + i * 2], 0);
        ri[route::ItemPercent + i] = 100;
    }
    for (int i = 0; i < 3; i++)
    {
        wr16le(&img[ee::Caught + i * 16], held.caught[i]);
        wr16le(&img[ee::Dowsed + i * 4], held.dowsed[i]);
    }

    // pictures
    const char* digits = "0123456789:-/";
    for (int i = 0; i < 13; i++) Put(img, 0x0280 + i * 32, Picture(8, 16, std::string(1, digits[i]), false));
    Put(img, 0x0420, Picture(16, 16, "W"));
    Put(img, 0x0460, Picture(8, 8, "", true, 3));
    Put(img, 0x0488, Picture(8, 8, "", true, 1));
    for (int i = 0; i < 12; i++) Put(img, 0x04F8 + i * 16, Picture(8, 8, "", true, 3));
    Put(img, 0x05B8, Picture(8, 16, "<", false));
    Put(img, 0x05D8, Picture(8, 16, ">", false));
    Put(img, 0x05F8, Picture(8, 16, "R", false));
    for (int i = 0; i < 6; i++) Put(img, 0x0670 + i * 96, Picture(24, 16, std::string(1, "!H&SN."[i])));
    const char* titles[6] = {"RADAR", "DOWSING", "CONNECT", "CARD", "ITEMS", "SETTINGS"};
    for (int i = 0; i < 6; i++) Put(img, 0x0910 + i * 320, Picture(80, 16, titles[i]));
    for (int i = 0; i < 6; i++) Put(img, 0x1090 + i * 64, Picture(16, 16, std::string(1, "RDCTIS"[i])));
    Put(img, 0x1210, Picture(16, 16, "P"));
    Put(img, 0x1250, Picture(80, 16, "LYRA"));
    Put(img, 0x1390, Picture(16, 16, "r"));
    Put(img, 0x13D0, Picture(40, 16, "STEPS"));
    Put(img, 0x1470, Picture(32, 16, "TIME"));
    Put(img, 0x14F0, Picture(40, 16, "DAYS"));
    Put(img, 0x1590, Picture(64, 16, "TOTAL DAYS"));
    Put(img, 0x1690, Picture(40, 16, "SOUND"));
    Put(img, 0x1730, Picture(40, 16, "SHADE"));
    for (int i = 0; i < 3; i++) Put(img, 0x17D0 + i * 96, Picture(24, 16, std::string(1, "0LH"[i])));
    Put(img, 0x18F0, Picture(8, 16, "", true, 2));
    Put(img, 0x1910, Picture(32, 24, "BOX"));
    Put(img, 0x1A90, Picture(32, 24, "GIFT"));
    Put(img, 0x1B50, Picture(16, 16, "", true, 3));
    Put(img, 0x1B90, Picture(16, 16, "", true, 1));
    Put(img, 0x1CB0, Picture(32, 24, "BUSH", true, 1));
    for (int i = 0; i < 3; i++) Put(img, 0x1D70 + i * 64, Picture(16, 16, std::string((size_t)i + 1, '!')));
    Put(img, 0x1E30, Picture(16, 16, "*"));
    Put(img, 0x1F70, Picture(32, 24, "POOF"));
    Put(img, 0x2030, Picture(8, 8, "", true, 3));
    Put(img, 0x2040, Picture(8, 8, "+", false));
    Put(img, 0x2050, Picture(96, 32, "ATK  CATCH  EVD"));
    Put(img, 0x2350, Picture(32, 32, "PW"));
    Put(img, 0x2450, Picture(8, 16, ")", false));
    struct T { u32 a; const char* s; } texts[] = {
        {0x2530, "CONNECTING..."}, {0x4330, "NEED MORE WATTS."}, {0x44B0, "NO POKEMON HELD!"}, {0x4630, "NOTHING HELD!"},
        {0x47B0, "DISCOVER AN ITEM"}, {0x4930, "FOUND!"}, {0x4AB0, "NOTHING FOUND!"}, {0x4C30, "IT'S NEAR!"},
        {0x4DB0, "IT'S FAR AWAY..."}, {0x4F30, "FIND A POKEMON!"}, {0x5230, "IT GOT AWAY..."}, {0x53B0, "APPEARED!"},
        {0x5530, "WAS CAUGHT!"}, {0x56B0, "FLED..."}, {0x5830, "WAS TOO STRONG."}, {0x59B0, "ATTACKED!"},
        {0x5B30, "EVADED!"}, {0x5CB0, "A CRITICAL HIT!"}, {0x5FB0, "THREW A BALL."}, {0x6130, "ALMOST HAD IT!"},
        {0x62B0, "STARE DOWN!"},
    };
    for (const T& t : texts) Put(img, t.a, Picture(96, 16, t.s, false));
    Put(img, 0x8B30, Picture(80, 16, "SWITCH?", false));
    Put(img, 0x8FBE, Picture(32, 24, "ROUTE"));
    Put(img, 0x907E, Picture(80, 16, "SIGHTSEEING"));
    Put(img, 0x91BE, Picture(32, 24, "SLW1"));
    Put(img, 0x91BE + 192, Picture(32, 24, "SLW2"));
    Put(img, 0x933E, Picture(64, 48, "SLOWPOKE 1"));
    Put(img, 0x933E + 768, Picture(64, 48, "SLOWPOKE 2"));
    Put(img, 0x993E, Picture(80, 16, "SLOWPOKE"));
    const char* small[3] = {"PIKA", "PIDG", "RATT"};
    const char* names[3] = {"PIKACHU", "PIDGEY", "RATTATA"};
    for (int i = 0; i < 3; i++)
    {
        Put(img, 0x9A7E + i * 384, Picture(32, 24, std::string(small[i]) + "1"));
        Put(img, 0x9A7E + i * 384 + 192, Picture(32, 24, std::string(small[i]) + "2"));
        Put(img, 0xA4FE + i * 320, Picture(80, 16, names[i]));
    }
    for (int i = 0; i < 10; i++) Put(img, 0xA8BE + i * 384, Picture(96, 16, "ITEM " + std::to_string(i), false));
    return img;
}

// ---- running the device ----------------------------------------------------------

struct Rig
{
    s64 ms = 1000;
    s64 unixSec = 1792600000;
    std::deque<u32> script; // device random numbers; 0 once empty
    VirtualPokewalker walker;
    WalkerDevice device;
    std::vector<u8> factory;

    Rig()
        : device(walker, [this]() { u32 v = script.empty() ? 0 : script.front(); if (!script.empty()) script.pop_front(); return v; },
                 []() { return 13 * 60 + 5; })
    {
        walker.SetClocks([this]() { return ms; }, [this]() { return unixSec; }, [](s64 t) { return t / 86400; });
        u32 seed = 99;
        // The walker's own roll: always 0 (so every slot with 100% qualifies).
        walker.SetRandom([seed]() mutable { seed = seed * 1103515245 + 12345; return 0u; });
        static int counter = 0;
        char path[256];
        std::snprintf(path, sizeof(path), "/tmp/pw_device_%d_%d.bin", (int)getpid(), counter++);
        std::remove(path);
        std::remove((std::string(path) + ".meta").c_str());
        std::string err;
        if (!walker.Open(path, &err)) std::printf("  open failed: %s\n", err.c_str());
        factory = walker.ExportImage();
    }

    void Load(const std::vector<u8>& img)
    {
        std::string err;
        if (!walker.ImportImage(img, &err)) std::printf("  import failed: %s\n", err.c_str());
        device.Reset(ms);
        device.Update(ms);
    }
    void Step(int dt) { ms += dt; device.Update(ms); }
    void Wait(int total) { for (int t = 0; t < total; t += 50) Step(50); }
    void Press(WalkerDevice::Button b) { device.Press(b, ms); device.Update(ms); }
    void Left() { Press(WalkerDevice::Button::Left); }
    void Right() { Press(WalkerDevice::Button::Right); }
    void Center() { Press(WalkerDevice::Button::Center); }
    std::string Screen() const { return device.ScreenName(); }

    // True when the frame region equals the walker image at addr.
    bool Shows(int x, int y, int w, int h, u32 addr) const
    {
        std::vector<u8> px = Decode(walker.Image() + addr, w, h);
        const auto& f = device.Frame();
        for (int j = 0; j < h; j++)
            for (int i = 0; i < w; i++)
                if (f[(size_t)(y + j) * W + x + i] != px[(size_t)j * w + i]) return false;
        return true;
    }
    int Ink(int x, int y, int w, int h) const
    {
        int n = 0;
        const auto& f = device.Frame();
        for (int j = y; j < y + h; j++)
            for (int i = x; i < x + w; i++) n += f[(size_t)j * W + i] != 0;
        return n;
    }
    void Dump(const char* name) const
    {
        const char* dir = std::getenv("PW_DEVICE_DUMP");
        if (!dir) return;
        std::string path = std::string(dir) + "/" + name + ".pgm";
        FILE* fp = std::fopen(path.c_str(), "wb");
        if (!fp) return;
        std::fprintf(fp, "P5\n%d %d\n3\n", W, H);
        for (u8 v : device.Frame()) std::fputc(3 - v, fp);
        std::fclose(fp);
    }
    std::string Status() { return walker.StatusJson(); }
};

bool Contains(const std::string& hay, const std::string& needle) { return hay.find(needle) != std::string::npos; }

// ---- tests ---------------------------------------------------------------------------

void TestHomeAndMenu()
{
    std::printf("home screen and menu\n");
    Rig r;
    r.Load(BuildWalker(r.factory, true, true, 100));
    CHECK(r.Screen() == "home");
    CHECK(r.Shows(0, 0, 64, 48, 0x933E));             // walking Slowpoke, frame 1
    CHECK(r.Shows(64, 24, 32, 24, 0x8FBE));           // route picture
    CHECK(r.Shows(64, 48, 8, 16, 0x0280 + 1 * 32));   // "1234" steps today, right-aligned
    CHECK(r.Shows(88, 48, 8, 16, 0x0280 + 4 * 32));
    r.Dump("home");
    r.Wait(500);
    CHECK(r.Shows(0, 0, 64, 48, 0x933E + 768));       // frame 2
    r.Center();                                        // the Pokémon reacts
    CHECK(r.Ink(64, 0, 24, 16) > 0);
    CHECK(r.device.TakeSound() == WalkerDevice::Sound::Good);

    r.Right();
    CHECK(r.Screen() == "menu");
    CHECK(r.Shows(8, 0, 80, 16, 0x0910));             // "Poké Radar"
    CHECK(r.Shows(0, 24, 16, 16, 0x1090));
    CHECK(r.Shows(0, 48, 8, 16, 0x0280 + 1 * 32));    // cost "10"
    CHECK(r.Shows(80, 48, 16, 16, 0x0420));           // watts symbol
    CHECK(r.Shows(56, 48, 8, 16, 0x0280 + 1 * 32));   // "100" watts
    r.Dump("menu_radar");
    for (int i = 1; i < 6; i++)
    {
        r.Right();
        CHECK(r.Shows(8, 0, 80, 16, 0x0910 + (u32)i * 320));
    }
    r.Right();
    CHECK(r.Screen() == "home");
    r.Left();
    CHECK(r.Screen() == "menu");
    CHECK(r.Shows(8, 0, 80, 16, 0x0910 + 5 * 320));   // left from home lands on Settings
}

void TestUnpaired()
{
    std::printf("unpaired walker and empty walker\n");
    Rig r;
    r.device.Reset(r.ms);
    r.device.Update(r.ms);
    CHECK(r.Screen() == "home");
    CHECK(r.Ink(0, 8, 96, 48) > 100);                 // our own "not paired yet" text
    r.Dump("unpaired");
    r.Right();
    CHECK(r.Screen() == "home");                      // nothing to open yet

    r.Load(BuildWalker(r.factory, true, false, 50));
    CHECK(r.Ink(0, 8, 96, 32) > 50);                  // "no Pokémon is on a stroll"
    r.Right();
    r.Center();                                       // Poké Radar without a Pokémon
    CHECK(r.Screen() == "message");
    CHECK(r.Shows(0, 16, 96, 16, 0x44B0));            // "no Pokémon held!"
    r.Wait(2100);
    CHECK(r.Screen() == "menu");
}

void TestRadarCatch()
{
    std::printf("Poke Radar: bushes, battle, catch\n");
    Rig r;
    r.Load(BuildWalker(r.factory, true, true, 100));
    r.Right();                                        // menu: Poké Radar
    r.script = {0, 2};                                // bubble after 1000 ms, on bush 2
    r.Center();
    CHECK(r.Screen() == "radar");
    CHECK(Contains(r.Status(), "\"watts\":90"));      // 10 W spent on the way in
    CHECK(r.Shows(64, 0, 32, 24, 0x1CB0));            // bush 1 (bush 0 has the cursor)
    CHECK(r.Shows(0, 48, 96, 16, 0x4F30));            // "find a Pokémon!"
    r.Right();
    r.Right();                                        // cursor on bush 2 (bottom left)
    r.Wait(1050);
    CHECK(r.Ink(16, 24, 16, 16) > 0);                 // "!" over bush 2
    r.Dump("radar_bubble");
    r.Center();
    CHECK(r.device.TakeSound() == WalkerDevice::Sound::Good);
    r.Wait(1300);                                     // click, cloud, then the battle
    CHECK(r.Screen() == "battle");
    CHECK(r.Shows(64, 8, 32, 24, 0x9A7E) || r.Shows(64, 8, 32, 24, 0x9A7E + 192)); // wild Pikachu
    CHECK(r.Shows(8, 32, 80, 16, 0xA4FE));            // "Pikachu"
    CHECK(r.Shows(0, 48, 96, 16, 0x53B0));            // "appeared!"
    r.Dump("battle_appeared");
    r.Wait(1700);
    CHECK(r.Shows(0, 32, 96, 32, 0x2050));            // attack / evade / catch
    r.Dump("battle_choose");

    // Attack against an attack: both lose one HP.
    r.script = {10};
    r.Left();
    CHECK(r.Shows(8, 32, 80, 16, 0x993E));            // "Slowpoke"
    CHECK(r.Shows(0, 48, 96, 16, 0x59B0));            // "attacked!"
    r.Wait(1150);
    CHECK(r.Shows(8, 32, 80, 16, 0xA4FE));            // "Pikachu attacked!"
    r.Wait(1150);
    CHECK(r.Ink(88, 0, 8, 8) == 0);                    // wild HP 3 of 4
    CHECK(r.Ink(24, 0, 8, 8) == 0);                    // our HP 3 of 4
    CHECK(r.Shows(0, 32, 96, 32, 0x2050));

    // Throw a ball: chance 45% at 3 HP, the script's roll 0 catches it.
    r.script = {0};
    r.Center();
    CHECK(r.Shows(0, 48, 96, 16, 0x5FB0));            // "threw a ball."
    r.Wait(1350);
    CHECK(r.Shows(0, 48, 96, 16, 0x5530));            // "was caught!"
    r.Dump("battle_caught");
    r.Wait(1850);
    CHECK(r.Screen() == "menu");
    std::string st = r.Status();
    CHECK(Contains(st, "\"caught\":1"));
    CHECK(rd16le(r.walker.Image() + ee::Caught) == 25);
}

void TestRadarMisses()
{
    std::printf("Poke Radar: wrong bush, too slow\n");
    Rig r;
    r.Load(BuildWalker(r.factory, true, true, 30));
    r.Right();
    r.script = {0, 1};
    r.Center();
    r.Wait(1050);                                     // "!" on bush 1, cursor still on 0
    r.Center();
    CHECK(r.Shows(0, 48, 96, 16, 0x5230));            // "it got away..."
    r.Wait(2100);
    CHECK(r.Screen() == "menu");

    r.script = {0, 3, 0, 3};                          // two bubbles nobody presses
    r.Center();
    CHECK(Contains(r.Status(), "\"watts\":10"));
    r.Wait(1050 + 1550 + 850 + 1550);
    CHECK(r.Shows(0, 48, 96, 16, 0x5230));
    r.Wait(2100);
    CHECK(r.Screen() == "menu");

    r.Center();                                       // 10 W left: plays once more
    CHECK(r.Screen() == "radar");
    r.Left();
    r.script = {};
    r.Center();                                       // pressing before any "!" scares it off
    r.Wait(2100);
    r.Center();                                       // 0 W
    CHECK(r.Screen() == "message");
    CHECK(r.Shows(0, 16, 96, 16, 0x4330));            // "need more watts."
}

void TestRadarRarerSlotAndSwitch()
{
    std::printf("Poke Radar: rarer Pokemon need more rounds, switch? when full\n");
    Rig r;
    Holdings held;
    held.caught[0] = 25;
    held.caught[1] = 19;
    held.caught[2] = 19;
    r.Load(BuildWalker(r.factory, true, true, 100, held, 1)); // only Pidgey (slot 1) qualifies
    r.Right();
    r.script = {0, 0, 0, 0};                          // round 1 and round 2 on bush 0
    r.Center();
    r.Wait(1050);
    r.Center();                                       // round 1 done
    CHECK(r.Screen() == "radar");
    r.Wait(450 + 750);                                // click, then the next "!" after 700 ms
    CHECK(r.Ink(16, 0, 16, 16) > 0);
    r.Center();                                       // round 2 done
    r.Wait(1300);
    CHECK(r.Screen() == "battle");
    CHECK(r.Shows(8, 32, 80, 16, 0xA4FE + 320));      // "Pidgey"
    r.Wait(1700);
    r.script = {0};
    r.Center();                                       // catch at full HP: 30%, roll 0
    r.Wait(1350 + 1850);
    CHECK(r.Screen() == "switch");
    CHECK(r.Shows(8, 0, 80, 16, 0x8B30));             // "switch?"
    r.Dump("switch");
    CHECK(rd16le(r.walker.Image() + ee::Caught + 16) == 19);
    r.Right();                                        // replace the second one
    r.Center();
    CHECK(r.Screen() == "menu");
    CHECK(rd16le(r.walker.Image() + ee::Caught + 16) == 16);
    CHECK(rd16le(r.walker.Image() + ee::Caught) == 25);

    // keep the old ones
    r.script = {0, 0, 0, 0};
    r.Center();
    r.Wait(1050);
    r.Center();
    r.Wait(1200);
    r.Center();
    r.Wait(1300 + 1700);
    r.script = {0};
    r.Center();
    r.Wait(1350 + 1850);
    CHECK(r.Screen() == "switch");
    r.Left();                                         // the "return" option
    r.Center();
    CHECK(r.Screen() == "menu");
    CHECK(rd16le(r.walker.Image() + ee::Caught + 32) == 19);
}

void TestBattleOutcomes()
{
    std::printf("battle: evade, critical hit, fleeing\n");
    Rig r;
    r.Load(BuildWalker(r.factory, true, true, 100));
    r.Right();
    auto toBattle = [&r]() {
        r.script = {0, 0};
        r.Center();
        r.Wait(1050);
        r.Center();
        r.Wait(1300 + 1700);
    };
    toBattle();
    CHECK(r.Screen() == "battle");
    r.script = {60};                                  // we evade, they evade: stare down
    r.Right();
    CHECK(r.Shows(0, 48, 96, 16, 0x62B0));
    r.Wait(1350);
    r.script = {90};                                  // we attack, something special: critical hit
    r.Left();
    CHECK(r.Shows(0, 48, 96, 16, 0x5CB0));
    r.Wait(1350);
    CHECK(r.Ink(80, 0, 16, 8) == 0);                   // wild HP 2 of 4
    r.script = {90};                                  // we evade, something special: it flees
    r.Right();
    CHECK(r.Shows(0, 48, 96, 16, 0x56B0));            // "fled..."
    r.Wait(1850);
    CHECK(r.Screen() == "menu");
    CHECK(Contains(r.Status(), "\"caught\":0"));

    toBattle();
    for (int i = 0; i < 4; i++)                        // we evade, they attack: we counter four times
    {
        r.script = {10};
        r.Right();
        r.Wait(2300);
    }
    CHECK(r.Shows(0, 48, 96, 16, 0x56B0));            // knocked out: it flees
    r.Wait(1850);
    CHECK(r.Screen() == "menu");

    toBattle();
    for (int i = 0; i < 4; i++)                        // we attack, they evade and hit back
    {
        r.script = {60};
        r.Left();
        r.Wait(2300);
    }
    CHECK(r.Shows(0, 48, 96, 16, 0x5830));            // "was too strong."
    r.Wait(1850);
    CHECK(r.Screen() == "menu");
}

void TestDowsing()
{
    std::printf("dowsing\n");
    Rig r;
    r.Load(BuildWalker(r.factory, true, true, 20));
    r.Right();
    r.Right();                                        // menu: Dowsing
    CHECK(r.Shows(8, 0, 80, 16, 0x0910 + 320));
    CHECK(r.Shows(8, 48, 8, 16, 0x0280 + 3 * 32));    // cost "3"
    r.script = {3};                                   // item under bush 3
    r.Center();
    CHECK(r.Screen() == "dowsing");
    CHECK(Contains(r.Status(), "\"watts\":17"));
    CHECK(r.Shows(0, 48, 96, 16, 0x47B0));            // "discover an item!"
    r.Dump("dowsing");
    r.Center();                                       // bush 0: far away
    CHECK(r.Shows(0, 48, 96, 16, 0x4DB0));
    CHECK(r.Shows(0, 16, 16, 16, 0x1B90));            // searched bush looks lighter
    r.Center();                                       // same bush again: ignored
    CHECK(r.Shows(0, 48, 96, 16, 0x4DB0));
    r.Right();
    r.Right();
    r.Center();                                       // bush 2: second miss, nothing found
    CHECK(r.Shows(0, 48, 96, 16, 0x4AB0));
    r.Wait(1850);
    CHECK(r.Screen() == "menu");

    r.script = {4};
    r.Center();
    r.Right();
    r.Right();
    r.Right();
    r.Center();                                       // bush 3: near
    CHECK(r.Shows(0, 48, 96, 16, 0x4C30));
    r.Right();
    r.Center();                                       // bush 4: found
    CHECK(r.Shows(0, 32, 96, 16, 0xA8BE));            // first route item's name
    CHECK(r.Shows(0, 48, 96, 16, 0x4930));            // "found!"
    r.Dump("dowsing_found");
    r.Wait(1850);
    CHECK(r.Screen() == "menu");
    CHECK(rd16le(r.walker.Image() + ee::Dowsed) == 17);
    CHECK(Contains(r.Status(), "\"dowsed\":1"));
}

void TestCardInventorySettings()
{
    std::printf("trainer card, Pokemon & items, settings, connect\n");
    Rig r;
    Holdings held;
    held.caught[0] = 19;
    held.dowsed[0] = 21;
    r.Load(BuildWalker(r.factory, true, true, 100, held));
    r.Left();
    r.Left();
    r.Left();                                         // menu: Trainer Card
    CHECK(r.Shows(8, 0, 80, 16, 0x0910 + 3 * 320));
    r.Center();
    CHECK(r.Screen() == "trainer card");
    CHECK(r.Shows(16, 0, 80, 16, 0x1250));            // trainer name
    CHECK(r.Shows(16, 16, 80, 16, 0x907E));           // route name
    CHECK(r.Shows(56, 32, 8, 16, 0x0280 + 1 * 32));   // 13:05
    CHECK(r.Shows(72, 32, 8, 16, 0x0280 + 10 * 32));
    CHECK(r.Shows(88, 32, 8, 16, 0x0280 + 5 * 32));
    CHECK(r.Shows(80, 48, 8, 16, 0x0280 + 1 * 32));   // 12 days
    r.Dump("card_1");
    r.Right();
    CHECK(r.Shows(0, 0, 40, 16, 0x13D0));
    CHECK(r.Shows(56, 48, 8, 16, 0x0280 + 5 * 32));   // 56789 total
    r.Right();
    CHECK(r.Shows(8, 0, 8, 16, 0x0280 + 1 * 32));     // "-1" day
    CHECK(r.Shows(64, 48, 8, 16, 0x0280 + 1 * 32));   // 1011 steps that day
    r.Dump("card_history");
    r.Center();
    CHECK(r.Screen() == "menu");

    r.Right();                                        // Pokémon & Items
    r.Center();
    CHECK(r.Screen() == "inventory");
    CHECK(r.Shows(3, 0, 8, 8, 0x0460));               // walker, caught, item
    CHECK(r.Shows(12, 0, 8, 8, 0x0460));
    CHECK(r.Shows(21, 0, 8, 8, 0x0488));
    CHECK(r.Shows(8, 44, 80, 16, 0x993E));            // Slowpoke
    r.Right();
    CHECK(r.Shows(8, 44, 80, 16, 0xA4FE + 2 * 320));  // the caught Rattata
    r.Right();
    CHECK(r.Shows(32, 16, 32, 24, 0x1910));
    CHECK(r.Shows(0, 44, 96, 16, 0xA8BE + 4 * 384));  // item 21 = route item 4
    r.Dump("inventory_item");
    r.Center();

    r.Right();                                        // Settings
    r.Center();
    CHECK(r.Screen() == "settings");
    CHECK(r.device.Volume() == 2 && r.device.Shade() == 4);
    r.Left();
    r.Left();
    r.Left();                                         // sound off
    CHECK(r.Shows(56, 0, 24, 16, 0x17D0));
    CHECK(r.device.Volume() == 0);
    r.Center();
    r.Right();
    r.Right();
    r.Right();                                        // shade 7
    CHECK(r.device.Shade() == 7);
    r.Dump("settings");
    r.Center();
    r.Center();                                       // save
    CHECK(r.Screen() == "menu");
    CHECK(r.walker.GetSnapshot().settings == 0x38);
    CHECK(r.device.Volume() == 0 && r.device.Shade() == 7);

    r.Left();
    r.Left();
    r.Left();                                         // Connect
    r.Center();
    CHECK(r.Screen() == "connect");
    CHECK(r.Shows(0, 0, 96, 16, 0x2530));
    r.Dump("connect");
    r.Wait(8100);
    CHECK(r.Screen() == "menu");
}

void TestChangedFlag()
{
    std::printf("redraw only when something changed\n");
    Rig r;
    r.Load(BuildWalker(r.factory, true, true, 100));
    r.ms += 100;
    CHECK(!r.device.Update(r.ms));                    // same picture
    r.ms += 500;
    CHECK(r.device.Update(r.ms));                     // animation frame changed
}

}

int main()
{
    TestHomeAndMenu();
    TestUnpaired();
    TestRadarCatch();
    TestRadarMisses();
    TestRadarRarerSlotAndSwitch();
    TestBattleOutcomes();
    TestDowsing();
    TestCardInventorySettings();
    TestChangedFlag();
    std::printf("%d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
