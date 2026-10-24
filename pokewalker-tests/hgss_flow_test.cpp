// HeartGold/SoulSilver Pokéwalker flows, modelled on the game's own code.
//
// The game side here follows the HG/SS decompilation (pret/pokeheartgold):
//   lib/asm/libirc.s     CARD_SpiWait* — SPI framing of the IR chip
//   asm/overlay_112.s    the "Connect to Pokéwalker" app:
//     ov112_021E5B98     link-layer poll (advert/handshake, checksum, session, 100 ms timeout)
//     ov112_021E6A6C     on-connect: request identity or send a reset
//     ov112_021E6BF8     decide after reading the walker's identity
//     ov112_021E6F60     dispatch walker replies
//     ov112_021E6004 / ov112_021E6164  next step after a write / read sequence
//     ov112_021E5EEC / ov112_021E6134  send one write chunk / read request
//     ov112_021E7398     IR thread: poll every 4 ms
//     ov112_021E7CC8     build the game's identity block
//
// It runs those flows against the real VirtualPokewalker, sharing one clock, so the
// virtual walker is held to the same rules, timeouts and sizes as on a DS.

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <string>
#include <unistd.h>
#include <vector>

#include "PwProtocol.h"
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

// Result codes the app reads back (ov112_021E5E18 / ov112_021E76A8).
enum Result : int
{
    ResPending = 1,             // set when the IR thread starts, cleared on connect
    ResLinkLost = 2,            // 100 ms without a valid packet after connecting
    ResWalkerBusy = 3,          // walker answered 0x28
    ResAlreadyRegistered = 4,
    ResNotRegistered = 5,
    ResNoPokemon = 6,
    ResAlreadyHasPokemon = 7,
    ResWrongWalker8 = 8,
    ResWrongWalker9 = 9,        // other game version (HG vs SS)
    ResVersion = 0xA,
    ResWrongWalkerB = 0xB,      // other trainer
    ResError = 0xC,
    ResNoComplete = 0xE,
    ResSuccess = 0xF,
};

struct Clock { s64 ms = 5000; s64 unixSec = 1792600000; };

static u32 rd32(const u8* p) { return (u32)p[0] | (u32)p[1] << 8 | (u32)p[2] << 16 | (u32)p[3] << 24; }
static u16 rd16(const u8* p) { return (u16)(p[0] | p[1] << 8); }

class HgssGame
{
public:
    HgssGame(VirtualPokewalker& walker, Clock& clock, u16 version, u32 trainerId)
        : w(walker), clk(clock), gameVersion(version), tid(trainerId)
    {
        // Payload blobs the game writes: shared walker data (0x0280), route (0xD700), team (0xD480).
        general.resize(0x8C50);
        for (size_t i = 0; i < general.size(); i++) general[i] = (u8)((i * 7 + (i >> 9)) & 0xFF) & ((i & 0x100) ? 0xFF : 0x0F);
        route.resize(0x28BE);
        for (size_t i = 0; i < route.size(); i++) route[i] = (u8)(i * 13 + 5);
        // Something recognisable where the walker keeps species / level / nickname.
        route[0] = 25; route[1] = 0; route[0x0C] = 12;
        team.resize(0x224);
        for (size_t i = 0; i < team.size(); i++) team[i] = (u8)(0xA0 + i);
        results.assign(0xD4C, 0);
        received.assign(0x6C8, 0);
        peers.assign(0x1568, 0);
        routeHeader.assign(0x2A, 0);
    }

    // --- the app's entry points (ov112_021E7484 / 74A8 / 7548 / 7594 / 7620) -------------
    int Register() { return Run(0x32); }
    int StartStroll() { return Run(0x52); }
    int ReturnFromStroll() { return Run(0x40); }
    int ReceiveGifts() { return Run(0x60); }
    int Erase(bool full) { subOp = full ? 0x2A : 0x2C; return Run(0x2A); }

    std::vector<u8> W = std::vector<u8>(0x68, 0); // walker identity as last read by the game
    std::vector<u8> D = std::vector<u8>(0x68, 0); // the game's identity block
    std::vector<u8> general, route, team, results, received, peers, routeHeader;
    std::vector<u8> savedWalkerUnique = std::vector<u8>(40, 0);
    bool compress = true;       // the game LZ-compresses the big writes when it has a work buffer
    bool forceRegisterCheck = true; // ov112_021FFAA4+0x18
    int packetsSent = 0, writesSent = 0, readsSent = 0;
    std::vector<u8> sentCmds;

private:
    VirtualPokewalker& w;
    Clock& clk;
    u16 gameVersion;
    u32 tid;

    // link layer (_021FF9E0)
    bool connected = false, advertSeen = false, linkError = false;
    int hs = 0;
    u32 myHalf = 0x3F57CF34, session = 0;
    s64 lastRx = 0;
    // session layer (ov112_021FFAA4 / ov112_021FFB24)
    int result = ResPending;
    u8 op = 0, subOp = 0, step = 0;
    bool done = false;
    struct Xfer
    {
        std::vector<u8>* buf = nullptr;
        size_t pos = 0;          // offset into buf (or into the compressed slot table)
        u32 addr = 0;
        u32 remaining = 0;
        int chunk = 0;
        const std::vector<u8>* table = nullptr; // compressed 0x80-byte slots
        const std::vector<u8>* sizes = nullptr;
    } x;
    std::vector<u8> generalSlots, generalSizes, routeSlots, routeSizes;

    void SetResult(int code) { if (result == 0) result = code; }

