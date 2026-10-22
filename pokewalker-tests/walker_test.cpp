// Host tests for the virtual Pokéwalker.
//
// A FakeGame plays the HeartGold/SoulSilver side of the IR protocol the way the
// cartridge drives it (poll with SPI 0x01, send with SPI 0x02), against the
// real VirtualPokewalker code that ships in the app.
//
// Build and run: see pokewalker-tests/CMakeLists.txt, or
//   g++ -std=c++17 -I../app/src/main/cpp/pokewalker walker_test.cpp ../app/src/main/cpp/pokewalker/{PwProtocol,EepromStore,PwText,VirtualPokewalker}.cpp

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <random>
#include <string>
#include <unistd.h>
#include <vector>

#include "PwProtocol.h"
#include "PwText.h"
#include "VirtualPokewalker.h"

using namespace pokewalker;

static int failures = 0, checks = 0;
#define CHECK(cond)                                                                 \
    do {                                                                            \
        checks++;                                                                   \
        if (!(cond)) { failures++; std::printf("  FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); } \
    } while (0)
#define CHECK_EQ(a, b)                                                              \
    do {                                                                            \
        checks++;                                                                   \
        auto _va = (a); auto _vb = (b);                                             \
        if (!(_va == _vb)) { failures++; std::printf("  FAIL %s:%d: %s == %s (%lld vs %lld)\n", __FILE__, __LINE__, #a, #b, (long long)_va, (long long)_vb); } \
    } while (0)

static bool Contains(const std::string& hay, const std::string& needle) { return hay.find(needle) != std::string::npos; }

struct FakeClock
{
    s64 ms = 1000;
    s64 unixSec = 1792600000; // 2026-10-22-ish
};

// Encode ASCII into the game's 16-bit charset (inverse of DecodeGameText for A-Z, a-z, 0-9, space).
static std::vector<u8> GameText(const std::string& s, size_t chars)
{
    std::vector<u8> out(chars * 2, 0xFF);
    size_t i = 0;
    for (; i < s.size() && i < chars; i++)
    {
        char c = s[i];
        u16 v = 0x01DE;
        if (c >= '0' && c <= '9') v = 0x0121 + (c - '0');
        else if (c >= 'A' && c <= 'Z') v = 0x012B + (c - 'A');
        else if (c >= 'a' && c <= 'z') v = 0x0145 + (c - 'a');
        wr16le(&out[i * 2], v);
    }
    return out;
}

struct Packet
{
    u8 cmd = 0, extra = 0;
    u8 session[4] {};
    std::vector<u8> payload;
    bool valid = false;
};

class FakeGame
{
public:
    FakeGame(VirtualPokewalker& walker, FakeClock& clock) : w(walker), clk(clock) {}

    std::vector<u8> Poll()
    {
        u8 buf[0xB8];
        int n = w.OnGamePoll(buf, sizeof(buf));
        clk.ms += 16; // one frame
        return std::vector<u8>(buf, buf + n);
    }

    void SendRaw(std::vector<u8> pkt)
    {
        AirScramble(pkt.data(), pkt.size());
        w.OnGameSend(pkt.data(), pkt.size());
        clk.ms += 2;
    }

    static Packet Parse(std::vector<u8> raw)
    {
        Packet p;
        if (raw.size() < 8) return p;
        AirScramble(raw.data(), raw.size());
        if (rd16le(&raw[2]) != PacketChecksum(raw.data(), raw.size())) return p;
        p.cmd = raw[0];
        p.extra = raw[1];
        std::memcpy(p.session, &raw[4], 4);
        p.payload.assign(raw.begin() + 8, raw.end());
        p.valid = true;
        return p;
    }

    bool Connect()
    {
        for (int i = 0; i < 400; i++)
        {
            std::vector<u8> r = Poll();
            if (r.size() == 1 && r[0] == (cmd::Advertising ^ AirXor))
            {
                u8 master[4] = {0x3F, 0x57, 0xCF, 0x34};
                SendRaw(BuildPacket(cmd::AssertMaster, ExtraFromMaster, master, nullptr, 0));
                Packet ack = Parse(Poll());
                if (!ack.valid || ack.cmd != cmd::SlaveAck) return false;
                for (int k = 0; k < 4; k++) session[k] = master[k] ^ ack.session[k];
                return true;
            }
            if (!r.empty()) return false;
        }
        return false;
    }

    Packet Request(u8 c, u8 extra, const std::vector<u8>& payload = {})
    {
        SendRaw(BuildPacket(c, extra, session, payload.data(), payload.size()));
        for (int i = 0; i < 5; i++)
        {
            std::vector<u8> r = Poll();
            if (!r.empty())
            {
                Packet p = Parse(r);
                if (p.valid && std::memcmp(p.session, session, 4) != 0) p.valid = false;
                return p;
            }
        }
        return Packet {};
    }

    void SendNoReply(u8 c, u8 extra = ExtraFromMaster)
    {
        SendRaw(BuildPacket(c, extra, session, nullptr, 0));
    }

    bool WriteEeprom(u16 addr, const std::vector<u8>& block128, bool compress)
    {
        u8 c;
        std::vector<u8> payload;
        if (compress)
        {
            payload = Lz10Compress(block128.data(), block128.size());
            if (payload.size() >= 128) { compress = false; payload = block128; }
        }
        else
            payload = block128;
        if (compress) c = (addr & 0x80) ? cmd::EepromWriteCmp80 : cmd::EepromWriteCmp00;
        else c = (addr & 0x80) ? cmd::EepromWriteRaw80 : cmd::EepromWriteRaw00;
        Packet ack = Request(c, (u8)(addr >> 8), payload);
        return ack.valid && ack.cmd == cmd::EepromWriteAck && ack.extra == (u8)(addr >> 8);
    }

    std::vector<u8> ReadEeprom(u16 addr, u8 len)
    {
        Packet p = Request(cmd::EepromReadReq, ExtraFromMaster, {(u8)(addr >> 8), (u8)addr, len});
        if (!p.valid || p.cmd != cmd::EepromReadRsp) return {};
        return p.payload;
    }

    u8 session[4] {};

private:
    VirtualPokewalker& w;
    FakeClock& clk;
};

static std::string TempPath(const char* name)
{
    static int counter = 0;
    char buf[256];
    std::snprintf(buf, sizeof(buf), "/tmp/pw_test_%d_%d_%s", (int)getpid(), counter++, name);
    std::remove(buf);
    std::remove((std::string(buf) + ".meta").c_str());
    return buf;
}

static void Setup(VirtualPokewalker& w, FakeClock& clk)
{
    w.SetClocks([&clk]() { return clk.ms; }, [&clk]() { return clk.unixSec; },
                [](s64 t) { return t / 86400; });
    u32 seed = 1234;
    w.SetRandom([seed]() mutable { seed = seed * 1103515245 + 12345; return seed; });
    w.SetLogger([](const std::string& m) { if (std::getenv("PW_VERBOSE")) std::printf("    [walker] %s\n", m.c_str()); });
}

static std::vector<u8> GameIdentity()
{
    std::vector<u8> id(0x68, 0);
    wr32le(&id[0x00], 1);          // unk0
    wr16le(&id[0x08], 7);          // unk2
    wr16le(&id[0x0C], 12345);      // TID
    wr16le(&id[0x0E], 54321);      // SID
    for (int i = 0; i < 40; i++) id[0x10 + i] = (u8)(0xA0 + i);
    std::vector<u8> name = GameText("LYRA", 8);
    std::memcpy(&id[0x48], name.data(), 16);
    id[0x5F] = 0x02;
    wr32be(&id[0x60], 815000000);  // game clock, seconds since 2001
    return id;
}

static std::vector<u8> RouteInfo()
{
    std::vector<u8> r(0x100, 0);
    wr16le(&r[0x00], 25);          // Pikachu
    r[0x0C] = 5;                   // level
    std::vector<u8> nick = GameText("SPARKY", 11);
    std::memcpy(&r[0x10], nick.data(), 22);
    std::vector<u8> route = GameText("Refreshing Field", 21);
    std::memcpy(&r[0x28], route.data(), 42);
    // route Pokémon: slot 0 rare (needs 3000 steps, 10%), slot 1 (500 steps, 30%), slot 2 common
    const u16 species[3] = {179, 16, 19};   // Mareep, Pidgey, Rattata
    const u16 steps[3] = {3000, 500, 0};
    const u8 percent[3] = {10, 30, 100};
    for (int i = 0; i < 3; i++)
    {
        wr16le(&r[0x52 + i * 16], species[i]);
        r[0x52 + i * 16 + 0x0C] = (u8)(8 + i);
        wr16le(&r[0x82 + i * 2], steps[i]);
        r[0x88 + i] = percent[i];
    }
    for (int i = 0; i < 10; i++)
    {
        wr16le(&r[0x8C + i * 2], (u16)(17 + i));       // Potion, Antidote, ...
        wr16le(&r[0xA0 + i * 2], (u16)(i < 9 ? 1000 : 0));
        r[0xB4 + i] = (u8)(i < 9 ? 5 : 100);
    }
    return r;
}

// ---------------------------------------------------------------------------

static void TestChecksumVectors()
{
    std::printf("checksum vectors\n");
    u8 a[4] = {0x4A, 0x21, 0x2E, 0x07};
    std::vector<u8> p = BuildPacket(0xF8, 0x02, a, nullptr, 0);
    CHECK_EQ(p[2], 0x2D); CHECK_EQ(p[3], 0x70);   // captured header bytes F8 02 2D 70
    u8 b[4] = {0x3F, 0x57, 0xCF, 0x34};
    p = BuildPacket(0xFA, 0x01, b, nullptr, 0);
    CHECK_EQ(p[2], 0x90); CHECK_EQ(p[3], 0x08);   // FA 01 90 08
}

static void TestLz10()
{
    std::printf("LZ10 round trips\n");
    std::mt19937 rng(7);
    for (int round = 0; round < 300; round++)
    {
        std::vector<u8> src(128);
        int mode = round % 3;
        for (size_t i = 0; i < src.size(); i++)
            src[i] = mode == 0 ? (u8)rng() : mode == 1 ? (u8)((i / 7) & 3) : (u8)(rng() % 3 == 0 ? rng() : 0);
        std::vector<u8> c = Lz10Compress(src.data(), src.size());
        u8 out[256];
        int n = Lz10Decompress(c.data(), c.size(), out, sizeof(out));
        CHECK_EQ(n, 128);
        CHECK(n == 128 && std::memcmp(out, src.data(), 128) == 0);
    }
    u8 bad[] = {0x10, 0x80, 0x00, 0x00, 0x80, 0xF0, 0x05};  // back-reference before start
    u8 out[256];
    CHECK_EQ(Lz10Decompress(bad, sizeof(bad), out, sizeof(out)), -1);
    u8 wrongType[] = {0x11, 0x80, 0, 0};
    CHECK_EQ(Lz10Decompress(wrongType, sizeof(wrongType), out, sizeof(out)), -1);
}

static void TestFreshWalker()
{
    std::printf("fresh walker\n");
    FakeClock clk;
    VirtualPokewalker w;
    Setup(w, clk);
    std::string path = TempPath("fresh.bin");
    std::string err;
    CHECK(w.Open(path, &err));
    std::vector<u8> img = w.ExportImage();
    CHECK_EQ(img.size(), (size_t)0x10000);
    CHECK(std::memcmp(img.data(), "nintendo", 8) == 0);
    CHECK_EQ(img[0x00ED + 0x5F], 0x02);
    CHECK_EQ(EepromStore::ReliableChecksum(&img[0x00ED], 0x68), img[0x00ED + 0x68]);
    CHECK_EQ(EepromStore::ReliableChecksum(&img[0x0156], 24), img[0x0156 + 24]);
    std::string st = w.StatusJson();
    CHECK(Contains(st, "\"registered\":false"));
    CHECK(Contains(st, "\"hasPokemon\":false"));

    // the advertisement only comes after a run of quiet polls
    FakeGame g(w, clk);
    for (int i = 0; i < 10; i++) CHECK(g.Poll().empty());
    std::vector<u8> adv = g.Poll();
    CHECK(adv.size() == 1 && adv[0] == (0xFC ^ 0xAA));
}

static void PairAndStartWalk(VirtualPokewalker& w, FakeClock& clk, bool* ok)
{
    FakeGame g(w, clk);
    *ok = g.Connect();
    CHECK(*ok);
    if (!*ok) return;

    Packet id = g.Request(cmd::IdentityReq, ExtraFromMaster);
    CHECK(id.valid && id.cmd == cmd::IdentityRsp && id.payload.size() == 0x68);
    CHECK_EQ(id.payload[0x5B] & 1, 0);  // not registered yet

    Packet rst = g.Request(cmd::WalkerReset1, ExtraFromMaster);
    CHECK(rst.valid && rst.cmd == cmd::WalkerReset1 && rst.payload.size() == 40);

    // UI graphics (compressed), then route + team data into the staging areas (raw)
    std::mt19937 rng(99);
    for (u32 a = 0x0300; a < 0x0700; a += 0x80)
    {
        std::vector<u8> blk(128);
        for (size_t i = 0; i < blk.size(); i++) blk[i] = (u8)((i % 16 < 8) ? 0xFF : rng());
        CHECK(g.WriteEeprom((u16)a, blk, true));
    }
    std::vector<u8> routeInfo = RouteInfo();
    CHECK(g.WriteEeprom(0xD700, std::vector<u8>(routeInfo.begin(), routeInfo.begin() + 128), false));
    CHECK(g.WriteEeprom(0xD780, std::vector<u8>(routeInfo.begin() + 128, routeInfo.begin() + 256), false));
    std::vector<u8> team(128, 0x5A);
    for (u32 a = 0xD480; a < 0xD700; a += 0x80) CHECK(g.WriteEeprom((u16)a, team, false));
    std::vector<u8> sprite(128, 0x33);
    CHECK(g.WriteEeprom(0xFF80, sprite, false));

    Packet ack = g.Request(cmd::IdentitySend, ExtraFromMaster, GameIdentity());
    CHECK(ack.valid && ack.cmd == cmd::IdentityAck);
    Packet ws = g.Request(cmd::WalkStartInit, ExtraFromMaster);
    CHECK(ws.valid && ws.cmd == cmd::WalkStartInit);
    Packet done = g.Request(cmd::ConnectComplete, ExtraFromMaster);
    CHECK(done.valid && done.cmd == cmd::ConnectCompleteAck);
    CHECK(!w.SessionActive());
}

static void TestFullStroll()
{
    std::printf("pairing, stroll, phone steps, return\n");
    FakeClock clk;
    VirtualPokewalker w;
    Setup(w, clk);
    std::string path = TempPath("stroll.bin");
    std::string err;
    CHECK(w.Open(path, &err));

    bool ok = false;
    PairAndStartWalk(w, clk, &ok);
    if (!ok) return;

    std::vector<u8> img = w.ExportImage();
    CHECK_EQ(img[0x00ED + 0x5B], 0x03);                  // registered + has Pokémon
    CHECK_EQ(rd16le(&img[0x00ED + 0x0C]), 12345);
    CHECK_EQ(rd16le(&img[0x00ED + 0x0E]), 54321);
    CHECK_EQ(rd32le(&img[0x00ED + 0x04]), 1u);           // unk1 = unk0
    CHECK_EQ(rd16le(&img[0x00ED + 0x0A]), 7);            // unk3 = unk2
    CHECK_EQ(rd16le(&img[0x8F00]), 25);                  // route info copied out of staging
    CHECK_EQ(img[0xB7FF], 0x33);                         // ...all 0x2900 bytes of it
    CHECK_EQ(img[0xCC00], 0x5A);                         // team data copied
    CHECK_EQ(EepromStore::ReliableChecksum(&img[0x00ED], 0x68), img[0x00ED + 0x68]);
    CHECK_EQ(EepromStore::ReliableChecksum(&img[0x01ED], 0x68), img[0x01ED + 0x68]);

    std::string st = w.StatusJson();
    CHECK(Contains(st, "\"trainer\":\"LYRA\""));
    CHECK(Contains(st, "\"speciesName\":\"Pikachu\""));
    CHECK(Contains(st, "\"nickname\":\"SPARKY\""));
    CHECK(Contains(st, "\"route\":\"Refreshing Field\""));
    CHECK(Contains(st, "\"hasPokemon\":true"));

    // phone steps
    CHECK(w.AddSteps(1234, 0, &err));
    st = w.StatusJson();
    CHECK(Contains(st, "\"todaySteps\":1234"));
    CHECK(Contains(st, "\"totalSteps\":1234"));
    CHECK(Contains(st, "\"watts\":61"));                // 1234 / 20
    CHECK(w.AddSteps(6, 0, &err));                       // 14 + 6 leftover = one more watt
    CHECK(Contains(w.StatusJson(), "\"watts\":62"));

    // the game cannot be connected while the UI edits, and vice versa
    {
        FakeGame g(w, clk);
        clk.ms += 5000;
        CHECK(g.Connect());
        CHECK(!w.AddSteps(10, 0, &err));
        CHECK(Contains(err, "connected"));

        Packet id = g.Request(cmd::IdentityReq, ExtraFromMaster);
        CHECK(id.valid && id.payload.size() == 0x68);
        CHECK_EQ(rd32be(&id.payload[0x64]), 1240u);       // live step count goes to the game
        Packet ack = g.Request(cmd::IdentitySendAlias1, ExtraFromMaster, GameIdentity());
        CHECK(ack.valid && ack.cmd == cmd::IdentityAckAlias1);
        std::vector<u8> watts = g.ReadEeprom(0xCE8A, 2);
        CHECK(watts.size() == 2 && rd16be(watts.data()) == 62);
        std::vector<u8> caught = g.ReadEeprom(0xCE8C, 0x64);
        CHECK_EQ(caught.size(), (size_t)0x64);
        Packet end = g.Request(cmd::WalkEndReq, ExtraFromMaster);
        CHECK(end.valid && end.cmd == cmd::WalkEndAck);
        Packet done = g.Request(cmd::ConnectComplete, ExtraFromMaster);
        CHECK(done.valid && done.cmd == cmd::ConnectCompleteAck);
    }
    st = w.StatusJson();
    CHECK(Contains(st, "\"hasPokemon\":false"));
    CHECK(Contains(st, "\"registered\":true"));
    CHECK(Contains(st, "\"watts\":0"));
    CHECK(Contains(st, "\"totalSteps\":1240"));

    // persisted to disk: reopen and look again
    w.Close();
    VirtualPokewalker w2;
    Setup(w2, clk);
    CHECK(w2.Open(path, &err));
    CHECK(Contains(w2.StatusJson(), "\"totalSteps\":1240"));
}

static void TestDaysAndCaps()
{
    std::printf("day rollover, backfill, caps\n");
    FakeClock clk;
    VirtualPokewalker w;
    Setup(w, clk);
    std::string err;
    CHECK(w.Open(TempPath("days.bin"), &err));
    CHECK(w.AddSteps(5000, 0, &err));
    clk.unixSec += 86400;                                   // next day
    CHECK(w.AddSteps(700, 0, &err));
    std::string st = w.StatusJson();
    CHECK(Contains(st, "\"todaySteps\":700"));
    CHECK(Contains(st, "\"history\":[5000,0,0,0,0,0,0]"));
    CHECK(Contains(st, "\"totalDays\":1"));
    CHECK(w.AddSteps(300, 1, &err));                     // yesterday, backfilled
    CHECK(Contains(w.StatusJson(), "\"history\":[5300,0,0,0,0,0,0]"));
    clk.unixSec += 3 * 86400;                               // three more days pass
    st = w.StatusJson();
    CHECK(Contains(st, "\"history\":[0,0,700,5300,0,0,0]"));
    CHECK(Contains(st, "\"todaySteps\":0"));
    clk.unixSec += 20 * 86400;                              // long gap clears the week
    CHECK(Contains(w.StatusJson(), "\"history\":[0,0,0,0,0,0,0]"));
    CHECK(w.AddSteps(250000, 0, &err));
    st = w.StatusJson();
    CHECK(Contains(st, "\"todaySteps\":99999"));
    CHECK(Contains(st, "\"watts\":9999"));
    CHECK(!w.AddSteps(5, -1, &err));
}

static void TestRobustness()
{
    std::printf("bad packets, cooldown, cart reset, factory reset\n");
    FakeClock clk;
    VirtualPokewalker w;
    Setup(w, clk);
    std::string err;
    CHECK(w.Open(TempPath("robust.bin"), &err));
    FakeGame g(w, clk);
    CHECK(g.Connect());

    // corrupted checksum: the walker stays silent
    std::vector<u8> pkt = BuildPacket(cmd::IdentityReq, ExtraFromMaster, g.session, nullptr, 0);
    pkt[2] ^= 0x55;
    g.SendRaw(pkt);
    CHECK(g.Poll().empty());
    // a good one still works
    Packet id = g.Request(cmd::IdentityReq, ExtraFromMaster);
    CHECK(id.valid && id.cmd == cmd::IdentityRsp);

    // disconnect: no reply, then no advert while the game keeps polling
    g.SendNoReply(cmd::Disconnect);
    CHECK(!w.SessionActive());
    for (int i = 0; i < 50; i++) CHECK(g.Poll().empty());
    // after the game stops polling for a moment the walker is discoverable again
    clk.ms += 2000;
    CHECK(g.Connect());

    // session timeout
    clk.ms += 4000;
    CHECK(g.Poll().empty());
    CHECK(!w.SessionActive());

    // cart reset mid-session drops it
    clk.ms += 2000;
    CHECK(g.Connect());
    w.OnCartReset();
    CHECK(!w.SessionActive());

    // erase including lifetime steps
    CHECK(w.AddSteps(4321, 0, &err));
    clk.ms += 2000;
    CHECK(g.Connect());
    Packet e = g.Request(cmd::WalkerReset3, ExtraFromMaster);
    CHECK(e.valid && e.cmd == cmd::WalkerReset3 && e.payload.size() == 40);
    std::string st = w.StatusJson();
    CHECK(Contains(st, "\"totalSteps\":0"));
    CHECK(!w.ResetToFactory(&err));                      // refused while the game is connected
    g.SendNoReply(cmd::Disconnect);

    CHECK(w.ResetToFactory(&err));
    CHECK(Contains(w.StatusJson(), "\"registered\":false"));
}

static void TestRealDump(const char* dumpPath)
{
    std::printf("real Pokewalker EEPROM dump\n");
    FILE* f = std::fopen(dumpPath, "rb");
    if (!f) { std::printf("  (skipped: %s not found)\n", dumpPath); return; }
    std::vector<u8> img(0x10000);
    size_t n = std::fread(img.data(), 1, img.size(), f);
    std::fclose(f);
    CHECK_EQ(n, (size_t)0x10000);

    FakeClock clk;
    VirtualPokewalker w;
    Setup(w, clk);
    std::string err;
    CHECK(w.Open(TempPath("dump.bin"), &err));
    CHECK(w.ImportImage(img, &err));
    std::string st = w.StatusJson();
    std::printf("  %s\n", st.c_str());
    CHECK(Contains(st, "\"registered\":true"));
    CHECK(Contains(st, "\"hasPokemon\":true"));

    // the real dump must survive a full return-from-stroll session
    FakeGame g(w, clk);
    CHECK(g.Connect());
    Packet id = g.Request(cmd::IdentityReq, ExtraFromMaster);
    CHECK(id.valid && std::memcmp(id.payload.data(), &img[0x00ED], 0x60) == 0);
    Packet end = g.Request(cmd::WalkEndReq, ExtraFromMaster);
    CHECK(end.valid && end.cmd == cmd::WalkEndAck);
    CHECK(Contains(w.StatusJson(), "\"hasPokemon\":false"));

    std::vector<u8> bad(1000, 0);
    CHECK(!w.ImportImage(bad, &err));
}

static void TestMiniGames()
{
    std::printf("Poke Radar and dowsing\n");
    FakeClock clk;
    VirtualPokewalker w;
    Setup(w, clk);
    std::string err, json;
    CHECK(w.Open(TempPath("games.bin"), &err));
    int slot = -1;
    u16 item = 0;
    CHECK(!w.RadarEncounter(&slot, &json, &err));         // no stroll yet
    CHECK(Contains(err, "stroll"));

    bool ok = false;
    PairAndStartWalk(w, clk, &ok);
    if (!ok) return;
    CHECK(!w.RadarEncounter(&slot, &json, &err));         // 0 W
    CHECK(Contains(err, "watts"));

    CHECK(w.AddSteps(400, 0, &err));                       // 20 W, today 400: only the common slot qualifies
    CHECK(w.RadarEncounter(&slot, &json, &err));
    CHECK_EQ(slot, 2);
    CHECK(Contains(json, "\"speciesName\":\"Rattata\""));
    CHECK(Contains(json, "\"watts\":10"));
    CHECK(w.StoreCaught(slot, &err));

    CHECK(w.DowsingStart(&item, &json, &err));             // today 400 < 1000: only the last item qualifies
    CHECK_EQ(item, 26);
    CHECK(Contains(json, "\"watts\":7"));
    CHECK(w.StoreDowsed(item, &err));
    CHECK(!w.StoreDowsed(999, &err));                      // not on this route

    // more steps unlock the rarer slots; over many tries all appear
    CHECK(w.AddSteps(5000, 0, &err));
    int seen[3] = {0, 0, 0};
    for (int i = 0; i < 20; i++)
    {
        if (!w.RadarEncounter(&slot, &json, &err)) break;
        seen[slot]++;
    }
    CHECK(seen[0] > 0 && seen[1] > 0 && seen[2] > 0);

    // catch slots fill up at three
    CHECK(w.StoreCaught(0, &err));
    CHECK(w.StoreCaught(1, &err));
    CHECK(!w.StoreCaught(2, &err));
    CHECK(!w.RadarEncounter(&slot, &json, &err));
    CHECK(Contains(err, "3 Pokemon"));
    std::string st = w.StatusJson();
    CHECK(Contains(st, "\"caught\":3"));
    CHECK(Contains(st, "\"dowsed\":1"));

    // what the game reads back on return: species in the caught area
    std::vector<u8> img = w.ExportImage();
    CHECK_EQ(rd16le(&img[0xCE8C]), 19);
    CHECK_EQ(rd16le(&img[0xCE8C + 16]), 179);
    CHECK_EQ(rd16le(&img[0xCE8C + 32]), 16);
    CHECK_EQ(rd16le(&img[0xCEBC]), 26);
}

int main(int argc, char** argv)
{
    TestChecksumVectors();
    TestLz10();
    TestFreshWalker();
    TestFullStroll();
    TestDaysAndCaps();
    TestRobustness();
    TestMiniGames();
    TestRealDump(argc > 1 ? argv[1] : "pokewalker-dump.bin");
    std::printf("%d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
