/*
    Virtual Pokéwalker for melonDS Android.

    This program is free software: you can redistribute it and/or modify it under
    the terms of the GNU General Public License as published by the Free Software
    Foundation, either version 3 of the License, or (at your option) any later
    version. See the LICENSE file at the root of this repository.

    Protocol and memory layout facts come from public research: Dmitry Grinberg's
    "PokéWalker hacking" write-up, mamba2410/reverse-pokewalker (packet docs) and
    the picowalker project. This is an independent implementation.
*/

#ifndef POKEWALKER_PWCOMMON_H
#define POKEWALKER_PWCOMMON_H

#include <cstddef>
#include <cstdint>

namespace pokewalker
{

using u8 = uint8_t;
using u16 = uint16_t;
using u32 = uint32_t;
using u64 = uint64_t;
using s32 = int32_t;
using s64 = int64_t;

inline u16 rd16le(const u8* p) { return (u16)(p[0] | (p[1] << 8)); }
inline u16 rd16be(const u8* p) { return (u16)((p[0] << 8) | p[1]); }
inline u32 rd32be(const u8* p) { return ((u32)p[0] << 24) | ((u32)p[1] << 16) | ((u32)p[2] << 8) | (u32)p[3]; }
inline u32 rd32le(const u8* p) { return ((u32)p[3] << 24) | ((u32)p[2] << 16) | ((u32)p[1] << 8) | (u32)p[0]; }
inline void wr16be(u8* p, u16 v) { p[0] = (u8)(v >> 8); p[1] = (u8)v; }
inline void wr16le(u8* p, u16 v) { p[0] = (u8)v; p[1] = (u8)(v >> 8); }
inline void wr32be(u8* p, u32 v) { p[0] = (u8)(v >> 24); p[1] = (u8)(v >> 16); p[2] = (u8)(v >> 8); p[3] = (u8)v; }
inline void wr32le(u8* p, u32 v) { p[0] = (u8)v; p[1] = (u8)(v >> 8); p[2] = (u8)(v >> 16); p[3] = (u8)(v >> 24); }

// ---- EEPROM map (64 KiB ST M95512 in the real device) ----------------------
namespace ee
{
constexpr u32 Size = 0x10000;

constexpr u16 Nintendo = 0x0000;        // "nintendo", 8 bytes
constexpr u16 Factory1 = 0x0080;        // 2 + chk (copy at 0x0180)
constexpr u16 Factory2 = 0x0180;
constexpr u16 FactorySize = 2;
constexpr u16 UniqueId1 = 0x0083;       // 40 + chk (copy at 0x0183)
constexpr u16 UniqueId2 = 0x0183;
constexpr u16 UniqueIdSize = 40;
constexpr u16 Lcd1 = 0x00AC;            // 64 + chk (copy at 0x01AC)
constexpr u16 Lcd2 = 0x01AC;
constexpr u16 LcdSize = 64;
constexpr u16 Identity1 = 0x00ED;       // 104 + chk (copy at 0x01ED)
constexpr u16 Identity2 = 0x01ED;
constexpr u16 IdentitySize = 0x68;
constexpr u16 Health1 = 0x0156;         // 24 + chk (copy at 0x0256)
constexpr u16 Health2 = 0x0256;
constexpr u16 HealthSize = 24;
constexpr u16 CopyMarker1 = 0x016F;     // 1 + chk (copy at 0x026F)
constexpr u16 CopyMarker2 = 0x026F;

constexpr u16 RouteInfo = 0x8F00;       // struct RouteInfo, 190 bytes
constexpr u16 RouteArea = 0x8F00;       // live route data, 0x2900 bytes
constexpr u16 RouteAreaSize = 0x2900;
constexpr u16 ReceivedBitfield = 0xB800;
constexpr u16 ReceivedBitfieldSize = 0x6C8;
constexpr u16 TeamData = 0xCC00;        // 0x280 bytes copied here at walk start
constexpr u16 TeamDataSize = 0x280;
constexpr u16 PerWalkArea = 0xCE80;     // cleared on full reset, 0xD4C bytes
constexpr u16 PerWalkAreaSize = 0xD4C;
constexpr u16 WattsMirror = 0xCE8A;     // u16 BE, refreshed when answering 0x20
constexpr u16 Caught = 0xCE8C;          // 3 x PokemonSummary (16)
constexpr u16 CaughtCount = 3;
constexpr u16 Dowsed = 0xCEBC;          // 3 x {u16 item, u16 unused}
constexpr u16 DowsedCount = 3;
constexpr u16 Gifted = 0xCEC8;          // 10 x {u16 item, u16 unused}
constexpr u16 GiftedCount = 10;
constexpr u16 WalkResultsSize = 0x64;   // Caught + Dowsed + Gifted
constexpr u16 History = 0xCEF0;         // 7 x u32 BE, [0] = yesterday
constexpr u16 HistoryDays = 7;
constexpr u16 EventLog = 0xCF0C;        // 24 x 136 bytes
constexpr u16 EventLogSize = 3264;
constexpr u16 TeamStaging = 0xD480;     // team data written here before walk start
constexpr u16 ScenarioStaging = 0xD700; // route data written here before walk start
constexpr u16 MetPeers = 0xDE24;
constexpr u16 MetPeersSize = 0x1568;
}

// ---- IdentityData (0x68 bytes) offsets ----------------------------------------
namespace id
{
constexpr u16 Unk0 = 0x00;              // u32 le
constexpr u16 Unk1 = 0x04;              // u32 le
constexpr u16 Unk2 = 0x08;              // u16 le
constexpr u16 Unk3 = 0x0A;              // u16 le
constexpr u16 Tid = 0x0C;               // u16 le
constexpr u16 Sid = 0x0E;               // u16 le
constexpr u16 UniqueData = 0x10;        // 40 bytes
constexpr u16 EventBitmap = 0x38;       // 16 bytes
constexpr u16 TrainerName = 0x48;       // 8 x u16 (game charset)
constexpr u16 Unk4 = 0x58;              // 2 bytes
constexpr u16 EventIndex = 0x5A;
constexpr u16 Flags = 0x5B;             // bit0 registered, bit1 has pokemon, bit2 pokemon joined
constexpr u16 ProtocolVer = 0x5C;
constexpr u16 Unk5 = 0x5D;
constexpr u16 ProtocolSubver = 0x5E;
constexpr u16 Unk8 = 0x5F;
constexpr u16 LastSync = 0x60;          // u32 be
constexpr u16 StepCount = 0x64;         // u32 be

constexpr u8 FlagRegistered = 1 << 0;
constexpr u8 FlagHasPokemon = 1 << 1;
constexpr u8 FlagPokemonJoined = 1 << 2;
}

// ---- HealthData (24 bytes, big-endian) offsets ---------------------------------
namespace hd
{
constexpr u16 TotalSteps = 0x00;        // u32
constexpr u16 TodaySteps = 0x04;        // u32
constexpr u16 LastSync = 0x08;          // u32, seconds since 2001-01-01 00:00
constexpr u16 TotalDays = 0x0C;         // u16
constexpr u16 Watts = 0x0E;             // u16
constexpr u16 WalkMinutes = 0x10;       // u16
constexpr u16 StepsThisWatt = 0x12;     // u8
constexpr u16 EventLogIndex = 0x13;     // u8
constexpr u16 Settings = 0x17;          // u8
}

// ---- RouteInfo offsets ---------------------------------------------------------
namespace route
{
constexpr u16 Species = 0x00;           // u16 le
constexpr u16 HeldItem = 0x02;
constexpr u16 Level = 0x0C;
constexpr u16 Nickname = 0x10;          // 11 x u16
constexpr u16 Happiness = 0x26;
constexpr u16 ImageIndex = 0x27;
constexpr u16 Name = 0x28;              // 21 x u16
constexpr u16 Pokemon = 0x52;           // 3 x PokemonSummary (16 bytes)
constexpr u16 PokemonSteps = 0x82;      // 3 x u16 le, steps needed today before it can appear
constexpr u16 PokemonPercent = 0x88;    // 3 x u8, chance once the steps are met
constexpr u16 Items = 0x8C;             // 10 x u16 le
constexpr u16 ItemSteps = 0xA0;         // 10 x u16 le
constexpr u16 ItemPercent = 0xB4;       // 10 x u8
constexpr u16 Size = 0xBE;
}

// ---- game rules ------------------------------------------------------------
constexpr u32 StepsPerWatt = 20;
constexpr u32 MaxWatts = 9999;
constexpr u32 MaxTodaySteps = 99999;
constexpr u32 MaxTotalSteps = 9999999;

}

#endif
