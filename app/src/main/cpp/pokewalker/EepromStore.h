/*
    Virtual Pokéwalker for melonDS Android - 64 KiB EEPROM image kept in memory
    and written through to a file, range by range, so a crash mid-transfer
    never loses more than the packet in flight.
    GPL-3.0-or-later, see PwCommon.h.
*/

#ifndef POKEWALKER_EEPROMSTORE_H
#define POKEWALKER_EEPROMSTORE_H

#include <array>
#include <string>
#include <vector>
#include "PwCommon.h"

namespace pokewalker
{

class EepromStore
{
public:
    EepromStore();
    ~EepromStore();
    EepromStore(const EepromStore&) = delete;
    EepromStore& operator=(const EepromStore&) = delete;

    /// Loads the image at path. A missing file is not an error: created is set
    /// and the caller is expected to initialise and Save() a fresh image.
    bool Open(const std::string& path, bool* created, std::string* error);
    void Close();
    bool IsOpen() const { return fd >= 0; }
    const std::string& Path() const { return path; }

    const u8* Data() const { return mem.data(); }

    void Read(u32 addr, u8* dst, size_t len) const;
    void Write(u32 addr, const u8* src, size_t len);
    void Fill(u32 addr, u8 value, size_t len);
    void Copy(u32 dst, u32 src, size_t len);

    /// "Reliable data" blocks: data followed by a checksum byte (1 + sum),
    /// stored twice. Read prefers the first copy and falls back to the second.
    bool ReadReliable(u32 addr1, u32 addr2, u8* dst, size_t len) const;
    void WriteReliable(u32 addr1, u32 addr2, const u8* src, size_t len);
    static u8 ReliableChecksum(const u8* data, size_t len);

    /// Replace the whole image (import or reset) and write it atomically.
    bool ReplaceAll(const u8* image, size_t len, std::string* error);
    /// Writes the whole image to disk (used after creating a fresh image).
    bool SaveAll(std::string* error);
    void Sync();

private:
    std::array<u8, ee::Size> mem {};
    std::string path;
    int fd = -1;

    void Persist(u32 addr, size_t len);
};

}

#endif