    // ov112_021E59B4 / ov112_021E5A68
    void Send(u8 cmd, u8 extra, const u8* payload, size_t len)
    {
        if (!connected && cmd != 0xFA) return;
        u8 sess[4];
        u32 s = (cmd == 0xFA) ? myHalf : session;
        std::memcpy(sess, &s, 4);
        std::vector<u8> pkt = BuildPacket(cmd, extra, sess, payload, len);
        AirScramble(pkt.data(), pkt.size());
        w.OnGameSend(pkt.data(), pkt.size());
        packetsSent++;
        sentCmds.push_back(cmd);
    }
    void Send(u8 cmd, const std::vector<u8>& payload = {}, u8 extra = 1) { Send(cmd, extra, payload.data(), payload.size()); }

    // ov112_021E5A14 (reason 0 sends F4)
    void Disconnect(int reason)
    {
        if (connected && reason == 0) Send(0xF4);
        connected = false; advertSeen = false; linkError = false; hs = 0;
        x = Xfer {};
        if (reason == 0) done = true;
    }

    // ov112_021E5B98: one 4 ms poll. Returns true if the link timed out.
    bool Poll()
    {
        u8 buf[0xB9];
        int n = w.OnGamePoll(buf, 0xB8);
        if (n > 0xB9) n = 0;
        AirScramble(buf, (size_t)n);
        // ov112_021E5A84: single bytes only as an advert while unconnected; 2–3 bytes are noise
        if (n == 1 && (connected || buf[0] != 0xFC)) n = 0;
        if (n == 2 || n == 3) n = 0;
        if (n == 0)
        {
            if ((connected || advertSeen) && clk.ms - lastRx >= 100)
            {
                Disconnect(1);
                return true;
            }
            return false;
        }
        // ov112_021E5B28
        bool ok;
        if (n == 1) ok = buf[0] == 0xFC;
        else if (n < 4) ok = false;
        else
        {
            u16 got = rd16(&buf[2]);
            buf[2] = buf[3] = 0;
            ok = PacketChecksum(buf, (size_t)n) == got;
            buf[2] = (u8)got; buf[3] = (u8)(got >> 8);
        }
        if (!ok) { if (connected) linkError = true; return false; }
        lastRx = clk.ms;
        u8 cmd = buf[0];
        if (cmd < 0xF0)
        {
            if (!connected) return false;
            if (n < 8 || rd32(&buf[4]) != session) return false;
        }
        switch (cmd)
        {
        case 0xF6: linkError = true; break;
        case 0xF8:
            if (hs == 2)
            {
                hs = 4;
                session = rd32(&buf[4]) ^ myHalf;
                connected = true; linkError = false; advertSeen = false;
                OnConnect(buf[1]);
            }
            break;
        case 0xFA: break; // the walker never takes the master role
        case 0xFC:
            if (hs == 1) { hs = 2; Send(0xFA); }
            if (!advertSeen)
            {
                advertSeen = true; hs = 2;
                if (n <= 1 || connected) Send(0xFA);
            }
            break;
        default:
            if (!linkError && connected && n >= 8)
                OnData(buf + 8, (size_t)n - 8, cmd, buf[1]);
            break;
        }
        return false;
    }

    int Run(u8 operation)
    {
        // this game is the one in the cartridge slot now
        w.SetGame(gameVersion == 8 ? 0x45475049u /* "IPGE" */ : 0x454B5049u /* "IPKE" */);
        op = operation;
        step = 0x20;
        result = ResPending;
        done = false;
        connected = false; advertSeen = false; linkError = false; hs = 0;
        packetsSent = writesSent = readsSent = 0;
        sentCmds.clear();
        BuildIdentity();
        PrepareCompressed();
        // ov112_021E5964: flush whatever the receiver holds
        u8 junk[0xB9];
        w.OnGamePoll(junk, 0xB8);
        // IR thread: poll every 4 ms until the session ends, give up after 20 s like the UI
        for (int i = 0; i < 5000 && !done; i++)
        {
            clk.ms += 4;
            if (Poll()) { SetResult(ResLinkLost); if (result != ResPending) break; }
        }
        // let the walker see the disconnect settle
        for (int i = 0; i < 3; i++) { clk.ms += 4; u8 b[0xB9]; w.OnGamePoll(b, 0xB8); }
        clk.ms += 2000; // the player puts the walker away
        return result;
    }

    // ov112_021E7CC8 (+ ov112_021E5E48 on connect)
    void BuildIdentity()
    {
        std::fill(D.begin(), D.end(), 0);
        wr32le(&D[0x00], 1);
        wr32le(&D[0x04], 1);
        wr16le(&D[0x08], gameVersion);
        wr16le(&D[0x0A], gameVersion);
        wr32le(&D[0x0C], tid);
        std::memcpy(&D[0x10], savedWalkerUnique.data(), 40);
        const u16 name[8] = {0x012B, 0x0145 + 4, 0x0145 + 19, 0x0145 + 7, 0xFFFF, 0, 0, 0}; // "Eth"
        for (int i = 0; i < 8; i++) wr16le(&D[0x48 + i * 2], name[i]);
        D[0x5A] = 0;
        D[0x5B] &= (u8)~1;
    }

