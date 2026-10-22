/*
    HeartGold/SoulSilver copy check. GPL-3.0-or-later, see PwCommon.h.

    Reference hashes are those of the original US releases as rebuilt by the
    pret/pokeheartgold decompilation (rom.sha1 and main.sha1 / OVY_112.sbin).
*/

#include "RomCheck.h"

#include <algorithm>
#include <cstdio>
#include <cstring>

namespace pokewalker
{

namespace
{

struct Reference
{
    u32 gamecode;
    const char* release;
    const char* romSha1;
    const char* overlay112Sha1;
};

constexpr u32 Code(char a, char b, char c, char d)
{
    return (u32)(u8)a | ((u32)(u8)b << 8) | ((u32)(u8)c << 16) | ((u32)(u8)d << 24);
}

const Reference kReferences[] = {
    {Code('I', 'P', 'G', 'E'), "SoulSilver (USA)", "f8dc38ea20c17541a43b58c5e6d18c1732c7e582", "9951646ac4a1e7560de24d50d53f3b037a214242"},
    {Code('I', 'P', 'K', 'E'), "HeartGold (USA)", "4fcded0e2713dc03929845de631d0932ea2b5a37", "b41527285c55b5be0800e6bd53f76ff1be2c52d0"},
};

#include "OverlayHashes.inc"

u32 Rd32(const u8* p) { return (u32)p[0] | ((u32)p[1] << 8) | ((u32)p[2] << 16) | ((u32)p[3] << 24); }

// --- SHA-1 -----------------------------------------------------------------

struct Sha1
{
    u32 h[5] = {0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476, 0xC3D2E1F0};
    u8 block[64];
    size_t used = 0;
    u64 total = 0;

    static u32 Rol(u32 v, int n) { return (v << n) | (v >> (32 - n)); }

