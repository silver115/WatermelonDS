// Tests for the HeartGold/SoulSilver copy check (RomCheck.cpp): SHA-1, backward LZ,
// and finding overlay 112 through the ROM header, overlay table and FAT.

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "RomCheck.h"

using namespace pokewalker;

static int failures = 0, checks = 0;
#define CHECK(cond)                                                                 \
    do {                                                                            \
        checks++;                                                                   \
        if (!(cond)) { failures++; std::printf("  FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); } \
    } while (0)

static void Wr32(std::vector<u8>& b, size_t off, u32 v)
{
    b[off] = (u8)v; b[off + 1] = (u8)(v >> 8); b[off + 2] = (u8)(v >> 16); b[off + 3] = (u8)(v >> 24);
}

// 1024 bytes of Pattern(), compressed by Nintendo's backward LZ as rebuilt by the
// pret/pokeheartgold "compstatic" tool (the format HG/SS overlays use in the ROM).
static const u8 kBlzBlob[] = {
    0x08, 0x30, 0x13, 0xF0, 0x13, 0xF0, 0x08, 0x80, 0x72, 0x21, 0x50, 0x6F, 0x0F, 0x6B, 0x65, 0x77,
    0x61, 0x6C, 0x6B, 0x65, 0x08, 0x30, 0x80, 0x13, 0xF0, 0x13, 0xF0, 0x08, 0x80, 0x6A, 0x64, 0x73,
    0x20, 0x51, 0x07, 0x6E, 0x6A, 0x64, 0x76, 0x60, 0x6D, 0x08, 0x30, 0x13, 0xF0, 0xC0, 0x13, 0xF0,
    0x08, 0x80, 0x63, 0x6E, 0x69, 0x67, 0x70, 0x23, 0x03, 0x52, 0x6D, 0x69, 0x67, 0x75, 0x08, 0x30,
    0x13, 0xF0, 0x13, 0xF0, 0xE0, 0x08, 0x80, 0x66, 0x74, 0x62, 0x6F, 0x68, 0x66, 0x71, 0x01, 0x22,
    0x53, 0x6C, 0x68, 0x08, 0x30, 0x13, 0xF0, 0x13, 0xF0, 0x08, 0x80, 0xF0, 0x6B, 0x6F, 0x61, 0x73,
    0x65, 0x68, 0x6F, 0x61, 0x00, 0x76, 0x25, 0x54, 0x08, 0x30, 0x13, 0xF0, 0x13, 0xF0, 0x08, 0x80,
    0x24, 0x78, 0x55, 0x6A, 0x6E, 0x60, 0x72, 0x64, 0x69, 0x6E, 0x00, 0x60, 0x77, 0x08, 0x30, 0x13,
    0xF0, 0x13, 0xF0, 0x08, 0x80, 0x63, 0x74, 0x3C, 0x27, 0x56, 0x69, 0x6D, 0x63, 0x71, 0x67, 0x6A,
    0x00, 0x6D, 0x08, 0x30, 0x13, 0xF0, 0x13, 0xF0, 0x08, 0x80, 0x6B, 0x6C, 0x62, 0x1E, 0x75, 0x26,
    0x57, 0x68, 0x6C, 0x62, 0x70, 0x66, 0x00, 0x08, 0x30, 0x13, 0xF0, 0x13, 0xF0, 0x08, 0x80, 0x7F,
    0x69, 0x64, 0x63, 0x0F, 0x6D, 0x7A, 0x29, 0x58, 0x67, 0x63, 0x6D, 0x08, 0x30, 0x80, 0x13, 0xF0,
    0x13, 0xF0, 0x08, 0x80, 0x62, 0x6C, 0x7E, 0x68, 0x65, 0x07, 0x62, 0x6C, 0x7B, 0x28, 0x59, 0x66,
    0x08, 0x30, 0x13, 0xF0, 0xC0, 0x13, 0xF0, 0x08, 0x80, 0x5A, 0x65, 0x61, 0x6F, 0x7D, 0x6B, 0x03,
    0x66, 0x61, 0x6F, 0x78, 0x2B, 0x08, 0x30, 0x13, 0xF0, 0x13, 0xF0, 0xE0, 0x08, 0x80, 0x79, 0x2A,
    0x5B, 0x64, 0x60, 0x6E, 0x7C, 0x01, 0x6A, 0x67, 0x60, 0x6E, 0x08, 0x30, 0x13, 0xF0, 0x13, 0xF0,
    0x08, 0x80, 0xF0, 0x67, 0x69, 0x7E, 0x2D, 0x5C, 0x63, 0x67, 0x69, 0x00, 0x7B, 0x6D, 0x60, 0x08,
    0x30, 0x13, 0xF0, 0x13, 0xF0, 0x08, 0x80, 0x6C, 0x78, 0x61, 0x66, 0x68, 0x7F, 0x2C, 0x5D, 0x62,
    0x66, 0x00, 0x68, 0x7A, 0x08, 0x30, 0x13, 0xF0, 0x13, 0xF0, 0x08, 0x80, 0x6B, 0x79, 0x3C, 0x6F,
    0x62, 0x65, 0x6B, 0x7C, 0x2F, 0x5E, 0x61, 0x00, 0x65, 0x08, 0x30, 0x13, 0xF0, 0x13, 0xF0, 0x08,
    0x80, 0x60, 0x64, 0x6A, 0x1E, 0x78, 0x6E, 0x63, 0x64, 0x6A, 0x7D, 0x2E, 0x5F, 0x00, 0xFF, 0xFF,
    0x58, 0x01, 0x00, 0x0A, 0xA8, 0x02, 0x00, 0x00,
};

static std::vector<u8> Pattern()
{
    const char* w = "Pokewalker!";
    std::vector<u8> b(1024);
    for (size_t i = 0; i < b.size(); i++) b[i] = (u8)(w[i % 11] ^ (i / 64));
    return b;
}

static void TestSha1()
{
    std::printf("SHA-1 known answers\n");
    CHECK(Sha1Hex((const u8*)"abc", 3) == "a9993e364706816aba3e25717850c26c9cd0d89d");
    CHECK(Sha1Hex((const u8*)"", 0) == "da39a3ee5e6b4b0d3255bfef95601890afd80709");
    std::vector<u8> mil(1000000, 'a');
    CHECK(Sha1Hex(mil.data(), mil.size()) == "34aa973cd4c4daa4f61eeb2bdbad27316534016f");
}

static void TestBlz()
{
    std::printf("backward LZ\n");
    std::vector<u8> out;
    CHECK(UncompressBackward(kBlzBlob, sizeof(kBlzBlob), &out));
    CHECK(out == Pattern());
    // Corrupt trailers are rejected, not read out of bounds.
    std::vector<u8> bad(kBlzBlob, kBlzBlob + sizeof(kBlzBlob));
    Wr32(bad, bad.size() - 8, 0xFFFFFFFF);
    CHECK(!UncompressBackward(bad.data(), bad.size(), &out));
    std::vector<u8> tiny(4, 0);
    CHECK(!UncompressBackward(tiny.data(), tiny.size(), &out));
    for (size_t cut = 1; cut < 40; cut += 3)
    {
        std::vector<u8> shortened(kBlzBlob + cut, kBlzBlob + sizeof(kBlzBlob));
        UncompressBackward(shortened.data(), shortened.size(), &out); // must not crash
    }
}

// Builds a small ROM laid out like a retail NDS image with overlay 112.
static std::vector<u8> MakeRom(const std::vector<u8>& overlayFile, bool compressedFlag, u32 compSize, u32 ramSize)
{
    std::vector<u8> rom(0x40000, 0xFF);
    std::memset(rom.data(), 0, 0x200);
    std::memcpy(rom.data() + 0x0C, "IPGE", 4);
    const u32 ovtOff = 0x1000, numOverlays = 120, fatOff = 0x2000, numFiles = 200;
    Wr32(rom, 0x48, fatOff);
    Wr32(rom, 0x4C, numFiles * 8);
    Wr32(rom, 0x50, ovtOff);
    Wr32(rom, 0x54, numOverlays * 32);
    for (u32 i = 0; i < numOverlays; i++)
    {
        size_t e = ovtOff + i * 32;
        std::memset(&rom[e], 0, 32);
        Wr32(rom, e + 0, i);                       // id
        Wr32(rom, e + 4, 0x021E5900);              // ram address
        Wr32(rom, e + 8, i == 112 ? ramSize : 0x100);
        Wr32(rom, e + 24, i);                      // file id
        Wr32(rom, e + 28, i == 112 ? ((compressedFlag ? 0x01000000u : 0u) | compSize) : 0);
    }
    const u32 dataOff = 0x10000;
    for (u32 f = 0; f < numFiles; f++)
    {
        Wr32(rom, fatOff + f * 8, f == 112 ? dataOff : 0x3000);
        Wr32(rom, fatOff + f * 8 + 4, f == 112 ? dataOff + (u32)overlayFile.size() : 0x3004);
    }
    std::memcpy(&rom[dataOff], overlayFile.data(), overlayFile.size());
    return rom;
}

static void TestRomParsing()
{
    std::printf("finding overlay 112 in a ROM\n");
    const u32 ipge = (u32)'I' | ((u32)'P' << 8) | ((u32)'G' << 16) | ((u32)'E' << 24);
    std::vector<u8> overlay(0x900);
    for (size_t i = 0; i < overlay.size(); i++) overlay[i] = (u8)(i ^ 0x5A);

    // Uncompressed overlay: its hash is the hash of the file itself.
    std::vector<u8> rom = MakeRom(overlay, false, 0, (u32)overlay.size());
    RomCheckResult r = CheckPokewalkerRom(ipge, rom.data(), (u32)rom.size());
    CHECK(r.release == "SoulSilver (USA)");
    CHECK(r.rom == CopyState::Different);           // synthetic, not the retail ROM
    CHECK(r.romSha1 == Sha1Hex(rom.data(), rom.size()));
    CHECK(r.overlaySha1 == Sha1Hex(overlay.data(), overlay.size()));
    CHECK(r.overlay == CopyState::Different);       // not the retail overlay either
    CHECK(!r.romTrimmed);
    // The other 119 overlays are compared too; none of these dummies is the original.
    CHECK(r.overlaysChecked == 119);
    CHECK(r.differentOverlays.size() == 119);
    CHECK(!r.differentOverlays.empty() && r.differentOverlays[0] == 0 && r.differentOverlays.back() == 119);
    CHECK(std::string(OverlayName(123)) == "ds_protect");
    CHECK(std::string(OverlayName(1)) == "field");
    CHECK(std::string(OverlayName(112)).empty());
    CHECK(std::string(OverlayName(500)).empty());

    // Compressed overlay: decompressed before hashing.
    std::vector<u8> enc(kBlzBlob, kBlzBlob + sizeof(kBlzBlob));
    std::vector<u8> expect = Pattern();
    rom = MakeRom(enc, true, (u32)enc.size(), (u32)expect.size());
    r = CheckPokewalkerRom(ipge, rom.data(), (u32)rom.size());
    CHECK(r.overlaySha1 == Sha1Hex(expect.data(), expect.size()));
    CHECK(r.detail.empty());

    // Trimmed ROM: the file table points past the end.
    std::vector<u8> cut(rom.begin(), rom.begin() + 0x8000);
    r = CheckPokewalkerRom(ipge, cut.data(), (u32)cut.size());
    CHECK(r.overlay == CopyState::Missing);
    CHECK(!r.detail.empty());

    // Other releases have no reference, but are still hashed.
    const u32 ipgp = (u32)'I' | ((u32)'P' << 8) | ((u32)'G' << 16) | ((u32)'P' << 24);
    rom = MakeRom(overlay, false, 0, (u32)overlay.size());
    r = CheckPokewalkerRom(ipgp, rom.data(), (u32)rom.size());
    CHECK(r.release.empty());
    CHECK(r.rom == CopyState::Unknown);
    CHECK(r.overlay == CopyState::Unknown);
    CHECK(!r.overlaySha1.empty());
    CHECK(r.overlaysChecked == 0);
}

// A trimmed copy (0xFF tail cut after the used area and its signature) that melonDS
// padded back up with zeros still counts as the original image.
static void TestTrimmed()
{
    std::printf("trimmed copies\n");
    const u32 chip = 0x40000, used = 0x30000;
    std::vector<u8> original(chip, 0xFF);
    for (u32 i = 0; i < used; i++) original[i] = (u8)(i * 7 + (i >> 9));
    original[0x14] = 1;                              // 128 KiB << 1
    original[0x80] = (u8)used; original[0x81] = (u8)(used >> 8); original[0x82] = (u8)(used >> 16); original[0x83] = 0;
    for (u32 i = 0; i < 0x88; i++) original[used + i] = (u8)(0xA0 + i); // signature
    const std::string ref = Sha1Hex(original.data(), original.size());

    std::vector<u8> padded(original.begin(), original.begin() + used + 0x88);
    padded.resize(chip, 0x00);
    CHECK(MatchesWhenUntrimmed(padded.data(), (u32)padded.size(), ref));
    CHECK(MatchesWhenUntrimmed(original.data(), (u32)original.size(), ref));

    std::vector<u8> changed = padded;
    changed[0x1234] ^= 1;
    CHECK(!MatchesWhenUntrimmed(changed.data(), (u32)changed.size(), ref));

    std::vector<u8> noSignature(original.begin(), original.begin() + used);
    noSignature.resize(chip, 0x00);
    CHECK(!MatchesWhenUntrimmed(noSignature.data(), (u32)noSignature.size(), ref));

    std::vector<u8> junkTail = padded;
    junkTail[chip - 1] = 0x42;
    CHECK(!MatchesWhenUntrimmed(junkTail.data(), (u32)junkTail.size(), ref));

    // Smaller image than the chip (padded only to the next power of two) still works.
    std::vector<u8> small(original.begin(), original.begin() + used + 0x88);
    small.resize(0x38000 > used + 0x88 ? 0x38000 : used + 0x88, 0x00);
    CHECK(MatchesWhenUntrimmed(small.data(), (u32)small.size(), ref));

    std::vector<u8> bogus = padded;
    bogus[0x14] = 0x40;                              // absurd chip size
    CHECK(!MatchesWhenUntrimmed(bogus.data(), (u32)bogus.size(), ref));
}

int main()
{
    TestSha1();
    TestBlz();
    TestRomParsing();
    TestTrimmed();
    std::printf("%d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