    // ov112_021E5D8C: per-128-byte LZ compression table (0x80 = sent raw)
    static void Compress(const std::vector<u8>& src, std::vector<u8>& slots, std::vector<u8>& sizes)
    {
        size_t chunks = (src.size() + 0x7F) / 0x80;
        slots.assign(chunks * 0x80, 0xEE); // stale heap bytes after a short last chunk
        sizes.assign(chunks, 0x80);
        for (size_t c = 0; c < chunks; c++)
        {
            size_t n = std::min<size_t>(0x80, src.size() - c * 0x80);
            const u8* s = &src[c * 0x80];
            if (n == 0x80)
            {
                std::vector<u8> z = Lz10Compress(s, n);
                if (!z.empty() && z.size() < 0x80)
                {
                    std::memcpy(&slots[c * 0x80], z.data(), z.size());
                    sizes[c] = (u8)z.size();
                    continue;
                }
            }
            std::memcpy(&slots[c * 0x80], s, n);
        }
    }
    void PrepareCompressed()
    {
        generalSlots.clear(); routeSlots.clear();
        if (!compress) return;
        if (op == 0x32) Compress(general, generalSlots, generalSizes);
        if (op == 0x32 || op == 0x52) Compress(route, routeSlots, routeSizes);
    }

    void StartWrite(std::vector<u8>* src, u32 addr, u32 len, const std::vector<u8>* slots, const std::vector<u8>* sizes)
    {
        x = Xfer {};
        x.buf = src; x.addr = addr; x.remaining = len;
        x.table = (slots && !slots->empty()) ? slots : nullptr;
        x.sizes = sizes;
    }
    void StartRead(std::vector<u8>* dst, u32 addr, u32 len)
    {
        x = Xfer {};
        x.buf = dst; x.addr = addr; x.remaining = len;
    }

    // ov112_021E5EEC
    void SendNextWriteChunk()
    {
        u16 addr = (u16)x.addr;
        u32 n = std::min<u32>(x.remaining, 0x80);
        writesSent++;
        if (!x.table)
        {
            if (addr & 0x7F)
            {
                n = 0x80 - (addr & 0x7F);
                std::vector<u8> p(1 + n);
                p[0] = (u8)addr;
                std::memcpy(&p[1], &(*x.buf)[x.pos], n);
                Send(0x0A, p, (u8)(addr >> 8));
            }
            else
                Send((u8)(2 | (addr & 0x80)), (u8)(addr >> 8), &(*x.buf)[x.pos], n);
        }
        else
        {
            u8 sz = (*x.sizes)[x.chunk];
            const u8* slot = &(*x.table)[x.pos];
            if (sz == 0x80) Send((u8)(2 | (addr & 0x80)), (u8)(addr >> 8), slot, 0x80); // always a full slot
            else Send((u8)(addr & 0x80), (u8)(addr >> 8), slot, sz);
        }
        x.pos += n; x.addr += n; x.remaining -= n; x.chunk++;
    }

    // ov112_021E6134
    void SendReadRequest()
    {
        u8 p[3] = {(u8)(x.addr >> 8), (u8)x.addr, (u8)std::min<u32>(x.remaining, 0x80)};
        readsSent++;
        Send(0x0C, 2, p, 3);
    }

    // ov112_021E6A6C
    void OnConnect(u8 walkerExtra)
    {
        result = 0;
        if (walkerExtra == 0 || walkerExtra > 2) { SetResult(ResError); Disconnect(0); return; }
        switch (op)
        {
        case 0x32: case 0x40: case 0x52:
        {
            u32 secs = (u32)(clk.unixSec - 946684800);
            wr32be(&D[0x60], secs);
            Send(0x20);
            break;
        }
        case 0x60: wr32be(&D[0x60], 0); Send(0x20); break;
        case 0x2A:
        {
            for (int i = 0; i < 40; i++) D[0x10 + i] = (u8)(0x40 + i * 3);
            Send(subOp, 1, &D[0x10], 0x28);
            SetResult(ResSuccess);
            Disconnect(0);
            break;
        }
        default: SetResult(ResError); Disconnect(0); break;
        }
    }

    void Fail(u8 cmd, int code, bool withIdentity = false)
    {
        if (withIdentity) Send(cmd, 1, D.data(), 0x68); else Send(cmd);
        SetResult(code);
        Disconnect(0);
    }

    // ov112_021E6BF8
    void Decide()
    {
        bool registered = W[0x5B] & 1, hasPokemon = W[0x5B] & 2;
        switch (op)
        {
        case 0x32:
            if (forceRegisterCheck && registered) return Fail(0x36, ResAlreadyRegistered);
            Send(0x32, 1, D.data(), 0x68);
            return;
        case 0x40:
            if (!registered) return Fail(0x44, ResNotRegistered);
            if (!hasPokemon) return Fail(0x44, ResNoPokemon);
            if (rd32(&W[0x0C]) != rd32(&D[0x0C])) return Fail(0x44, ResWrongWalkerB);
            if (rd32(&W[0x04]) != rd32(&D[0x04])) return Fail(0x44, ResWrongWalker8);
            if ((rd16(&W[0x0A]) >> 2) != (rd16(&D[0x0A]) >> 2)) return Fail(0x44, ResWrongWalker9);
            if (W[0x5D] > D[0x5D]) return Fail(0x44, ResVersion);
            Send(0x40, 1, D.data(), 0x68);
            return;
        case 0x52:
            if (!registered) return Fail(0x56, ResNotRegistered);
            if (hasPokemon) return Fail(0x56, ResAlreadyHasPokemon);
            if (rd32(&W[0x00]) != rd32(&D[0x00])) return Fail(0x56, ResWrongWalker8);
            if ((rd16(&W[0x08]) >> 2) != (rd16(&D[0x08]) >> 2)) return Fail(0x56, ResWrongWalker9);
            if (rd32(&W[0x0C]) != rd32(&D[0x0C])) return Fail(0x56, ResWrongWalkerB);
            if (W[0x5D] > D[0x5D]) return Fail(0x56, ResVersion);
            Send(0x52, 1, D.data(), 0x68);
            return;
        case 0x60:
            if (!registered) return Fail(0x64, ResNotRegistered, true);
            if (W[0x5D] > D[0x5D]) return Fail(0x64, ResVersion, true);
            Send(0x60, 1, D.data(), 0x68);
            return;
        }
        SetResult(ResError);
        Disconnect(0);
    }