    void Compress(const u8* p)
    {
        u32 w[80];
        for (int i = 0; i < 16; i++)
            w[i] = ((u32)p[i * 4] << 24) | ((u32)p[i * 4 + 1] << 16) | ((u32)p[i * 4 + 2] << 8) | (u32)p[i * 4 + 3];
        for (int i = 16; i < 80; i++)
            w[i] = Rol(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
        u32 a = h[0], b = h[1], c = h[2], d = h[3], e = h[4];
        for (int i = 0; i < 80; i++)
        {
            u32 f, k;
            if (i < 20) { f = (b & c) | (~b & d); k = 0x5A827999; }
            else if (i < 40) { f = b ^ c ^ d; k = 0x6ED9EBA1; }
            else if (i < 60) { f = (b & c) | (b & d) | (c & d); k = 0x8F1BBCDC; }
            else { f = b ^ c ^ d; k = 0xCA62C1D6; }
            u32 t = Rol(a, 5) + f + e + k + w[i];
            e = d; d = c; c = Rol(b, 30); b = a; a = t;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e;
    }

    void Update(const u8* data, size_t len)
    {
        total += len;
        if (used)
        {
            size_t n = std::min(len, sizeof(block) - used);
            std::memcpy(block + used, data, n);
            used += n; data += n; len -= n;
            if (used < sizeof(block)) return;
            Compress(block);
            used = 0;
        }
        while (len >= 64) { Compress(data); data += 64; len -= 64; }
        if (len) { std::memcpy(block, data, len); used = len; }
    }

    std::string Hex()
    {
        u64 bits = total * 8;
        u8 pad = 0x80;
        Update(&pad, 1);
        u8 zero = 0;
        while (used != 56) Update(&zero, 1);
        u8 len[8];
        for (int i = 0; i < 8; i++) len[i] = (u8)(bits >> (56 - 8 * i));
        Update(len, 8);
        char out[41];
        for (int i = 0; i < 5; i++) std::snprintf(out + i * 8, 9, "%08x", h[i]);
        return std::string(out, 40);
    }
};

}

std::string Sha1Hex(const u8* data, size_t len)
{
    Sha1 s;
    s.Update(data, len);
    return s.Hex();
}

const char* CopyStateName(CopyState state)
{
    switch (state)
    {
    case CopyState::Original: return "original";
    case CopyState::Different: return "different";
    case CopyState::Missing: return "missing";
    default: return "unknown";
    }
}

bool UncompressBackward(const u8* in, size_t inLen, std::vector<u8>* out)
{
    if (inLen < 8) return false;
    u32 info = Rd32(in + inLen - 8);
    u32 extra = Rd32(in + inLen - 4);
    size_t encLen = info & 0xFFFFFF;
    size_t hdrLen = info >> 24;
    if (encLen > inLen || hdrLen > encLen || hdrLen < 8 || extra > 0x1000000) return false;

    // The SDK decompresses in place into a buffer that grows by `extra` bytes.
    std::vector<u8>& buf = *out;
    buf.assign(inLen + extra, 0);
    std::memcpy(buf.data(), in, inLen);
    u8* base = buf.data();
    u8* src = base + inLen - hdrLen;       // read pointer, moves down
    u8* start = base + inLen - encLen;     // lowest compressed byte
    u8* dst = base + inLen + extra;        // write pointer, moves down
    while (src > start)
    {
        u8 flags = *--src;
        for (int i = 0; i < 8; i++)
        {
            if (!(flags & 0x80))
            {
                if (src <= base || dst <= base) return false;
                *--dst = *--src;
            }
            else
            {
                if (src - 2 < base) return false;
                u32 hi = *--src;
                u32 lo = *--src;
                u32 disp = (((hi << 8) | lo) & 0x0FFF) + 3;
                u32 len = (hi >> 4) + 3;
                if ((size_t)(dst - base) < len || dst + disp - 1 >= base + buf.size()) return false;
                for (u32 k = 0; k < len; k++)
                {
                    --dst;
                    *dst = dst[disp];
                }
            }
            if (src <= start) break;
            flags <<= 1;
        }
    }
    return true;
}

namespace
{

// Reads ARM9 overlay `entry` (a 32-byte overlay table row) and decompresses it if needed.
bool ReadOverlayImage(const u8* rom, u32 len, u32 fatOff, u32 fatLen, const u8* entry,
                      std::vector<u8>* image, std::string* why, std::string* rawSha1)
{
    u32 fileId = Rd32(entry + 24);
    u32 comp = Rd32(entry + 28);
    if ((u64)fileId * 8 + 8 > fatLen)
    {
        *why = "file entry is out of range";
        return false;
    }
    u32 fStart = Rd32(rom + fatOff + fileId * 8), fEnd = Rd32(rom + fatOff + fileId * 8 + 4);
    if (fEnd < fStart || fEnd > len)
    {
        *why = "data lies outside the ROM (trimmed or damaged)";
        return false;
    }
    if (comp & 0x01000000)
    {
        u32 compSize = comp & 0xFFFFFF;
        if (compSize == 0 || compSize > fEnd - fStart || !UncompressBackward(rom + fStart, compSize, image))
        {
            *why = "does not decompress";
            if (rawSha1) *rawSha1 = Sha1Hex(rom + fStart, fEnd - fStart);
            return false;
        }
    }
    else
        image->assign(rom + fStart, rom + fEnd);
    return true;
}

}

bool MatchesWhenUntrimmed(const u8* rom, u32 len, const std::string& refSha1)
{
    if (rom[0x14] > 12) return false;
    u64 chip = 0x20000ull << rom[0x14];
    u32 used = Rd32(rom + 0x80);
    u64 keep = (u64)used + 0x88;
    if (used < 0x8000 || keep > len || chip < len || chip > 0x20000000ull) return false;
    for (u64 i = keep; i < len; i++)
        if (rom[i] != 0x00 && rom[i] != 0xFF) return false;
    Sha1 h;
    h.Update(rom, (size_t)keep);
    u8 ff[4096];
    std::memset(ff, 0xFF, sizeof(ff));
    for (u64 left = chip - keep; left;)
    {
        size_t n = (size_t)std::min<u64>(left, sizeof(ff));
        h.Update(ff, n);
        left -= n;
    }
    return h.Hex() == refSha1;
}

const char* OverlayName(int id)
{
    return id >= 0 && id < kOverlayCount ? kOverlayNames[id] : "";
}

RomCheckResult CheckPokewalkerRom(u32 gamecode, const u8* rom, u32 len)
{
    RomCheckResult r;
    const Reference* ref = nullptr;
    for (const Reference& candidate : kReferences)
        if (candidate.gamecode == gamecode) ref = &candidate;
    if (ref) r.release = ref->release;

    if (!rom || len < 0x200)
    {
        r.rom = r.overlay = CopyState::Missing;
        r.detail = "no ROM image";
        return r;
    }

    r.romSha1 = Sha1Hex(rom, len);
    if (ref)
    {
        if (r.romSha1 == ref->romSha1)
            r.rom = CopyState::Original;
        else if (MatchesWhenUntrimmed(rom, len, ref->romSha1))
        {
            r.rom = CopyState::Original;
            r.romTrimmed = true;
        }
        else
            r.rom = CopyState::Different;
    }

    // ARM9 overlay table (y9) and file allocation table (FAT) from the header.
    u32 fatOff = Rd32(rom + 0x48), fatLen = Rd32(rom + 0x4C);
    u32 ovtOff = Rd32(rom + 0x50), ovtLen = Rd32(rom + 0x54);
    if ((u64)fatOff + fatLen > len || (u64)ovtOff + ovtLen > len)
    {
        r.overlay = CopyState::Missing;
        r.detail = "the ROM's file tables point past its end (trimmed or damaged)";
        return r;
    }

    const char* const* refOverlays = nullptr;
    if (gamecode == Code('I', 'P', 'G', 'E')) refOverlays = kSoulSilverOverlaySha1;
    else if (gamecode == Code('I', 'P', 'K', 'E')) refOverlays = kHeartGoldOverlaySha1;

    bool found112 = false;
    std::vector<u8> image;
    for (u32 off = 0; off + 32 <= ovtLen; off += 32)
    {
        const u8* entry = rom + ovtOff + off;
        u32 id = Rd32(entry);
        std::string why;
        if (id == 112)
        {
            found112 = true;
            std::string raw;
            if (!ReadOverlayImage(rom, len, fatOff, fatLen, entry, &image, &why, &raw))
            {
                r.overlay = raw.empty() ? CopyState::Missing : CopyState::Different;
                r.overlaySha1 = raw;
                r.detail = "overlay 112 " + why;
                continue;
            }
            r.overlaySha1 = Sha1Hex(image.data(), image.size());
            if (ref)
            {
                r.overlay = r.overlaySha1 == ref->overlay112Sha1 ? CopyState::Original : CopyState::Different;
                u32 ramSize = Rd32(entry + 8);
                if (r.overlay == CopyState::Different && image.size() != ramSize)
                    r.detail = "overlay 112 decompresses to " + std::to_string(image.size()) + " bytes instead of " +
                               std::to_string(ramSize);
            }
            continue;
        }
        if (!refOverlays || id >= (u32)kOverlayCount) continue;
        r.overlaysChecked++;
        if (!ReadOverlayImage(rom, len, fatOff, fatLen, entry, &image, &why, nullptr) ||
            Sha1Hex(image.data(), image.size()) != refOverlays[id])
            r.differentOverlays.push_back((int)id);
    }
    if (!found112)
    {
        r.overlay = CopyState::Missing;
        r.detail = "the Pokewalker program (overlay 112) is not in the overlay table";
    }
    return r;
}

}
