/*
    Virtual Pokéwalker for melonDS Android - EEPROM image store.
    GPL-3.0-or-later, see PwCommon.h.
*/

#include "EepromStore.h"

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace pokewalker
{

EepromStore::EepromStore() = default;

EepromStore::~EepromStore()
{
    Close();
}

static bool WriteFully(int fd, const u8* data, size_t len, off_t offset)
{
    while (len > 0)
    {
        ssize_t n = pwrite(fd, data, len, offset);
        if (n < 0)
        {
            if (errno == EINTR) continue;
            return false;
        }
        data += n;
        len -= (size_t)n;
        offset += n;
    }
    return true;
}

bool EepromStore::Open(const std::string& newPath, bool* created, std::string* error)
{
    Close();
    if (created) *created = false;
    mem.fill(0);
    path = newPath;

    int f = open(path.c_str(), O_RDWR | O_CREAT, 0600);
    if (f < 0)
    {
        if (error) *error = "cannot open " + path + ": " + std::strerror(errno);
        return false;
    }

    struct stat st {};
    fstat(f, &st);
    if (st.st_size == 0)
    {
        if (created) *created = true;
    }
    else
    {
        size_t want = st.st_size < (off_t)ee::Size ? (size_t)st.st_size : ee::Size;
        size_t got = 0;
        while (got < want)
        {
            ssize_t n = pread(f, mem.data() + got, want - got, (off_t)got);
            if (n < 0 && errno == EINTR) continue;
            if (n <= 0) break;
            got += (size_t)n;
        }
        if (got != want)
        {
            close(f);
            if (error) *error = "cannot read " + path;
            return false;
        }
        if (want < ee::Size && created) *created = true; // truncated image: treat as new
    }

    fd = f;
    return true;
}

void EepromStore::Close()
{
    if (fd >= 0)
    {
        fsync(fd);
        close(fd);
        fd = -1;
    }
}

void EepromStore::Read(u32 addr, u8* dst, size_t len) const
{
    for (size_t i = 0; i < len; i++)
    {
        u32 a = addr + (u32)i;
        dst[i] = a < ee::Size ? mem[a] : 0;
    }
}

void EepromStore::Write(u32 addr, const u8* src, size_t len)
{
    if (addr >= ee::Size) return;
    if (addr + len > ee::Size) len = ee::Size - addr;
    std::memcpy(&mem[addr], src, len);
    Persist(addr, len);
}

void EepromStore::Fill(u32 addr, u8 value, size_t len)
{
    if (addr >= ee::Size) return;
    if (addr + len > ee::Size) len = ee::Size - addr;
    std::memset(&mem[addr], value, len);
    Persist(addr, len);
}

void EepromStore::Copy(u32 dst, u32 src, size_t len)
{
    if (dst >= ee::Size || src >= ee::Size) return;
    if (dst + len > ee::Size) len = ee::Size - dst;
    if (src + len > ee::Size) len = ee::Size - src;
    std::memmove(&mem[dst], &mem[src], len);
    Persist(dst, len);
}

u8 EepromStore::ReliableChecksum(const u8* data, size_t len)
{
    u8 sum = 1;
    for (size_t i = 0; i < len; i++) sum += data[i];
    return sum;
}

bool EepromStore::ReadReliable(u32 addr1, u32 addr2, u8* dst, size_t len) const
{
    for (u32 addr : {addr1, addr2})
    {
        Read(addr, dst, len);
        u8 stored = 0;
        Read(addr + (u32)len, &stored, 1);
        if (ReliableChecksum(dst, len) == stored)
            return true;
    }
    Read(addr1, dst, len); // both damaged: use the first copy anyway
    return false;
}

void EepromStore::WriteReliable(u32 addr1, u32 addr2, const u8* src, size_t len)
{
    u8 chk = ReliableChecksum(src, len);
    for (u32 addr : {addr1, addr2})
    {
        Write(addr, src, len);
        Write(addr + (u32)len, &chk, 1);
    }
}

bool EepromStore::ReplaceAll(const u8* image, size_t len, std::string* error)
{
    if (len != ee::Size)
    {
        if (error) *error = "a Pokéwalker EEPROM image must be exactly 65536 bytes";
        return false;
    }
    std::string tmp = path + ".tmp";
    int f = open(tmp.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (f < 0 || !WriteFully(f, image, len, 0) || fsync(f) != 0)
    {
        if (f >= 0) close(f);
        unlink(tmp.c_str());
        if (error) *error = "cannot write " + tmp + ": " + std::strerror(errno);
        return false;
    }
    close(f);
    if (rename(tmp.c_str(), path.c_str()) != 0)
    {
        unlink(tmp.c_str());
        if (error) *error = "cannot replace " + path + ": " + std::strerror(errno);
        return false;
    }
    std::memcpy(mem.data(), image, len);
    if (fd >= 0) close(fd);
    fd = open(path.c_str(), O_RDWR, 0600);
    return fd >= 0;
}

bool EepromStore::SaveAll(std::string* error)
{
    std::array<u8, ee::Size> copy = mem;
    return ReplaceAll(copy.data(), copy.size(), error);
}

void EepromStore::Sync()
{
    if (fd >= 0) fsync(fd);
}

void EepromStore::Persist(u32 addr, size_t len)
{
    if (fd < 0 || len == 0) return;
    WriteFully(fd, &mem[addr], len, (off_t)addr);
}

}