    // ov112_021E6004
    void WriteSequenceDone()
    {
        switch (step)
        {
        case 0x3A:
            step = 0x3C;
            StartWrite(&route, 0xD700, 0x28BE, routeSlots.empty() ? nullptr : &routeSlots, &routeSizes);
            SendNextWriteChunk();
            return;
        case 0x3C:
            step = 0x3E;
            std::memcpy(&team[8], &W[0x10], 0x28);
            StartWrite(&team, 0xD480, 0x224, nullptr, nullptr);
            SendNextWriteChunk();
            return;
        case 0x3E:
            if (op == 0x52)
            {
                step = 0x48;
                StartRead(&results, 0xCE80, 0xD4C);
                SendReadRequest();
            }
            else
                Send(0x24);
            return;
        }
        SetResult(ResError);
        Disconnect(0);
    }

    // ov112_021E6164
    void ReadSequenceDone()
    {
        switch (step)
        {
        case 0x46:
            step = 0x48;
            StartRead(&results, 0xCE80, 0xD4C);
            SendReadRequest();
            return;
        case 0x48:
            if (op == 0x60) { step = 0x4C; StartRead(&peers, 0xDE24, 0x1568); }
            else { step = 0x4A; StartRead(&received, 0xB800, 0x6C8); }
            SendReadRequest();
            return;
        case 0x4A:
            if (op == 0x52) { Send(0x24); return; }
            if (op == 0x40) { step = 0x4C; StartRead(&peers, 0xDE24, 0x1568); SendReadRequest(); return; }
            std::printf("  FAIL game would OS_Halt (step 0x4A, op %02X)\n", op); failures++;
            done = true;
            return;
        case 0x4C:
            Send(0x24);
            return;
        }
    }

    // ov112_021E6F60
    void OnData(const u8* p, size_t len, u8 cmd, u8 /*extra*/)
    {
        switch (cmd)
        {
        case 0x04:
            if (x.remaining != 0) SendNextWriteChunk(); else WriteSequenceDone();
            return;
        case 0x0E:
            if (!x.buf) return;
            if (x.pos + len > x.buf->size()) { std::printf("  FAIL read overflow\n"); failures++; len = x.buf->size() - x.pos; }
            std::memcpy(&(*x.buf)[x.pos], p, len);
            x.pos += len; x.addr += (u32)len; x.remaining -= (u32)std::min<size_t>(len, x.remaining);
            if (x.remaining == 0) ReadSequenceDone(); else SendReadRequest();
            return;
        case 0x22:
            if (len < 0x68) return;
            std::memcpy(W.data(), p, 0x68);
            Decide();
            return;
        case 0x26:
            switch (op)
            {
            case 0x32: Send(0x38); return;
            case 0x40: Send(0x4E); SetResult(ResSuccess); Disconnect(0); return;
            case 0x52: Send(0x5A); return;
            case 0x60: Send(0x66); SetResult(ResSuccess); Disconnect(0); return;
            }
            return;
        case 0x28: SetResult(ResWalkerBusy); Disconnect(0); return;
        case 0x34:
            step = 0x3A;
            StartWrite(&general, 0x0280, 0x8C50, generalSlots.empty() ? nullptr : &generalSlots, &generalSizes);
            SendNextWriteChunk();
            return;
        case 0x38: case 0x5A: case 0x5E:
            SetResult(ResSuccess); Disconnect(0); return;
        case 0x42:
            step = 0x46;
            StartRead(&routeHeader, 0x8F00, 0x2A);
            SendReadRequest();
            return;
        case 0x44: case 0x64: SetResult(ResNoComplete); Disconnect(0); return;
        case 0x54:
            step = 0x3C;
            StartWrite(&route, 0xD700, 0x28BE, routeSlots.empty() ? nullptr : &routeSlots, &routeSizes);
            SendNextWriteChunk();
            return;
        case 0x62:
            step = 0x48;
            StartRead(&results, 0xCE80, 0xD4C);
            SendReadRequest();
            return;
        case 0x9C: case 0x9E: SetResult(ResError); Disconnect(0); return;
        default:
            if ((cmd >= 0xC0 && cmd <= 0xCE && !(cmd & 1)) || cmd == 0xE0 || cmd == 0xE2 || cmd == 0xF0 || cmd == 0xFE)
            { SetResult(ResSuccess); Disconnect(0); }
            return;
        }
    }
};

static std::string TempPath(const char* name)
{
    char buf[256];
    std::snprintf(buf, sizeof(buf), "/tmp/hgss_flow_%d_%s", (int)getpid(), name);
    std::remove(buf);
    std::remove((std::string(buf) + ".meta").c_str());
    std::remove((std::string(buf) + ".lastwalk").c_str());
    return buf;
}

static void Setup(VirtualPokewalker& w, Clock& clk)
{
    w.SetClocks([&clk]() { return clk.ms; }, [&clk]() { return clk.unixSec; }, [](s64 t) { return t / 86400; });
    u32 seed = 99;
    w.SetRandom([seed]() mutable { seed = seed * 1664525 + 1013904223; return seed; });
    w.SetLogger([](const std::string& m) { if (std::getenv("PW_VERBOSE")) std::printf("    [walker] %s\n", m.c_str()); });
}

