/*
    Virtual Pokéwalker for melonDS Android.
    GPL-3.0-or-later, see PwCommon.h.
*/

#include "VirtualPokewalker.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <fstream>
#include <memory>
#include <random>
#include <sstream>

#include "PwProtocol.h"
#include "PwText.h"

namespace pokewalker
{

// Seconds between the Unix epoch and the Pokéwalker epoch (2001-01-01 00:00).
static constexpr s64 kWalkerEpoch = 978307200;
// Default "last sync" written by a factory reset (2008-01-02, as the real device does).
static constexpr u32 kFactoryLastSync = 220924800;

// How many empty polls must pass before advertising: the game's first poll of
// a connection must come back empty or it aborts the session.
static constexpr int kQuietPollsBeforeAdvert = 10;
static constexpr s64 kAdvertIntervalMs = 100;
static constexpr s64 kSessionTimeoutMs = 3000;
static constexpr s64 kPollGapResetMs = 1000;
static constexpr s64 kCooldownGapMs = 1500;
static constexpr s64 kCooldownMaxMs = 6000;

static s64 DefaultMonotonicMs()
{
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

static s64 DefaultUnixSeconds()
{
    return (s64)std::time(nullptr);
}

static s64 DefaultLocalDay(s64 unixSeconds)
{
    time_t t = (time_t)unixSeconds;
    struct tm lt {};
    localtime_r(&t, &lt);
    s64 local = unixSeconds + (s64)lt.tm_gmtoff;
    return local >= 0 ? local / 86400 : -((-local + 86399) / 86400);
}

VirtualPokewalker::VirtualPokewalker()
{
    nowMs = DefaultMonotonicMs;
    nowUnix = DefaultUnixSeconds;
    localDay = DefaultLocalDay;
    auto engine = std::make_shared<std::mt19937>(std::random_device{}());
    rng = [engine]() { return (u32)(*engine)(); };
}

VirtualPokewalker::~VirtualPokewalker()
{
    Close();
}

void VirtualPokewalker::SetClocks(MonotonicMs mono, UnixSeconds wall, LocalDayOf day)
{
    if (mono) nowMs = std::move(mono);
    if (wall) nowUnix = std::move(wall);
    if (day) localDay = std::move(day);
}

void VirtualPokewalker::Log(const std::string& msg) const
{
    if (log) log(msg);
}

// ---------------------------------------------------------------------------
// Opening / images
// ---------------------------------------------------------------------------

bool VirtualPokewalker::Open(const std::string& eepromPath, std::string* error)
{
    Close();
    bool created = false;
    if (!store.Open(eepromPath, &created, error))
        return false;

    metaPath = eepromPath + ".meta";
    if (created)
    {
        std::vector<u8> fresh = BuildFactoryImage();
        if (!store.ReplaceAll(fresh.data(), fresh.size(), error))
            return false;
        meta = Meta {};
        meta.dayNumber = localDay(nowUnix());
        SaveMeta();
        Log("Created a new virtual Pokewalker at " + eepromPath);
    }
    else
    {
        LoadMeta();
    }
    OnCartReset();
    return true;
}

void VirtualPokewalker::Close()
{
    store.Close();
    air = AirState::Idle;
    pending.clear();
}

std::vector<u8> VirtualPokewalker::BuildFactoryImage()
{
    std::vector<u8> img(ee::Size, 0x00);
    std::memcpy(&img[ee::Nintendo], "nintendo", 8);

    auto reliable = [&img](u32 a1, u32 a2, const u8* data, size_t len) {
        u8 chk = EepromStore::ReliableChecksum(data, len);
        for (u32 a : {a1, a2})
        {
            std::memcpy(&img[a], data, len);
            img[a + len] = chk;
        }
    };

    u8 factory[ee::FactorySize] = {0, 0};
    reliable(ee::Factory1, ee::Factory2, factory, sizeof(factory));

    u8 unique[ee::UniqueIdSize];
    for (size_t i = 0; i < sizeof(unique); i += 4)
    {
        u32 r = rng();
        for (size_t k = 0; k < 4 && i + k < sizeof(unique); k++)
            unique[i + k] = (u8)(r >> (8 * k));
    }
    reliable(ee::UniqueId1, ee::UniqueId2, unique, sizeof(unique));

    u8 lcd[ee::LcdSize] = {};
    reliable(ee::Lcd1, ee::Lcd2, lcd, sizeof(lcd));

    Identity ident {};
    std::memcpy(&ident[id::UniqueData], unique, sizeof(unique));
    ident[id::Unk8] = 0x02; // value seen on real walkers
    reliable(ee::Identity1, ee::Identity2, ident.data(), ident.size());

    Health health {};
    wr32be(&health[hd::LastSync], kFactoryLastSync);
    health[hd::Settings] = 0x24; // default volume and contrast
    reliable(ee::Health1, ee::Health2, health.data(), health.size());

    u8 marker = 0;
    reliable(ee::CopyMarker1, ee::CopyMarker2, &marker, 1);
    return img;
}

bool VirtualPokewalker::ResetToFactory(std::string* error)
{
    if (!IsOpen()) { if (error) *error = "the virtual Pokewalker is not open"; return false; }
    if (SessionActive()) { if (error) *error = "the game is connected to the Pokewalker right now"; return false; }
    std::vector<u8> fresh = BuildFactoryImage();
    if (!store.ReplaceAll(fresh.data(), fresh.size(), error))
        return false;
    meta = Meta {};
    meta.dayNumber = localDay(nowUnix());
    SaveMeta();
    Log("Virtual Pokewalker reset to factory state");
    return true;
}

bool VirtualPokewalker::ImportImage(const std::vector<u8>& image, std::string* error)
{
    if (!IsOpen()) { if (error) *error = "the virtual Pokewalker is not open"; return false; }
    if (SessionActive()) { if (error) *error = "the game is connected to the Pokewalker right now"; return false; }
    if (image.size() != ee::Size)
    {
        if (error) *error = "a Pokewalker EEPROM dump must be exactly 65536 bytes";
        return false;
    }
    if (std::memcmp(image.data(), "nintendo", 8) != 0)
    {
        if (error) *error = "this file is not a Pokewalker EEPROM dump (no \"nintendo\" header)";
        return false;
    }
    if (!store.ReplaceAll(image.data(), image.size(), error))
        return false;
    meta = Meta {};
    meta.dayNumber = localDay(nowUnix());
    Identity ident = ReadIdentity();
    if (ident[id::Flags] & id::FlagHasPokemon)
        meta.strollStartUnix = nowUnix(); // steps already on the dump stay; new phone steps count from now
    SaveMeta();
    Log("Imported a Pokewalker EEPROM image");
    return true;
}

std::vector<u8> VirtualPokewalker::ExportImage() const
{
    return std::vector<u8>(store.Data(), store.Data() + ee::Size);
}

// ---------------------------------------------------------------------------
// Structures
// ---------------------------------------------------------------------------

VirtualPokewalker::Health VirtualPokewalker::ReadHealth() const
{
    Health h {};
    store.ReadReliable(ee::Health1, ee::Health2, h.data(), h.size());
    return h;
}

void VirtualPokewalker::WriteHealth(const Health& h)
{
    store.WriteReliable(ee::Health1, ee::Health2, h.data(), h.size());
}

VirtualPokewalker::Identity VirtualPokewalker::ReadIdentity() const
{
    Identity i {};
    store.ReadReliable(ee::Identity1, ee::Identity2, i.data(), i.size());
    return i;
}

void VirtualPokewalker::WriteIdentity(const Identity& i)
{
    store.WriteReliable(ee::Identity1, ee::Identity2, i.data(), i.size());
}

void VirtualPokewalker::WriteWattsMirror(u16 watts)
{
    u8 be[2];
    wr16be(be, watts);
    store.Write(ee::WattsMirror, be, 2);
}

void VirtualPokewalker::LoadMeta()
{
    meta = Meta {};
    std::ifstream in(metaPath);
    std::string line;
    while (std::getline(in, line))
    {
        auto eq = line.find('=');
        if (eq == std::string::npos) continue;
        std::string key = line.substr(0, eq);
        s64 value = std::strtoll(line.c_str() + eq + 1, nullptr, 10);
        if (key == "day") meta.dayNumber = value;
        else if (key == "stroll") meta.strollStartUnix = value;
    }
    if (meta.dayNumber < 0)
    {
        meta.dayNumber = localDay(nowUnix());
        Identity ident = ReadIdentity();
        if ((ident[id::Flags] & id::FlagHasPokemon) && meta.strollStartUnix == 0)
            meta.strollStartUnix = nowUnix();
        SaveMeta();
    }
}

void VirtualPokewalker::SaveMeta()
{
    std::string tmp = metaPath + ".tmp";
    {
        std::ofstream out(tmp, std::ios::trunc);
        out << "day=" << meta.dayNumber << "\n";
        out << "stroll=" << meta.strollStartUnix << "\n";
    }
    std::rename(tmp.c_str(), metaPath.c_str());
}

void VirtualPokewalker::RollOverDay()
{
    s64 today = localDay(nowUnix());
    if (meta.dayNumber < 0 || today < meta.dayNumber)
    {
        meta.dayNumber = today; // first run, or the clock went backwards
        SaveMeta();
        return;
    }
    if (today == meta.dayNumber)
        return;

    s64 passed = today - meta.dayNumber;
    Health h = ReadHealth();
    u32 oldToday = rd32be(&h[hd::TodaySteps]);

    u8 raw[ee::HistoryDays * 4];
    store.Read(ee::History, raw, sizeof(raw));
    u32 oldHist[ee::HistoryDays], newHist[ee::HistoryDays];
    for (int k = 0; k < ee::HistoryDays; k++) oldHist[k] = rd32be(&raw[k * 4]);
    for (int k = 0; k < ee::HistoryDays; k++)
    {
        if (k == passed - 1) newHist[k] = oldToday;
        else if (k >= passed) newHist[k] = oldHist[k - passed];
        else newHist[k] = 0;
    }
    for (int k = 0; k < ee::HistoryDays; k++) wr32be(&raw[k * 4], newHist[k]);
    store.Write(ee::History, raw, sizeof(raw));

    wr32be(&h[hd::TodaySteps], 0);
    u32 days = rd16be(&h[hd::TotalDays]) + (u32)std::min<s64>(passed, 0xFFFF);
    wr16be(&h[hd::TotalDays], (u16)std::min<u32>(days, 0xFFFF));
    WriteHealth(h);

    meta.dayNumber = today;
    SaveMeta();
    store.Sync();
}

// ---------------------------------------------------------------------------
// Model operations
// ---------------------------------------------------------------------------

bool VirtualPokewalker::AddSteps(u32 steps, int daysAgo, std::string* error)
{
    if (!IsOpen()) { if (error) *error = "the virtual Pokewalker is not open"; return false; }
    if (SessionActive()) { if (error) *error = "the game is connected to the Pokewalker right now, try again in a moment"; return false; }
    if (daysAgo < 0) { if (error) *error = "steps cannot be added in the future"; return false; }
    if (steps == 0) return true;

    RollOverDay();
    Health h = ReadHealth();

    u32 total = rd32be(&h[hd::TotalSteps]);
    total = (u32)std::min<u64>((u64)total + steps, MaxTotalSteps);
    wr32be(&h[hd::TotalSteps], total);

    if (daysAgo == 0)
    {
        u32 today = rd32be(&h[hd::TodaySteps]);
        wr32be(&h[hd::TodaySteps], (u32)std::min<u64>((u64)today + steps, MaxTodaySteps));
    }
    else if (daysAgo <= ee::HistoryDays)
    {
        u8 raw[4];
        u32 addr = ee::History + (u32)(daysAgo - 1) * 4;
        store.Read(addr, raw, 4);
        wr32be(raw, (u32)std::min<u64>((u64)rd32be(raw) + steps, MaxTodaySteps));
        store.Write(addr, raw, 4);
    }

    u32 partial = h[hd::StepsThisWatt] < StepsPerWatt ? h[hd::StepsThisWatt] : 0;
    u64 pool = (u64)partial + steps;
    u32 watts = rd16be(&h[hd::Watts]);
    watts = (u32)std::min<u64>((u64)watts + pool / StepsPerWatt, MaxWatts);
    wr16be(&h[hd::Watts], (u16)watts);
    h[hd::StepsThisWatt] = (u8)(pool % StepsPerWatt);
    WriteHealth(h);
    WriteWattsMirror((u16)watts);

    Identity ident = ReadIdentity();
    wr32be(&ident[id::StepCount], total);
    WriteIdentity(ident);
    store.Sync();

    char msg[96];
    std::snprintf(msg, sizeof(msg), "Added %u steps (%d days ago): total %u, watts %u", steps, daysAgo, total, watts);
    Log(msg);
    return true;
}

std::string VirtualPokewalker::StatusJson()
{
    if (!IsOpen())
        return "{\"open\":false}";
    if (!SessionActive())
        RollOverDay();

    Health h = ReadHealth();
    Identity ident = ReadIdentity();
    u8 routeInfo[0xBE];
    store.Read(ee::RouteInfo, routeInfo, sizeof(routeInfo));
    u8 hist[ee::HistoryDays * 4];
    store.Read(ee::History, hist, sizeof(hist));

    bool registered = ident[id::Flags] & id::FlagRegistered;
    bool hasPokemon = ident[id::Flags] & id::FlagHasPokemon;

    int caught = 0, dowsed = 0, gifted = 0;
    for (int i = 0; i < ee::CaughtCount; i++)
    {
        u8 s[2];
        store.Read(ee::Caught + i * 16, s, 2);
        if (rd16le(s) != 0) caught++;
    }
    for (int i = 0; i < ee::DowsedCount; i++)
    {
        u8 s[2];
        store.Read(ee::Dowsed + i * 4, s, 2);
        if (rd16le(s) != 0) dowsed++;
    }
    for (int i = 0; i < ee::GiftedCount; i++)
    {
        u8 s[2];
        store.Read(ee::Gifted + i * 4, s, 2);
        if (rd16le(s) != 0) gifted++;
    }

    u32 lastSync = rd32be(&h[hd::LastSync]);
    u16 species = rd16le(&routeInfo[route::Species]);

    std::ostringstream js;
    js << "{\"open\":true";
    js << ",\"registered\":" << (registered ? "true" : "false");
    js << ",\"hasPokemon\":" << (hasPokemon ? "true" : "false");
    js << ",\"sessionActive\":" << (SessionActive() ? "true" : "false");
    js << ",\"trainer\":\"" << JsonEscape(DecodeGameText(&ident[id::TrainerName], 8)) << "\"";
    js << ",\"tid\":" << rd16le(&ident[id::Tid]);
    js << ",\"todaySteps\":" << rd32be(&h[hd::TodaySteps]);
    js << ",\"totalSteps\":" << rd32be(&h[hd::TotalSteps]);
    js << ",\"watts\":" << rd16be(&h[hd::Watts]);
    js << ",\"totalDays\":" << rd16be(&h[hd::TotalDays]);
    js << ",\"lastSyncUnix\":" << (lastSync ? (s64)lastSync + kWalkerEpoch : 0);
    js << ",\"strollStartUnix\":" << (hasPokemon ? meta.strollStartUnix : 0);
    js << ",\"history\":[";
    for (int k = 0; k < ee::HistoryDays; k++)
        js << (k ? "," : "") << rd32be(&hist[k * 4]);
    js << "]";
    if (hasPokemon)
    {
        js << ",\"pokemon\":{\"species\":" << species;
        js << ",\"speciesName\":\"" << JsonEscape(SpeciesName(species)) << "\"";
        js << ",\"nickname\":\"" << JsonEscape(DecodeGameText(&routeInfo[route::Nickname], 11)) << "\"";
        js << ",\"level\":" << (int)routeInfo[route::Level];
        js << ",\"route\":\"" << JsonEscape(DecodeGameText(&routeInfo[route::Name], 21)) << "\"";
        js << ",\"routeImage\":" << (int)routeInfo[route::ImageIndex];
        js << "}";
    }
    js << ",\"caught\":" << caught << ",\"dowsed\":" << dowsed << ",\"gifted\":" << gifted;
    js << "}";
    return js.str();
}

// ---------------------------------------------------------------------------
// Poké Radar and dowsing
// ---------------------------------------------------------------------------

bool VirtualPokewalker::CheckPlayable(u32 cost, std::string* error)
{
    if (!IsOpen()) { if (error) *error = "the virtual Pokewalker is not open"; return false; }
    if (SessionActive()) { if (error) *error = "the game is connected to the Pokewalker right now"; return false; }
    Identity ident = ReadIdentity();
    if (!(ident[id::Flags] & id::FlagHasPokemon)) { if (error) *error = "no Pokemon is on a stroll"; return false; }
    Health h = ReadHealth();
    if (rd16be(&h[hd::Watts]) < cost)
    {
        if (error) *error = "not enough watts (" + std::to_string(cost) + " W needed)";
        return false;
    }
    return true;
}

void VirtualPokewalker::SpendWatts(u32 cost)
{
    Health h = ReadHealth();
    u32 watts = rd16be(&h[hd::Watts]);
    watts = watts >= cost ? watts - cost : 0;
    wr16be(&h[hd::Watts], (u16)watts);
    WriteHealth(h);
    WriteWattsMirror((u16)watts);
    store.Sync();
}

int VirtualPokewalker::FreeSlot(u32 base, int count, int stride) const
{
    for (int i = 0; i < count; i++)
    {
        u8 raw[2];
        store.Read(base + (u32)(i * stride), raw, 2);
        u16 v = rd16le(raw);
        if (v == 0 || v == 0xFFFF) return i;
    }
    return -1;
}

bool VirtualPokewalker::RadarEncounter(int* slot, std::string* json, std::string* error)
{
    if (IsOpen() && !SessionActive()) RollOverDay();
    if (!CheckPlayable(RadarCost, error)) return false;
    if (FreeSlot(ee::Caught, ee::CaughtCount, 16) < 0)
    {
        if (error) *error = "you already caught 3 Pokemon on this stroll, bring them home first";
        return false;
    }

    u8 ri[route::Size];
    store.Read(ee::RouteInfo, ri, sizeof(ri));
    u32 today = rd32be(&ReadHealth()[hd::TodaySteps]);
    u32 roll = rng() % 100;

    int chosen = -1, fallback = -1;
    for (int i = 0; i < 3; i++)
    {
        u16 species = rd16le(&ri[route::Pokemon + i * 16]);
        if (species == 0 || species == 0xFFFF) continue;
        fallback = i;
        u16 steps = rd16le(&ri[route::PokemonSteps + i * 2]);
        u8 percent = ri[route::PokemonPercent + i];
        if (chosen < 0 && today >= steps && roll <= percent) chosen = i;
    }
    if (chosen < 0) chosen = fallback;
    if (chosen < 0)
    {
        if (error) *error = "this route has no Pokemon";
        return false;
    }

    SpendWatts(RadarCost);
    const u8* mon = &ri[route::Pokemon + chosen * 16];
    u16 species = rd16le(mon);
    *slot = chosen;
    std::ostringstream js;
    js << "{\"slot\":" << chosen << ",\"species\":" << species
       << ",\"speciesName\":\"" << JsonEscape(SpeciesName(species)) << "\""
       << ",\"level\":" << (int)mon[route::Level]
       << ",\"watts\":" << rd16be(&ReadHealth()[hd::Watts]) << "}";
    *json = js.str();
    Log(std::string("Poke Radar found ") + SpeciesName(species));
    return true;
}

bool VirtualPokewalker::StoreCaught(int slot, std::string* error)
{
    if (!CheckPlayable(0, error)) return false;
    if (slot < 0 || slot > 2) { if (error) *error = "invalid route slot"; return false; }
    int free = FreeSlot(ee::Caught, ee::CaughtCount, 16);
    if (free < 0) { if (error) *error = "you already caught 3 Pokemon on this stroll"; return false; }
    u8 mon[16];
    store.Read(ee::RouteInfo + route::Pokemon + (u32)slot * 16, mon, sizeof(mon));
    store.Write(ee::Caught + (u32)free * 16, mon, sizeof(mon));
    store.Sync();
    Log(std::string("Caught ") + SpeciesName(rd16le(mon)));
    return true;
}

bool VirtualPokewalker::DowsingStart(u16* item, std::string* json, std::string* error)
{
    if (IsOpen() && !SessionActive()) RollOverDay();
    if (!CheckPlayable(DowsingCost, error)) return false;
    if (FreeSlot(ee::Dowsed, ee::DowsedCount, 4) < 0)
    {
        if (error) *error = "you already found 3 items on this stroll, bring them home first";
        return false;
    }

    u8 ri[route::Size];
    store.Read(ee::RouteInfo, ri, sizeof(ri));
    u32 today = rd32be(&ReadHealth()[hd::TodaySteps]);
    u32 roll = rng() % 100;

    int chosen = -1, fallback = -1;
    for (int i = 0; i < 10; i++)
    {
        u16 id = rd16le(&ri[route::Items + i * 2]);
        if (id == 0 || id == 0xFFFF) continue;
        fallback = i;
        u16 steps = rd16le(&ri[route::ItemSteps + i * 2]);
        u8 percent = ri[route::ItemPercent + i];
        if (chosen < 0 && today >= steps && roll < percent) chosen = i;
    }
    if (chosen < 0) chosen = fallback;
    if (chosen < 0)
    {
        if (error) *error = "this route has no items";
        return false;
    }

    SpendWatts(DowsingCost);
    *item = rd16le(&ri[route::Items + chosen * 2]);
    std::ostringstream js;
    js << "{\"item\":" << *item << ",\"itemName\":\"" << JsonEscape(ItemName(*item)) << "\""
       << ",\"watts\":" << rd16be(&ReadHealth()[hd::Watts]) << "}";
    *json = js.str();
    return true;
}

bool VirtualPokewalker::StoreDowsed(u16 item, std::string* error)
{
    if (!CheckPlayable(0, error)) return false;
    u8 ri[route::Size];
    store.Read(ee::RouteInfo, ri, sizeof(ri));
    bool onRoute = false;
    for (int i = 0; i < 10; i++)
        if (item != 0 && rd16le(&ri[route::Items + i * 2]) == item) onRoute = true;
    if (!onRoute) { if (error) *error = "that item is not on this route"; return false; }
    int free = FreeSlot(ee::Dowsed, ee::DowsedCount, 4);
    if (free < 0) { if (error) *error = "you already found 3 items on this stroll"; return false; }
    u8 entry[4] = {0, 0, 0, 0};
    wr16le(entry, item);
    store.Write(ee::Dowsed + (u32)free * 4, entry, sizeof(entry));
    store.Sync();
    Log(std::string("Found ") + ItemName(item));
    return true;
}

// ---------------------------------------------------------------------------
// Air interface
// ---------------------------------------------------------------------------

bool VirtualPokewalker::SessionActive() const
{
    return air == AirState::Connected || !pending.empty();
}

void VirtualPokewalker::OnCartReset()
{
    if (air == AirState::Connected)
        Log("Cart reset during a Pokewalker session; session dropped");
    air = AirState::Idle;
    pending.clear();
    endAfterReply = false;
    quietPolls = 0;
    havePeer = false;
    store.Sync();
}

void VirtualPokewalker::OnGameSend(const u8* data, size_t len)
{
    if (!IsOpen() || len == 0)
        return;

    std::vector<u8> packet(data, data + len);
    AirScramble(packet.data(), packet.size());

    if (packet.size() < HeaderSize)
    {
        Log("Ignoring short IR packet");
        return;
    }
    u16 got = rd16le(&packet[2]);
    u16 want = PacketChecksum(packet.data(), packet.size());
    if (got != want)
    {
        char msg[80];
        std::snprintf(msg, sizeof(msg), "Ignoring IR packet %02X with bad checksum", packet[0]);
        Log(msg);
        return;
    }

    lastActivityMs = nowMs();
    if (packet[0] == cmd::AssertMaster)
    {
        StartSession(packet);
        return;
    }
    if (air != AirState::Connected)
    {
        char msg[80];
        std::snprintf(msg, sizeof(msg), "Ignoring IR packet %02X outside a session", packet[0]);
        Log(msg);
        return;
    }
    if (std::memcmp(&packet[4], session, 4) != 0)
        Log("Warning: IR packet with an unexpected session id");

    Handle(packet);
}

int VirtualPokewalker::OnGamePoll(u8* out, size_t maxLen)
{
    if (!IsOpen())
        return 0;
    s64 now = nowMs();
    s64 sincePoll = now - lastPollMs;
    lastPollMs = now;

    if (!pending.empty())
    {
        size_t n = std::min(maxLen, pending.size());
        std::memcpy(out, pending.data(), n);
        pending.clear();
        lastActivityMs = now;
        if (endAfterReply)
        {
            endAfterReply = false;
            EndSession("communication complete");
        }
        return (int)n;
    }

    switch (air)
    {
    case AirState::Connected:
        if (now - lastActivityMs > kSessionTimeoutMs)
            EndSession("timed out");
        return 0;

    case AirState::Cooldown:
        if (sincePoll > kCooldownGapMs || now - cooldownSinceMs > kCooldownMaxMs)
        {
            air = AirState::Idle;
            quietPolls = 0;
        }
        return 0;

    case AirState::Idle:
        if (sincePoll > kPollGapResetMs)
            quietPolls = 0;
        quietPolls++;
        if (quietPolls > kQuietPollsBeforeAdvert && now - lastAdvertMs >= kAdvertIntervalMs && maxLen >= 1)
        {
            lastAdvertMs = now;
            quietPolls = 0;
            out[0] = cmd::Advertising ^ AirXor;
            return 1;
        }
        return 0;
    }
    return 0;
}

void VirtualPokewalker::StartSession(const std::vector<u8>& packet)
{
    if (air == AirState::Connected)
        Log("Game restarted the Pokewalker session");

    RollOverDay();

    u8 walkerHalf[4];
    u32 r = rng();
    wr32le(walkerHalf, r);
    for (int i = 0; i < 4; i++)
        session[i] = packet[4 + i] ^ walkerHalf[i];

    air = AirState::Connected;
    havePeer = false;
    endAfterReply = false;

    std::vector<u8> reply = BuildPacket(cmd::SlaveAck, ExtraFromWalker, walkerHalf, nullptr, 0);
    AirScramble(reply.data(), reply.size());
    pending = std::move(reply);
    Log("Game connected to the virtual Pokewalker");
}

void VirtualPokewalker::EndSession(const char* reason)
{
    air = AirState::Cooldown;
    cooldownSinceMs = nowMs();
    havePeer = false;
    store.Sync();
    Log(std::string("Pokewalker session ended: ") + reason);
}

void VirtualPokewalker::Reply(u8 command, u8 extra, const u8* payload, size_t len)
{
    std::vector<u8> reply = BuildPacket(command, extra, session, payload, len);
    AirScramble(reply.data(), reply.size());
    if (!pending.empty())
        Log("Warning: previous IR reply was never read by the game");
    pending = std::move(reply);
}

void VirtualPokewalker::Handle(const std::vector<u8>& packet)
{
    const u8 c = packet[0];
    const u8 extra = packet[1];
    const u8* payload = packet.data() + HeaderSize;
    const size_t payloadLen = packet.size() - HeaderSize;

    switch (c)
    {
    case cmd::IdentityReq:
    {
        Health h = ReadHealth();
        Identity ident = ReadIdentity();
        wr32be(&ident[id::StepCount], rd32be(&h[hd::TotalSteps]));
        WriteIdentity(ident);
        WriteWattsMirror(rd16be(&h[hd::Watts]));
        Reply(cmd::IdentityRsp, ExtraFromWalker, ident.data(), ident.size());
        break;
    }

    case cmd::IdentitySend:
    case cmd::IdentitySendAlias1:
    case cmd::IdentitySendAlias2:
    case cmd::IdentitySendAlias3:
    {
        peer.fill(0);
        std::memcpy(peer.data(), payload, std::min(payloadLen, peer.size()));
        havePeer = true;
        u32 lastSync = rd32be(&peer[id::LastSync]);
        if (lastSync != 0)
        {
            Health h = ReadHealth();
            wr32be(&h[hd::LastSync], lastSync);
            WriteHealth(h);
            Identity ident = ReadIdentity();
            wr32be(&ident[id::LastSync], lastSync);
            WriteIdentity(ident);
        }
        Reply((u8)(c + 2), ExtraFromWalker);
        break;
    }

    case cmd::EepromReadReq:
    {
        if (payloadLen < 3) { Log("EEPROM read request too short"); break; }
        u32 addr = (u32)(payload[0] << 8 | payload[1]);
        size_t n = std::min<size_t>(payload[2], MaxPayload);
        u8 buf[MaxPayload];
        store.Read(addr, buf, n);
        Reply(cmd::EepromReadRsp, ExtraFromWalker, buf, n);
        break;
    }

    case cmd::EepromWriteCmp00:
    case cmd::EepromWriteCmp80:
    case cmd::EepromWriteRaw00:
    case cmd::EepromWriteRaw80:
        DoEepromWrite(packet);
        Reply(cmd::EepromWriteAck, extra);
        break;

    case cmd::Ping:
        Reply(cmd::Pong, ExtraFromWalker);
        break;

    case cmd::WalkStartInit:
    case cmd::WalkStart:
        Reply(c, ExtraFromWalker);
        DoWalkStart(c == cmd::WalkStartInit);
        break;

    case cmd::WalkEndReq:
        Reply(cmd::WalkEndAck, ExtraFromWalker);
        DoWalkEnd();
        break;

    case cmd::WalkerReset0:
    case cmd::WalkerReset1:
    case cmd::WalkerReset3:
    {
        u8 unique[ee::UniqueIdSize];
        store.ReadReliable(ee::UniqueId1, ee::UniqueId2, unique, sizeof(unique));
        Reply(c, ExtraFromWalker, unique, sizeof(unique));
        DoReset(c != cmd::WalkerReset0, c == cmd::WalkerReset3);
        break;
    }

    case cmd::ConnectComplete:
        Reply(cmd::ConnectCompleteAck, ExtraFromWalker);
        endAfterReply = true;
        break;

    case cmd::NoRx:
        Reply(cmd::NoRxAck, ExtraFromWalker);
        break;

    case cmd::Disconnect:
        EndSession("game disconnected");
        break;

    case cmd::NoComplete:
    case cmd::NoCompleteAlias1:
    case cmd::NoCompleteAlias2:
    case cmd::NoCompleteAlias3:
        EndSession("game could not complete the operation");
        break;

    default:
    {
        char msg[80];
        std::snprintf(msg, sizeof(msg), "Unsupported Pokewalker command %02X (%zu bytes)", c, payloadLen);
        Log(msg);
        break;
    }
    }
}

void VirtualPokewalker::DoEepromWrite(const std::vector<u8>& packet)
{
    const u8 c = packet[0];
    const u32 addr = (u32)(packet[1] << 8) | (c & 0x80);
    const u8* payload = packet.data() + HeaderSize;
    const size_t payloadLen = packet.size() - HeaderSize;
    const bool compressed = (c & 0x02) == 0 && packet.size() < HeaderSize + MaxPayload;

    if (compressed)
    {
        u8 buf[0x100];
        int n = Lz10Decompress(payload, payloadLen, buf, sizeof(buf));
        if (n < 0)
        {
            char msg[80];
            std::snprintf(msg, sizeof(msg), "Bad compressed EEPROM write at %04X ignored", addr);
            Log(msg);
            return;
        }
        store.Write(addr, buf, std::min<size_t>((size_t)n, MaxPayload));
    }
    else
    {
        store.Write(addr, payload, std::min(payloadLen, MaxPayload));
    }
}

void VirtualPokewalker::DoWalkStart(bool initAfterPairing)
{
    u8 marker = 0xA5;
    store.WriteReliable(ee::CopyMarker1, ee::CopyMarker2, &marker, 1);
    store.Copy(ee::RouteArea, ee::ScenarioStaging, ee::RouteAreaSize);
    store.Copy(ee::TeamData, ee::TeamStaging, ee::TeamDataSize);
    marker = 0;
    store.WriteReliable(ee::CopyMarker1, ee::CopyMarker2, &marker, 1);

    Health h = ReadHealth();
    if (initAfterPairing)
        wr32be(&h[hd::TodaySteps], 0);
    wr16be(&h[hd::WalkMinutes], 0);
    h[hd::EventLogIndex] = 0;
    wr16be(&h[hd::Watts], 0);
    WriteHealth(h);
    WriteWattsMirror(0);

    store.Fill(ee::EventLog, 0, ee::EventLogSize);
    store.Fill(ee::MetPeers, 0, ee::MetPeersSize);
    store.Fill(ee::Caught, 0, ee::WalkResultsSize);

    Identity ident = ReadIdentity();
    if (havePeer)
    {
        std::memcpy(&ident[id::Unk0], &peer[id::Unk0], 4);
        std::memcpy(&ident[id::Unk1], &peer[id::Unk0], 4);
        std::memcpy(&ident[id::Unk2], &peer[id::Unk2], 2);
        std::memcpy(&ident[id::Unk3], &peer[id::Unk2], 2);
        std::memcpy(&ident[id::Tid], &peer[id::Tid], 4); // TID + SID
        std::memcpy(&ident[id::UniqueData], &peer[id::UniqueData], ee::UniqueIdSize);
        std::memcpy(&ident[id::TrainerName], &peer[id::TrainerName], 16);
        ident[id::ProtocolVer] = peer[id::ProtocolVer];
        ident[id::Unk5] = peer[id::Unk5];
        ident[id::ProtocolSubver] = peer[id::ProtocolSubver];
        ident[id::Unk8] = peer[id::Unk8];
        if (rd32be(&peer[id::LastSync]) != 0)
            std::memcpy(&ident[id::LastSync], &peer[id::LastSync], 4);
    }
    else
    {
        Log("Warning: walk started without the game's identity data");
    }
    ident[id::Flags] = (u8)((ident[id::Flags] & ~id::FlagPokemonJoined) | id::FlagRegistered | id::FlagHasPokemon);
    wr32be(&ident[id::StepCount], rd32be(&h[hd::TotalSteps]));
    WriteIdentity(ident);
    store.Sync();

    meta.strollStartUnix = nowUnix();
    SaveMeta();

    u8 routeInfo[0xBE];
    store.Read(ee::RouteInfo, routeInfo, sizeof(routeInfo));
    u16 species = rd16le(&routeInfo[route::Species]);
    Log(std::string("Stroll started with ") + SpeciesName(species) + " on " + DecodeGameText(&routeInfo[route::Name], 21));
}

void VirtualPokewalker::DoWalkEnd()
{
    Identity ident = ReadIdentity();
    wr32le(&ident[id::Unk1], 0);
    wr16le(&ident[id::Unk3], 0);
    ident[id::Flags] &= (u8)~id::FlagHasPokemon;
    WriteIdentity(ident);

    Health h = ReadHealth();
    wr16be(&h[hd::Watts], 0);
    h[hd::EventLogIndex] = 0;
    WriteHealth(h);
    WriteWattsMirror(0);

    store.Fill(ee::Caught, 0, ee::WalkResultsSize);
    store.Fill(ee::EventLog, 0, ee::EventLogSize);
    store.Fill(ee::ReceivedBitfield, 0, ee::ReceivedBitfieldSize);
    store.Fill(ee::MetPeers, 0, ee::MetPeersSize);
    store.Fill(ee::RouteInfo, 0, 0x10);
    store.Sync();

    meta.strollStartUnix = 0;
    SaveMeta();
    Log("Stroll ended; the Pokemon went home");
}

void VirtualPokewalker::DoReset(bool clearEvents, bool clearSteps)
{
    Identity ident = ReadIdentity();
    std::memset(&ident[id::Unk0], 0, 0x10);          // unk0..unk3, TID, SID
    std::memset(&ident[id::TrainerName], 0, 0x12);   // trainer name + 2 bytes after it
    if (clearEvents)
        std::memset(&ident[id::EventBitmap], 0, 16);
    ident[id::Flags] = 0;
    ident[id::EventIndex] = 0;
    ident[id::ProtocolSubver] = 0;
    ident[id::Unk8] = 0x02;
    wr32be(&ident[id::LastSync], 0);
    store.ReadReliable(ee::UniqueId1, ee::UniqueId2, &ident[id::UniqueData], ee::UniqueIdSize);
    if (clearSteps)
        wr32be(&ident[id::StepCount], 0);
    WriteIdentity(ident);

    Health h = ReadHealth();
    if (clearSteps)
    {
        wr32be(&h[hd::TotalSteps], 0);
        wr32be(&h[hd::TodaySteps], 0);
        wr16be(&h[hd::TotalDays], 0);
        wr32be(&h[hd::LastSync], kFactoryLastSync);
    }
    wr16be(&h[hd::WalkMinutes], 0);
    wr16be(&h[hd::Watts], 0);
    h[hd::StepsThisWatt] = 0;
    h[hd::EventLogIndex] = 0;
    h[hd::Settings] = (u8)((h[hd::Settings] & 0xA4) | 0x24);
    WriteHealth(h);

    if (clearSteps)
    {
        store.Fill(ee::PerWalkArea, 0, ee::PerWalkAreaSize);
    }
    else
    {
        store.Fill(ee::Caught, 0, ee::WalkResultsSize);
        store.Fill(ee::EventLog, 0xFF, ee::EventLogSize);
        store.Fill(ee::History, 0, ee::HistoryDays * 4);
    }
    if (clearEvents)
        store.Fill(ee::ReceivedBitfield, 0, ee::ReceivedBitfieldSize);
    store.Fill(ee::MetPeers, 0, ee::MetPeersSize);
    store.Write(ee::Nintendo, reinterpret_cast<const u8*>("nintendo"), 8);
    store.Sync();

    meta.strollStartUnix = 0;
    if (clearSteps)
        meta.dayNumber = localDay(nowUnix());
    SaveMeta();
    Log(clearSteps ? "Game erased the Pokewalker (including lifetime steps)" : "Game reset the Pokewalker for pairing");
}

}
