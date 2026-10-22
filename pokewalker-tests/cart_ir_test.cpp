// Integration test: the real melonDS CartRetailIR (SPI commands 0x01/0x02)
// wired through melonDS::Platform IR hooks into the Pokéwalker IR front end,
// exactly as in the Android app. A scripted "game" issues SPI transfers the
// way the DS does and completes pairing + a stroll start + a stroll return.

#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <unistd.h>
#include <vector>

#include "NDSCart.h"
#include "Platform.h"
#include "PokewalkerIR.h"
#include "PwProtocol.h"

using namespace melonDS;

namespace melonDS::Platform
{
void Log(LogLevel, const char* fmt, ...)
{
    if (!getenv("PW_VERBOSE")) return;
    va_list args;
    va_start(args, fmt);
    std::vprintf(fmt, args);
    va_end(args);
}
}

static int failures = 0, checks = 0;
#define CHECK(cond)                                                                 \
    do {                                                                            \
        checks++;                                                                   \
        if (!(cond)) { failures++; std::printf("  FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); } \
    } while (0)

// One SPI transfer as the DS performs it: command byte, then data bytes, with
// chip-select held until the final byte.
static std::vector<u8> SpiRead(NDSCart::CartCommon& cart)
{
    cart.SPIWrite(0x01, 0, false);
    u8 len = cart.SPIWrite(0x00, 1, false);
    std::vector<u8> out;
    for (u32 i = 0; i < len; i++)
        out.push_back(cart.SPIWrite(0x00, 2 + i, i + 1 == len));
    if (len == 0) cart.SPIWrite(0x00, 2, true); // libirc reads one dummy byte to end an empty transfer
    return out;
}

static void SpiSend(NDSCart::CartCommon& cart, const std::vector<u8>& data)
{
    cart.SPIWrite(0x02, 0, false);
    for (size_t i = 0; i < data.size(); i++)
        cart.SPIWrite(data[i], (u32)i + 1, i + 1 == data.size());
}

struct Game
{
    NDSCart::CartCommon& cart;
    u8 session[4] {};

    std::vector<u8> Poll()
    {
        std::vector<u8> r = SpiRead(cart);
        usleep(1000);
        return r;
    }

    void Send(std::vector<u8> pkt)
    {
        pokewalker::AirScramble(pkt.data(), pkt.size());
        SpiSend(cart, pkt);
    }

    bool Connect()
    {
        for (int i = 0; i < 300; i++)
        {
            std::vector<u8> r = Poll();
            if (r.size() == 1 && r[0] == (0xFC ^ 0xAA))
            {
                u8 master[4] = {1, 2, 3, 4};
                Send(pokewalker::BuildPacket(0xFA, 0x01, master, nullptr, 0));
                std::vector<u8> ack = Poll();
                if (ack.size() != 8) return false;
                pokewalker::AirScramble(ack.data(), ack.size());
                if (ack[0] != 0xF8) return false;
                for (int k = 0; k < 4; k++) session[k] = master[k] ^ ack[4 + k];
                return true;
            }
        }
        return false;
    }

    std::vector<u8> Request(u8 cmd, u8 extra, const std::vector<u8>& payload = {})
    {
        Send(pokewalker::BuildPacket(cmd, extra, session, payload.data(), payload.size()));
        for (int i = 0; i < 5; i++)
        {
            std::vector<u8> r = Poll();
            if (!r.empty())
            {
                pokewalker::AirScramble(r.data(), r.size());
                return r;
            }
        }
        return {};
    }
};

static std::unique_ptr<NDSCart::CartRetailIR> MakeCart(const char* gamecode)
{
    std::vector<u8> rom(0x8000, 0);
    std::memcpy(&rom[0x0C], gamecode, 4);
    ROMListEntry params {};
    std::memcpy(&params.GameCode, gamecode, 4);
    params.ROMSize = (u32)rom.size();
    params.SaveMemType = 6; // 512 KiB flash, like HG/SS
    auto cart = std::make_unique<NDSCart::CartRetailIR>(rom.data(), (u32)rom.size(), 0x00000FC2, 2, false, params, nullptr, 0, nullptr);
    cart->Reset();
    return cart;
}

int main()
{
    std::string path = "/tmp/pw_cart_test_" + std::to_string(getpid()) + ".bin";
    std::remove(path.c_str());
    std::remove((path + ".meta").c_str());

    pokewalker::ir::Config cfg;
    cfg.mode = pokewalker::ir::Mode::Virtual;
    cfg.eepromPath = path;
    pokewalker::ir::Configure(cfg);

    std::printf("chip ID and save pass-through\n");
    auto cart = MakeCart("IPKE");
    cart->SPIWrite(0x08, 0, false);
    CHECK(cart->SPIWrite(0x00, 1, true) == 0xAA);

    std::printf("pairing + stroll start through the real cartridge SPI path\n");
    Game g {*cart};
    CHECK(g.Connect());
    std::vector<u8> id = g.Request(0x20, 0x01);
    CHECK(id.size() == 8 + 0x68 && id[0] == 0x22);
    std::vector<u8> rst = g.Request(0x2A, 0x01);
    CHECK(rst.size() == 8 + 40 && rst[0] == 0x2A);

    std::vector<u8> route(128, 0);
    route[0] = 25; // Pikachu
    route[0x0C] = 10;
    std::vector<u8> ack = g.Request(0x02, 0xD7, route);          // raw write 0xD700
    CHECK(ack.size() == 8 && ack[0] == 0x04 && ack[1] == 0xD7);
    std::vector<u8> gfx(128);
    for (size_t i = 0; i < gfx.size(); i++) gfx[i] = (u8)(i / 16);
    std::vector<u8> packed = pokewalker::Lz10Compress(gfx.data(), gfx.size());
    ack = g.Request(0x80, 0x04, packed);                          // compressed write 0x0480
    CHECK(ack.size() == 8 && ack[0] == 0x04 && ack[1] == 0x04);

    std::vector<u8> ident(0x68, 0);
    ident[0x0C] = 0x39; ident[0x0D] = 0x30;                       // TID 12345
    ident[0x5F] = 0x02;
    CHECK(g.Request(0x32, 0x01, ident).size() == 8);
    CHECK(g.Request(0x38, 0x01).size() == 8);
    CHECK(g.Request(0x66, 0x01).size() == 8);

    std::vector<u8> readBack;
    std::string err;
    CHECK(pokewalker::ir::ExportImage(&readBack, &err));
    CHECK(readBack[0x8F00] == 25 && readBack[0x8F0C] == 10);       // route copied at walk start
    CHECK(readBack[0x0480 + 127] == 7);                             // decompressed graphics
    CHECK(readBack[0x00ED + 0x5B] == 0x03);
    std::string status = pokewalker::ir::StatusJson();
    CHECK(status.find("\"speciesName\":\"Pikachu\"") != std::string::npos);
    CHECK(status.find("\"mode\":1") != std::string::npos);

    std::printf("phone steps, then the Pokemon comes home\n");
    CHECK(pokewalker::ir::AddSteps(4000, 0, &err));
    usleep(1600 * 1000); // game leaves the IR screen
    CHECK(g.Connect());
    id = g.Request(0x20, 0x01);
    CHECK(id.size() == 8 + 0x68);
    u32 steps = (u32)id[8 + 0x64] << 24 | (u32)id[8 + 0x65] << 16 | (u32)id[8 + 0x66] << 8 | id[8 + 0x67];
    CHECK(steps == 4000);
    std::vector<u8> w = g.Request(0x0C, 0x01, {0xCE, 0x8A, 2});
    CHECK(w.size() == 10 && ((w[8] << 8) | w[9]) == 200);         // 4000 steps = 200 W
    CHECK(g.Request(0x4E, 0x01).size() == 8);
    CHECK(g.Request(0x66, 0x01).size() == 8);
    status = pokewalker::ir::StatusJson();
    CHECK(status.find("\"hasPokemon\":false") != std::string::npos);

    std::printf("other IR games are left alone\n");
    auto bw = MakeCart("IRBO");                                     // Pokémon Black
    bool anything = false;
    for (int i = 0; i < 40; i++) anything |= !SpiRead(*bw).empty();
    CHECK(!anything);

    std::printf("%d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