static std::vector<u8> Ee(VirtualPokewalker& w, u32 addr, size_t len)
{
    std::vector<u8> img = w.ExportImage();
    return std::vector<u8>(img.begin() + addr, img.begin() + addr + len);
}

static void TestFullStroll(bool compress)
{
    std::printf("register, walk, return (%s writes)\n", compress ? "compressed" : "raw");
    Clock clk;
    VirtualPokewalker w;
    Setup(w, clk);
    std::string err;
    CHECK(w.Open(TempPath(compress ? "c.bin" : "r.bin"), &err));

    HgssGame hg(w, clk, 7 /* HeartGold */, 0x1234ABCD);
    hg.compress = compress;

    int r = hg.Register();
    CHECK_EQ(r, (int)ResSuccess);
    CHECK_EQ(hg.sentCmds.front(), (u8)0xFA);
    CHECK_EQ(hg.sentCmds.back(), (u8)0xF4);
    CHECK(hg.writesSent > 360);
    // what the walker now holds
    CHECK(Ee(w, 0x0280, 0x8C50) == hg.general);
    std::vector<u8> routeLive = Ee(w, 0x8F00, 0x28BE);
    CHECK(routeLive == hg.route);
    std::vector<u8> teamLive = Ee(w, 0xCC00, 0x224);
    CHECK(teamLive == hg.team);
    std::vector<u8> ident = Ee(w, 0x00ED, 0x68);
    CHECK_EQ(ident[0x5B] & 3, 3);              // registered + Pokémon on the walker
    CHECK_EQ(rd32(&ident[0x0C]), 0x1234ABCDu);  // trainer id kept
    CHECK_EQ(rd32(&ident[0x00]), 1u);
    CHECK_EQ(rd32(&ident[0x04]), 1u);
    CHECK_EQ(rd16(&ident[0x08]), (u16)7);
    CHECK_EQ(rd16(&ident[0x0A]), (u16)7);
    CHECK_EQ(ident[0x5F], (u8)0x02);           // the walker's own byte, not the game's 0
    std::string status = w.StatusJson();
    CHECK(status.find("\"hasPokemon\":true") != std::string::npos);

    // The game saved the transfer (its save now says a Pokémon is out on this walker)
    w.OnGameSave(false);
    CHECK(status.find("\"awaitingGameSave\":true") != std::string::npos);
    CHECK(w.StatusJson().find("\"awaitingGameSave\":false") != std::string::npos);

    // Another save that doesn't know this walker must be refused with the game's own message,
    // and so must a save that thinks the walker has no Pokémon. (The walker then answers the
    // way such a save expects for a few minutes, in case it lost the transfer; see TestLost*.)
    HgssGame stranger(w, clk, 7, 0x0BADF00D);
    CHECK_EQ(stranger.Register(), (int)ResAlreadyRegistered);
    clk.unixSec += 6 * 60;
    CHECK_EQ(stranger.StartStroll(), (int)ResAlreadyHasPokemon);
    // it tries again: the walker answers as if it had no Pokémon, but it isn't this trainer's walker
    CHECK_EQ(stranger.StartStroll(), (int)ResWrongWalkerB);
    // after which the walker answers truthfully again, and nothing changed on it
    CHECK_EQ(stranger.StartStroll(), (int)ResAlreadyHasPokemon);
    CHECK_EQ(Ee(w, 0x00ED, 0x68)[0x5B] & 3, 3);
    CHECK(Ee(w, 0x8F00, 0x28BE) == hg.route);
    clk.unixSec += 6 * 60;

    // Walk, then come home
    std::string e2;
    CHECK(w.AddSteps(2345, 0, &e2));
    r = hg.ReturnFromStroll();
    CHECK_EQ(r, (int)ResSuccess);
    CHECK_EQ(hg.sentCmds.back(), (u8)0xF4);
    CHECK(rd32be(&hg.W[0x64]) >= 2345u);       // steps the game credits
    u16 watts = (u16)(hg.results[0x0A] << 8 | hg.results[0x0B]);
    CHECK_EQ(watts, (u16)(2345 / 20));
    CHECK(std::memcmp(hg.routeHeader.data(), hg.route.data(), 0x10) == 0);
    ident = Ee(w, 0x00ED, 0x68);
    CHECK_EQ(ident[0x5B] & 2, 0);               // Pokémon went home
    CHECK_EQ(ident[0x5B] & 1, 1);               // still registered to this game
    w.OnGameSave(false);

    // Next stroll on the now-registered walker
    r = hg.StartStroll();
    CHECK_EQ(r, (int)ResSuccess);
    ident = Ee(w, 0x00ED, 0x68);
    CHECK_EQ(ident[0x5B] & 3, 3);
    CHECK(Ee(w, 0x8F00, 0x28BE) == hg.route);

    // Gifts / send-home flow
    CHECK_EQ(hg.ReceiveGifts(), (int)ResSuccess);
}

static void TestOtherGame()
{
    std::printf("walker registered to HeartGold, used with SoulSilver / another trainer\n");
    Clock clk;
    VirtualPokewalker w;
    Setup(w, clk);
    std::string err;
    CHECK(w.Open(TempPath("o.bin"), &err));
    HgssGame hg(w, clk, 7, 777);
    CHECK_EQ(hg.Register(), (int)ResSuccess);
    std::string e;
    CHECK(w.AddSteps(100, 0, &e));
    CHECK_EQ(hg.ReturnFromStroll(), (int)ResSuccess);

    HgssGame ss(w, clk, 8 /* SoulSilver */, 777);
    CHECK_EQ(ss.StartStroll(), (int)ResWrongWalker9);
    HgssGame other(w, clk, 7, 778);
    CHECK_EQ(other.StartStroll(), (int)ResWrongWalkerB);
}

