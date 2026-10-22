/*
    Virtual Pokéwalker for melonDS Android - IR packet helpers.
    GPL-3.0-or-later, see PwCommon.h.
*/

#include "PwProtocol.h"

#include <cstring>

namespace pokewalker
{

u16 PacketChecksum(const u8* packet, size_t len)
{
    u32 sum = 0x0002;
    for (size_t i = 0; i < len; i++)
    {
        u32 v = (i == 2 || i == 3) ? 0 : packet[i];
        sum += (i & 1) ? v : (v << 8);
    }
    while (sum >> 16)
        sum = (sum & 0xFFFF) + (sum >> 16);
    return (u16)sum;
}

std::vector<u8> BuildPacket(u8 command, u8 extra, const u8 session[4], const u8* payload, size_t payloadLen)
{
    std::vector<u8> out(HeaderSize + payloadLen);
    out[0] = command;
    out[1] = extra;
    std::memcpy(&out[4], session, 4);
    if (payloadLen)
        std::memcpy(&out[HeaderSize], payload, payloadLen);
    u16 chk = PacketChecksum(out.data(), out.size());
    out[2] = (u8)(chk & 0xFF);
    out[3] = (u8)(chk >> 8);
    return out;
}

void AirScramble(u8* data, size_t len)
{
    for (size_t i = 0; i < len; i++)
        data[i] ^= AirXor;
}

int Lz10Decompress(const u8* src, size_t srcLen, u8* dst, size_t dstCap)
{
    if (!src || !dst || srcLen < 4 || src[0] != 0x10)
        return -1;

    size_t outLen = (size_t)src[1] | ((size_t)src[2] << 8) | ((size_t)src[3] << 16);
    if (outLen > dstCap)
        return -1;

    size_t in = 4, out = 0;
    while (out < outLen)
    {
        if (in >= srcLen)
            return -1;
        u8 flags = src[in++];
        for (int bit = 7; bit >= 0 && out < outLen; bit--)
        {
            if (flags & (1 << bit))
            {
                if (in + 1 >= srcLen)
                    return -1;
                size_t count = (size_t)(src[in] >> 4) + 3;
                size_t disp = ((size_t)(src[in] & 0x0F) << 8 | src[in + 1]) + 1;
                in += 2;
                if (disp > out)
                    return -1;
                for (size_t k = 0; k < count && out < outLen; k++, out++)
                    dst[out] = dst[out - disp];
            }
            else
            {
                if (in >= srcLen)
                    return -1;
                dst[out++] = src[in++];
            }
        }
    }
    return (int)outLen;
}

std::vector<u8> Lz10Compress(const u8* src, size_t len)
{
    std::vector<u8> out = {0x10, (u8)len, (u8)(len >> 8), (u8)(len >> 16)};
    size_t pos = 0;
    while (pos < len)
    {
        size_t flagIndex = out.size();
        out.push_back(0);
        for (int bit = 7; bit >= 0 && pos < len; bit--)
        {
            size_t bestLen = 0, bestDisp = 0;
            size_t maxDisp = pos < 0x1000 ? pos : 0x1000;
            for (size_t disp = 1; disp <= maxDisp; disp++)
            {
                size_t n = 0;
                while (n < 18 && pos + n < len && src[pos + n] == src[pos + n - disp])
                    n++;
                if (n > bestLen)
                {
                    bestLen = n;
                    bestDisp = disp;
                }
            }
            if (bestLen >= 3)
            {
                out[flagIndex] |= (u8)(1 << bit);
                size_t d = bestDisp - 1;
                out.push_back((u8)(((bestLen - 3) << 4) | (d >> 8)));
                out.push_back((u8)(d & 0xFF));
                pos += bestLen;
            }
            else
            {
                out.push_back(src[pos++]);
            }
        }
    }
    return out;
}

}
