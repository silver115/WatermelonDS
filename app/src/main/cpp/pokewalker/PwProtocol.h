/*
    Virtual Pokéwalker for melonDS Android - IR packet helpers.
    GPL-3.0-or-later, see PwCommon.h.
*/

#ifndef POKEWALKER_PWPROTOCOL_H
#define POKEWALKER_PWPROTOCOL_H

#include <vector>
#include "PwCommon.h"

namespace pokewalker
{

// Every byte on air is XORed with this.
constexpr u8 AirXor = 0xAA;
constexpr size_t HeaderSize = 8;
constexpr size_t MaxPayload = 128;

namespace cmd
{
constexpr u8 EepromWriteCmp00 = 0x00;
constexpr u8 EepromWriteRaw00 = 0x02;
constexpr u8 EepromWriteAck = 0x04;
constexpr u8 RamWrite = 0x06;
constexpr u8 EepromWriteRandom = 0x0A;
constexpr u8 EepromReadReq = 0x0C;
constexpr u8 EepromReadRsp = 0x0E;
constexpr u8 IdentityReq = 0x20;
constexpr u8 IdentityRsp = 0x22;
constexpr u8 Ping = 0x24;
constexpr u8 Pong = 0x26;
constexpr u8 WalkerReset1 = 0x2A;       // clear events
constexpr u8 WalkerReset0 = 0x2C;       // keep events and lifetime steps
constexpr u8 IdentitySend = 0x32;
constexpr u8 IdentityAck = 0x34;
constexpr u8 NoComplete = 0x36;
constexpr u8 WalkStartInit = 0x38;      // walk start right after pairing
constexpr u8 IdentitySendAlias1 = 0x40;
constexpr u8 IdentityAckAlias1 = 0x42;
constexpr u8 NoCompleteAlias3 = 0x44;
constexpr u8 WalkEndReq = 0x4E;
constexpr u8 WalkEndAck = 0x50;
constexpr u8 IdentitySendAlias2 = 0x52;
constexpr u8 IdentityAckAlias2 = 0x54;
constexpr u8 NoCompleteAlias1 = 0x56;
constexpr u8 WalkStart = 0x5A;
constexpr u8 IdentitySendAlias3 = 0x60;
constexpr u8 IdentityAckAlias3 = 0x62;
constexpr u8 NoCompleteAlias2 = 0x64;
constexpr u8 ConnectComplete = 0x66;
constexpr u8 ConnectCompleteAck = 0x68;
constexpr u8 EepromWriteCmp80 = 0x80;
constexpr u8 EepromWriteRaw80 = 0x82;
constexpr u8 NoRx = 0x9C;
constexpr u8 NoRxAck = 0x9E;
constexpr u8 WalkerReset3 = 0xE0;       // clear events and lifetime steps
constexpr u8 Disconnect = 0xF4;
constexpr u8 SlaveAck = 0xF8;
constexpr u8 AssertMaster = 0xFA;
constexpr u8 Advertising = 0xFC;
}

constexpr u8 ExtraFromMaster = 0x01;
constexpr u8 ExtraFromWalker = 0x02;

/// Checksum over a whole un-XORed packet. Header bytes 2 and 3 (the checksum
/// field itself) are treated as zero. Seed 0x0002; even offsets are added
/// shifted left by 8, odd offsets as-is; carries are folded back in.
u16 PacketChecksum(const u8* packet, size_t len);

/// Builds an un-XORed packet with the checksum filled in.
std::vector<u8> BuildPacket(u8 command, u8 extra, const u8 session[4], const u8* payload, size_t payloadLen);

/// XOR a buffer with AirXor in place.
void AirScramble(u8* data, size_t len);

/// Decompresses Nintendo LZ77 (type 0x10) data as used by EEPROM write commands
/// 0x00/0x80. Returns the decompressed length, or -1 if the data is malformed.
int Lz10Decompress(const u8* src, size_t srcLen, u8* dst, size_t dstCap);

/// Compressor used by the tests (and handy for tools). Produces valid LZ10.
std::vector<u8> Lz10Compress(const u8* src, size_t len);

}

#endif