static void TestErase()
{
    std::printf("erase Pokéwalker records, then register again\n");
    Clock clk;
    VirtualPokewalker w;
    Setup(w, clk);
    std::string err;
    CHECK(w.Open(TempPath("e.bin"), &err));
    HgssGame hg(w, clk, 7, 4242);
    CHECK_EQ(hg.Register(), (int)ResSuccess);
    CHECK_EQ(hg.Erase(true), (int)ResSuccess);
    std::vector<u8> ident = Ee(w, 0x00ED, 0x68);
    CHECK_EQ(ident[0x5B] & 1, 0);
    CHECK_EQ(hg.StartStroll(), (int)ResNotRegistered);
    CHECK_EQ(hg.Register(), (int)ResSuccess);
    CHECK_EQ(Ee(w, 0x00ED, 0x68)[0x5B] & 3, 3);
}

// What the game's save knows about the walker: POKEWALKER.unk_000 (src/pokewalker.c), 0 none,
// 1 paired, 2 a Pokémon out on a stroll. The menu picks the flow from it (ov112_021E9338:
// register while it is 0), the app records a transfer that worked (ov112_021EE628) and then
// saves (SaveGameNormal), which the walker sees as save memory writes. After a refusal the
// game offers to try again (message 7, CONNECT), which runs the same flow.
struct HgssSave
{
    int state = 0;
};

static int GoForStroll(VirtualPokewalker& w, HgssGame& g, HgssSave& s, bool saves = true)
{
    int r = s.state == 0 ? g.Register() : g.StartStroll();
    if (r == ResSuccess)
    {
        s.state = 2;
        if (saves) w.OnGameSave(false);
    }
    return r;
}

static int ComeHome(VirtualPokewalker& w, HgssGame& g, HgssSave& s, bool saves = true)
{
    int r = g.ReturnFromStroll();
    if (r == ResSuccess)
    {
        s.state = 1;
        if (saves) w.OnGameSave(false);
    }
    return r;
}

static u16 CollectedWatts(const HgssGame& g) { return (u16)(g.results[0x0A] << 8 | g.results[0x0B]); }

static void TestLostPairing()
{
    std::printf("the game forgot pairing with the walker (rewound after it saved): CONNECT again pairs it\n");
    Clock clk;
    VirtualPokewalker w;
    Setup(w, clk);
    std::string err;
    CHECK(w.Open(TempPath("lp.bin"), &err));
    HgssGame ss(w, clk, 8 /* SoulSilver */, 0x00C0FFEE);
    HgssSave save;
    CHECK_EQ(GoForStroll(w, ss, save), (int)ResSuccess);
    CHECK(w.AddSteps(1000, 0, &err)); // 50 W on the walker since
    // a save state from before the transfer: the save memory goes back too
    w.OnGameSave(true);
    save.state = 0;

    // the Pokémon is in the PC again, and the game says "already registered"...
    CHECK_EQ(GoForStroll(w, ss, save), (int)ResAlreadyRegistered);
    std::string note = w.TakeNotice();
    CHECK(note.find("SoulSilver lost track of this Pokéwalker") != std::string::npos);
    CHECK(note.find("CONNECT again") != std::string::npos);
    CHECK(w.TakeNotice().empty());
    // ... so the player taps CONNECT again (with the same or another Pokémon): paired again
    ss.route[0] = 133;
    CHECK_EQ(GoForStroll(w, ss, save), (int)ResSuccess);
    CHECK_EQ(Ee(w, 0x00ED, 0x68)[0x5B] & 3, 3);
    CHECK_EQ(Ee(w, 0x8F00, 1)[0], (u8)133);
    CHECK(Ee(w, 0x8F00, 0x28BE) == ss.route);
    CHECK(Ee(w, 0xCC00, 0x224) == ss.team);
    CHECK(w.TakeNotice().find("Back in step with SoulSilver") != std::string::npos);
    // the steps and watts since the lost transfer stay: the game never collected them
    std::string status = w.StatusJson();
    CHECK(status.find("\"watts\":50") != std::string::npos);
    CHECK(status.find("\"todaySteps\":1000") != std::string::npos);
    CHECK(status.find("\"pairedWith\":\"SoulSilver\"") != std::string::npos);
    // and from then on everything works as usual
    CHECK(w.AddSteps(200, 0, &err));
    CHECK_EQ(ComeHome(w, ss, save), (int)ResSuccess);
    CHECK_EQ(CollectedWatts(ss), (u16)60);
    CHECK_EQ(GoForStroll(w, ss, save), (int)ResSuccess);

    // CONNECT again much later: the walker answers truthfully once more
    w.OnGameSave(true);
    save.state = 0;
    CHECK_EQ(GoForStroll(w, ss, save), (int)ResAlreadyRegistered);
    clk.unixSec += 6 * 60;
    CHECK_EQ(GoForStroll(w, ss, save), (int)ResAlreadyRegistered);
    CHECK_EQ(GoForStroll(w, ss, save), (int)ResSuccess);
}

