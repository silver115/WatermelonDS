/*
    Checks whether a HeartGold/SoulSilver ROM is an unmodified retail copy, and in
    particular whether its Pokéwalker program (ARM9 overlay 112) is intact.
    GPL-3.0-or-later, see PwCommon.h.
*/

#ifndef POKEWALKER_ROMCHECK_H
#define POKEWALKER_ROMCHECK_H

#include <string>
#include <vector>
#include "PwCommon.h"

namespace pokewalker
{

enum class CopyState
{
    Unknown,    // no reference hash for this release
    Original,   // matches the original release
    Different,  // differs from the original release
    Missing,    // the part could not be found or read
};

struct RomCheckResult
{
    std::string release;          // e.g. "SoulSilver (USA)"; empty when unknown
    std::string romSha1;
    CopyState rom = CopyState::Unknown;
    bool romTrimmed = false;      // matches the original once its cut-off 0xFF tail is restored
    std::string overlaySha1;      // overlay 112, decompressed
    CopyState overlay = CopyState::Unknown;
    int overlaysChecked = 0;      // other ARM9 overlays compared with the original release
    std::vector<int> differentOverlays; // ids of those that differ or could not be read
    std::string detail;           // short reason when something is off
};

/// Hashes the whole ROM and the decompressed overlay 112. `rom` must be the
/// image as loaded (before melonDS re-encrypts the secure area).
RomCheckResult CheckPokewalkerRom(u32 gamecode, const u8* rom, u32 len);

std::string Sha1Hex(const u8* data, size_t len);

/// The original image is the chip size with everything after the used area (plus the
/// 0x88-byte download-play signature) filled with 0xFF. Trimming tools cut that tail and
/// melonDS pads the image back up with zeros; this restores it and compares the SHA-1.
bool MatchesWhenUntrimmed(const u8* rom, u32 len, const std::string& refSha1);

/// NitroSDK backward LZ ("BLZ") as used for compressed overlays. Returns false if the
/// trailer is inconsistent.
bool UncompressBackward(const u8* in, size_t inLen, std::vector<u8>* out);

const char* CopyStateName(CopyState state);

/// Name the decompilation gives an ARM9 overlay ("field", "ds_protect", ...), or "".
const char* OverlayName(int id);

}

#endif