static void TestLostStroll()
{
    std::printf("the game forgot a stroll start (rewound after it saved): CONNECT again takes the new Pokémon\n");
    Clock clk;
    VirtualPokewalker w;
    Setup(w, clk);
    std::string err;
    CHECK(w.Open(TempPath("ls.bin"), &err));
    HgssGame hg(w, clk, 7, 0x4444);
    HgssSave save;
    CHECK_EQ(GoForStroll(w, hg, save), (int)ResSuccess);
    CHECK_EQ(ComeHome(w, hg, save), (int)ResSuccess);
    CHECK_EQ(GoForStroll(w, hg, save), (int)ResSuccess);
    // a catch on the walker, and some watts left
    CHECK(w.AddSteps(400, 0, &err)); // 20 W
    int slot = -1;
    std::string js;
    CHECK(w.RadarEncounter(&slot, &js, &err)); // 10 W
    CHECK(w.StoreCaught(slot, &err));
    w.OnGameSave(true);
    save.state = 1;

    CHECK_EQ(GoForStroll(w, hg, save), (int)ResAlreadyHasPokemon);
    CHECK(w.TakeNotice().find("lost track of") != std::string::npos);
    CHECK_EQ(GoForStroll(w, hg, save), (int)ResSuccess);
    // the game collected the walker's watts as the stroll started; the catch waits for the return
    CHECK_EQ(CollectedWatts(hg), (u16)10);
    CHECK(w.StatusJson().find("\"caught\":1") != std::string::npos);
    CHECK_EQ(ComeHome(w, hg, save), (int)ResSuccess);
    CHECK(rd16(&hg.results[0x0C]) != 0); // the caught Pokémon came home too
}

static void TestLostBeforeSave()
{
    std::printf("the game was reset, rewound or closed before it saved a transfer: right on the first try\n");
    for (int how = 0; how < 3; how++)
    {
        static const char* names[] = {"lb0.bin", "lb1.bin", "lb2.bin"};
        std::printf("  (%s)\n", how == 0 ? "game reset" : how == 1 ? "save state loaded" : "app closed");
        Clock clk;
        VirtualPokewalker w;
        Setup(w, clk);
        std::string err, path = TempPath(names[how]);
        CHECK(w.Open(path, &err));
        HgssGame hg(w, clk, 7, 0x1111);
        HgssSave save;
        auto lose = [&]() {
            if (how == 0) w.OnCartReset();
            else if (how == 1) w.OnGameSave(true);
            else { w.Close(); CHECK(w.Open(path, &err)); }
        };

        // paired and out on a stroll, but the game never saved it
        CHECK_EQ(GoForStroll(w, hg, save, false), (int)ResSuccess);
        CHECK(w.StatusJson().find("\"awaitingGameSave\":true") != std::string::npos);
        lose();
        save.state = 0;
        CHECK(w.StatusJson().find("\"awaitingGameSave\":false") != std::string::npos);
        CHECK(w.TakeNotice().find("didn't save the transfer") != std::string::npos);
        CHECK_EQ(GoForStroll(w, hg, save), (int)ResSuccess);

        // a stroll start, the same way
        CHECK_EQ(ComeHome(w, hg, save), (int)ResSuccess);
        CHECK_EQ(GoForStroll(w, hg, save, false), (int)ResSuccess);
        lose();
        save.state = 1;
        CHECK_EQ(GoForStroll(w, hg, save), (int)ResSuccess);

        // a return, the same way: the walker brings the stroll back
        CHECK(w.AddSteps(400, 0, &err)); // 20 W
        CHECK_EQ(ComeHome(w, hg, save, false), (int)ResSuccess);
        CHECK_EQ(Ee(w, 0x00ED, 0x68)[0x5B] & 2, 0);
        CHECK(w.AddSteps(100, 0, &err)); // 5 W walked since
        lose();
        save.state = 2;
        CHECK_EQ(Ee(w, 0x00ED, 0x68)[0x5B] & 3, 3);
        CHECK_EQ(Ee(w, 0x8F00, 1)[0], hg.route[0]);
        CHECK(w.TakeNotice().find("Return from a Stroll again") != std::string::npos);
        CHECK(w.StatusJson().find("\"todaySteps\":") != std::string::npos);
        CHECK_EQ(ComeHome(w, hg, save), (int)ResSuccess);
        CHECK_EQ(CollectedWatts(hg), (u16)25); // the stroll's 20 W again, and the 5 W since
    }
}

static void TestLostReturn()
{
    std::printf("the game forgot a return (rewound after it saved): the walker brings the stroll back\n");
    Clock clk;
    VirtualPokewalker w;
    Setup(w, clk);
    std::string err;
    CHECK(w.Open(TempPath("lr.bin"), &err));
    HgssGame hg(w, clk, 7, 0x5555);
    HgssSave save;
    CHECK_EQ(GoForStroll(w, hg, save), (int)ResSuccess);
    CHECK(w.AddSteps(600, 0, &err)); // 30 W
    CHECK_EQ(ComeHome(w, hg, save), (int)ResSuccess);
    CHECK_EQ(CollectedWatts(hg), (u16)30);
    w.OnGameSave(true);
    save.state = 2;
    // "There are not any Pokémon on this Pokéwalker." ... then CONNECT again brings it home
    CHECK_EQ(ComeHome(w, hg, save), (int)ResNoPokemon);
    CHECK(w.TakeNotice().find("brought its stroll back") != std::string::npos);
    CHECK_EQ(ComeHome(w, hg, save), (int)ResSuccess);
    CHECK_EQ(CollectedWatts(hg), (u16)30);
    // every lost return can be brought back: the walker keeps a copy of its last stroll...
    w.OnGameSave(true);
    save.state = 2;
    CHECK_EQ(ComeHome(w, hg, save), (int)ResNoPokemon);
    CHECK_EQ(ComeHome(w, hg, save), (int)ResSuccess);
    // ...until it starts over; then the game's "Send Pokémon back home" is the way
    CHECK(w.Unpair(&err));
    save.state = 2;
    CHECK_EQ(ComeHome(w, hg, save), (int)ResNotRegistered);
    CHECK(w.TakeNotice().find("Send Pokémon back home") != std::string::npos);
}

static void TestWrongGuess()
{
    std::printf("the walker thought the game lost a transfer, but it hadn't: one refusal, then normal\n");
    Clock clk;
    VirtualPokewalker w;
    Setup(w, clk);
    std::string err;
    CHECK(w.Open(TempPath("wg.bin"), &err));
    HgssGame hg(w, clk, 7, 0x2222);
    HgssSave save;
    CHECK_EQ(GoForStroll(w, hg, save, false), (int)ResSuccess);
    w.OnCartReset(); // say the game had saved after all, without the walker noticing
    CHECK_EQ(ComeHome(w, hg, save), (int)ResNotRegistered);
    CHECK(w.TakeNotice().find("guessed wrong") != std::string::npos);
    CHECK_EQ(ComeHome(w, hg, save), (int)ResSuccess);
}

static void TestOtherVersionAndUnpair()
{
    std::printf("paired with HeartGold, SoulSilver can't take it over; Unpair keeps the steps\n");
    Clock clk;
    VirtualPokewalker w;
    Setup(w, clk);
    std::string err;
    CHECK(w.Open(TempPath("ou.bin"), &err));
    HgssGame hg(w, clk, 7, 0x3333);
    HgssGame ss(w, clk, 8, 0x3333);
    HgssSave hgSave, ssSave;
    CHECK_EQ(GoForStroll(w, hg, hgSave, false), (int)ResSuccess);
    // HeartGold lost it (so the walker would pair with HeartGold again), but SoulSilver connects
    w.OnCartReset();
    CHECK_EQ(GoForStroll(w, ss, ssSave), (int)ResAlreadyRegistered);
    CHECK(w.TakeNotice().find("paired with HeartGold") != std::string::npos);
    CHECK_EQ(GoForStroll(w, ss, ssSave), (int)ResAlreadyRegistered);
    CHECK_EQ(Ee(w, 0x00ED, 0x68)[0x5B] & 3, 3);
    CHECK(w.StatusJson().find("\"pairedWith\":\"HeartGold\"") != std::string::npos);

    CHECK(w.AddSteps(777, 0, &err));
    CHECK(w.AddSteps(55, 2, &err));
    CHECK(w.Unpair(&err));
    std::string status = w.StatusJson();
    CHECK(status.find("\"registered\":false") != std::string::npos);
    CHECK(status.find("\"hasPokemon\":false") != std::string::npos);
    CHECK(status.find("\"todaySteps\":777") != std::string::npos);
    CHECK(status.find("\"history\":[0,55,") != std::string::npos);
    CHECK(status.find("pairedWith") == std::string::npos);
    CHECK_EQ(GoForStroll(w, ss, ssSave), (int)ResSuccess);
    CHECK(w.StatusJson().find("\"pairedWith\":\"SoulSilver\"") != std::string::npos);
    // HeartGold's old fix doesn't come back to haunt the new pairing
    hgSave.state = 0;
    CHECK_EQ(GoForStroll(w, hg, hgSave), (int)ResAlreadyRegistered);
    CHECK_EQ(GoForStroll(w, hg, hgSave), (int)ResAlreadyRegistered);
}

static void TestUnalignedWrite()
{
    std::printf("unaligned EEPROM write (command 0x0A)\n");
    Clock clk;
    VirtualPokewalker w;
    Setup(w, clk);
    std::string err;
    CHECK(w.Open(TempPath("u.bin"), &err));
    // Drive the link by hand: connect, then send a 0x0A write to 0x0081..0x0083 like ov112_021E6A6C op 0xE2.
    u8 buf[0xB9];
    int n = 0;
    for (int i = 0; i < 200 && n != 1; i++) { clk.ms += 4; n = w.OnGamePoll(buf, 0xB8); }
    CHECK_EQ(n, 1);
    u8 half[4] = {1, 2, 3, 4};
    std::vector<u8> fa = BuildPacket(0xFA, 1, half, nullptr, 0);
    AirScramble(fa.data(), fa.size());
    w.OnGameSend(fa.data(), fa.size());
    clk.ms += 4;
    n = w.OnGamePoll(buf, 0xB8);
    AirScramble(buf, (size_t)n);
    CHECK_EQ(buf[0], (u8)0xF8);
    CHECK(buf[1] >= 1 && buf[1] <= 2);         // the game drops the link otherwise
    u8 sess[4];
    for (int i = 0; i < 4; i++) sess[i] = buf[4 + i] ^ half[i];
    u8 payload[4] = {0x81, 0x11, 0x22, 0x33};
    std::vector<u8> wr = BuildPacket(0x0A, 0x00, sess, payload, 4);
    AirScramble(wr.data(), wr.size());
    w.OnGameSend(wr.data(), wr.size());
    clk.ms += 4;
    n = w.OnGamePoll(buf, 0xB8);
    AirScramble(buf, (size_t)n);
    CHECK_EQ(n, 8);
    CHECK_EQ(buf[0], (u8)0x04);
    std::vector<u8> got = Ee(w, 0x0081, 3);
    CHECK_EQ(got[0], (u8)0x11);
    CHECK_EQ(got[1], (u8)0x22);
    CHECK_EQ(got[2], (u8)0x33);
}

int main()
{
    TestFullStroll(true);
    TestFullStroll(false);
    TestOtherGame();
    TestErase();
    TestLostPairing();
    TestLostStroll();
    TestLostBeforeSave();
    TestLostReturn();
    TestWrongGuess();
    TestOtherVersionAndUnpair();
    TestUnalignedWrite();
    std::printf("%d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
