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
#include <iterator>
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

// How long the walker keeps answering the way a refusing game expects. After a refusal the
// game asks the player to try again right away (message 7, then CONNECT), so a short time
// is enough and another save started later still meets the walker as it really is. A
// transfer the game is known to have lost is fixed whenever the player next connects.
static constexpr s64 kHealAfterRefusalSec = 5 * 60;
static constexpr s64 kHealAfterLossSec = 60LL * 24 * 3600;
// A copy of the walker as it was before its last return lets a return the game lost be
// taken back (the game still has the Pokémon out on its stroll).
static constexpr s64 kLastWalkKeepSec = 60LL * 24 * 3600;
static const char kLastWalkMagic[8] = {'P', 'W', 'L', 'A', 'S', 'T', 'W', '1'};

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
        // The app closed after a transfer the game hadn't saved yet: the game never kept it.
        if (meta.pending != (u8)Pending::None)
            TransferLost("closed");
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
    DropLastWalk();
    Log("Virtual Pokewalker reset to factory state");
    return true;
}

bool VirtualPokewalker::Unpair(std::string* error)
{
    if (!IsOpen()) { if (error) *error = "the virtual Pokewalker is not open"; return false; }
    if (SessionActive()) { if (error) *error = "the game is connected to the Pokewalker right now"; return false; }
    RollOverDay();
    Identity ident = ReadIdentity();
    std::string was = (ident[id::Flags] & id::FlagRegistered) ? GameName(rd16le(&ident[id::Unk2])) : std::string();
    // the game's "Erase Pokéwalker records" (0x2C), keeping what the phone counted
    u8 hist[ee::HistoryDays * 4];
    store.Read(ee::History, hist, sizeof(hist));
    DoReset(false, false);
    store.Write(ee::History, hist, sizeof(hist));
    store.Sync();
    Log(was.empty() ? "Unpaired in the app (the walker was not paired)"
                    : "Unpaired from " + was + " in the app: any game can register this walker now");
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
    DropLastWalk();
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
        else if (key == "heal") meta.heal = (u8)value;
        else if (key == "healUntil") meta.healUntil = value;
        else if (key == "pending") meta.pending = (u8)value;
        else if (key == "pendingAt") meta.pendingUnix = value;
    }
    if (meta.heal > (u8)Heal::NoPokemon) meta.heal = 0;
    if (meta.pending > (u8)Pending::Return) meta.pending = 0;
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
        if (meta.heal) out << "heal=" << (int)meta.heal << "\nhealUntil=" << meta.healUntil << "\n";
        if (meta.pending) out << "pending=" << (int)meta.pending << "\npendingAt=" << meta.pendingUnix << "\n";
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
    if (registered)
        js << ",\"pairedWith\":\"" << JsonEscape(GameName(rd16le(&ident[id::Unk2]))) << "\"";
    // a transfer the game still has to save (the player should let it finish)
    const bool awaiting = meta.pending != (u8)Pending::None && nowUnix() - meta.pendingUnix < 3600;
    js << ",\"awaitingGameSave\":" << (awaiting ? "true" : "false");
    if (awaiting)
        js << ",\"awaitingKind\":" << (int)meta.pending << ",\"awaitingName\":\"" << JsonEscape(StrollName()) << "\"";
    if (!notice.empty() && nowUnix() - noticeUnix < 3600)
        js << ",\"notice\":\"" << JsonEscape(notice) << "\"";
    js << "}";
    return js.str();
}

// ---------------------------------------------------------------------------
// Keeping the game and the walker in step
// ---------------------------------------------------------------------------

std::string VirtualPokewalker::GameName(u16 version) const
{
    switch (version)
    {
    case 7: return "HeartGold";
    case 8: return "SoulSilver";
    default: return "the game";
    }
}

std::string VirtualPokewalker::StrollName() const
{
    u8 routeInfo[route::Size];
    store.Read(ee::RouteInfo, routeInfo, sizeof(routeInfo));
    std::string name = DecodeGameText(&routeInfo[route::Nickname], 11);
    // characters the walker can't show come out as '?': fall back to the species
    if (name.empty() || name.find('?') != std::string::npos)
    {
        u16 species = rd16le(&routeInfo[route::Species]);
        name = species >= 1 && species <= 493 ? SpeciesName(species) : "";
    }
    return name.empty() ? std::string("your Pokémon") : name;
}

void VirtualPokewalker::SetGame(u32 gamecode)
{
    char c2 = (char)((gamecode >> 16) & 0xFF);
    bool hgss = (gamecode & 0xFFFF) == ('I' | 'P' << 8);
    gameVersion = !hgss ? 0 : c2 == 'K' ? 7 : c2 == 'G' ? 8 : 0;
}

void VirtualPokewalker::SetNotice(const std::string& text)
{
    notice = text;
    if (!notice.empty() && notice[0] >= 'a' && notice[0] <= 'z') notice[0] = (char)(notice[0] - 'a' + 'A');
    noticeUnix = nowUnix();
    noticeTaken = false;
    Log("Note for the player: " + text);
}

std::string VirtualPokewalker::TakeNotice()
{
    if (noticeTaken || notice.empty() || nowUnix() - noticeUnix > 3600) return std::string();
    noticeTaken = true;
    return notice;
}

void VirtualPokewalker::ArmHeal(Heal heal, s64 seconds)
{
    meta.heal = (u8)heal;
    meta.healUntil = heal == Heal::None ? 0 : nowUnix() + seconds;
    SaveMeta();
}

void VirtualPokewalker::SetPending(Pending kind)
{
    meta.pending = (u8)kind;
    meta.pendingUnix = kind == Pending::None ? 0 : nowUnix();
    SaveMeta();
}

void VirtualPokewalker::OnGameSave(bool wholesale)
{
    if (!IsOpen() || meta.pending == (u8)Pending::None) return;
    if (wholesale)
    {
        // a save state or rewind replaced the game, save memory and all
        TransferLost("rewound (a save state was loaded)");
        return;
    }
    Log("The game saved: the last transfer is complete on both sides");
    SetPending(Pending::None);
}

void VirtualPokewalker::TransferLost(const char* how)
{
    Pending kind = (Pending)meta.pending;
    SetPending(Pending::None);
    Identity ident = ReadIdentity();
    std::string game = GameName(gameVersion ? gameVersion : rd16le(&ident[id::Unk2]));
    std::string mon = StrollName();
    switch (kind)
    {
    case Pending::Paired:
    case Pending::Stroll:
        Log("The game was " + std::string(how) + " before it saved the last transfer, so it doesn't know about it; "
            "the walker will answer it the way it expects next time");
        ArmHeal(kind == Pending::Paired ? Heal::Unregistered : Heal::NoPokemon, kHealAfterLossSec);
        SetNotice(game + " didn't save the transfer, so " + mon + " is still in its PC. "
                  "Your next stroll will put the walker right.");
        break;
    case Pending::Return:
    {
        Log("The game was " + std::string(how) + " before it saved the last return");
        std::string why;
        if (RestoreLastWalk(&why))
            SetNotice(game + " didn't save " + StrollName() + "'s return, so the walker has its stroll back. "
                      "Return from a Stroll again.");
        else
            Log("The stroll can't be brought back: " + why);
        break;
    }
    default:
        break;
    }
}

// The game refused the walker after reading its identity (0x36 / 0x44 / 0x56 / 0x64). Its
// checks (ov112_021E6BF8) tell from the walker's answer why; when the reason is that the game
// lost a transfer, answer the next identity request the way the game expects.
void VirtualPokewalker::OnRefused(u8 c)
{
    if (!answered) return;
    Identity ident = ReadIdentity();
    const bool registered = answeredFlags & id::FlagRegistered;
    const bool hasPokemon = answeredFlags & id::FlagHasPokemon;
    const u16 ownerVersion = rd16le(&ident[id::Unk2]);
    const std::string game = GameName(gameVersion);
    const std::string owner = GameName(ownerVersion);
    const bool sameGame = gameVersion == 0 || ownerVersion == 0 || gameVersion == ownerVersion;
    const std::string trainer = DecodeGameText(&ident[id::TrainerName], 8);
    const std::string whose = (trainer.empty() || trainer.find('?') != std::string::npos ? std::string("another trainer")
                                                                                          : trainer) + "'s " + owner;
    const std::string mon = StrollName();
    const char* lostWhy = "the game was probably reset, rewound or closed before it saved the last transfer";

    if (healUsed != Heal::None)
    {
        // the changed answer didn't suit this game: answer it truthfully next time
        Log("The game refused the changed answer too; answering truthfully from now on");
        ArmHeal(Heal::None, 0);
        SetNotice("Sorry, the walker guessed wrong. Tap CONNECT again.");
        return;
    }

    switch (c)
    {
    case cmd::NoComplete: // register: "This Pokéwalker has already been registered."
        if (!registered) break;
        if (!sameGame)
        {
            Log("Refused: " + game + " can't register a walker paired with " + owner);
            SetNotice("This Pokéwalker is paired with " + owner + ". Erase it there or unpair it in the walker settings first.");
            break;
        }
        Log(game + " says the walker is already registered, but it has no record of it: " + lostWhy);
        ArmHeal(Heal::Unregistered, kHealAfterRefusalSec);
        SetNotice(game + " lost track of this Pokéwalker. Tap CONNECT again to pair it again.");
        break;

    case cmd::NoCompleteAlias1: // stroll
        if (!registered)
        {
            Log("Refused: " + game + " thinks it is paired with this walker, but the walker isn't paired");
            SetNotice("The walker isn't paired. In the game, erase the Pokéwalker records, then go for a stroll.");
        }
        else if (hasPokemon && sameGame)
        {
            // "There is already a Pokémon on this Pokéwalker.": the game has no stroll on record
            Log(game + " says there is already a Pokemon on the walker, but it has no record of that stroll: " + lostWhy);
            ArmHeal(Heal::NoPokemon, kHealAfterRefusalSec);
            SetNotice(game + " lost track of " + mon + "'s stroll. Tap CONNECT again to send the new Pokémon.");
        }
        else
        {
            Log("Refused: the walker belongs to " + whose);
            SetNotice("This Pokéwalker belongs to " + whose + ". Erase it there or unpair it in the walker settings.");
        }
        break;

    case cmd::NoCompleteAlias3: // return
        if (registered && !hasPokemon)
        {
            // "There are not any Pokémon on this Pokéwalker.": the game still has one out
            Log(game + " still has a Pokemon out on a stroll with this walker, which ended it: the game was probably "
                       "rewound or closed before it saved the last return");
            std::string why;
            if (sameGame && RestoreLastWalk(&why))
                SetNotice(game + " still had " + StrollName() + " out, so the walker brought its stroll back. "
                          "Tap CONNECT again.");
            else
            {
                if (!why.empty()) Log("The stroll can't be brought back: " + why);
                SetNotice("The walker has no Pokémon. Choose \"Send Pokémon back home\" in the game to get yours back.");
            }
        }
        else if (!registered)
        {
            Log("Refused: " + game + " has a Pokemon out on this walker, but the walker isn't paired");
            SetNotice("The walker isn't paired. Choose \"Send Pokémon back home\" in the game to get your Pokémon back.");
        }
        else
        {
            Log("Refused: the walker belongs to " + whose);
            SetNotice("This Pokéwalker belongs to " + whose + ", not this game.");
        }
        break;

    case cmd::NoCompleteAlias2: // gifts
        if (!registered)
            SetNotice("The walker isn't paired with " + game + " yet: go for a stroll first.");
        break;
    }
}

void VirtualPokewalker::SaveLastWalk()
{
    std::string path = LastWalkPath();
    std::string tmp = path + ".tmp";
    {
        std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
        if (!out) return;
        u8 head[24] {};
        std::memcpy(head, kLastWalkMagic, 8);
        s64 times[2] = {nowUnix(), meta.strollStartUnix};
        for (int t = 0; t < 2; t++)
            for (int i = 0; i < 8; i++) head[8 + t * 8 + i] = (u8)((u64)times[t] >> (8 * i));
        out.write(reinterpret_cast<const char*>(head), sizeof(head));
        out.write(reinterpret_cast<const char*>(store.Data()), ee::Size);
        if (!out) return;
    }
    std::rename(tmp.c_str(), path.c_str());
}

void VirtualPokewalker::DropLastWalk()
{
    if (!store.Path().empty()) std::remove(LastWalkPath().c_str());
}

bool VirtualPokewalker::RestoreLastWalk(std::string* why)
{
    std::ifstream in(LastWalkPath(), std::ios::binary);
    if (!in) { *why = "there is no copy of it"; return false; }
    std::vector<u8> file((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    if (file.size() != 24 + ee::Size || std::memcmp(file.data(), kLastWalkMagic, 8) != 0)
    {
        *why = "its copy is damaged";
        return false;
    }
    s64 times[2] = {0, 0};
    for (int t = 0; t < 2; t++)
        for (int i = 7; i >= 0; i--) times[t] = (s64)(((u64)times[t] << 8) | file[8 + t * 8 + i]);
    std::vector<u8> img(file.begin() + 24, file.end());

    auto reliable = [&img](u32 a1, u32 a2, u8* data, size_t len) {
        // the first copy if its checksum is good, else the second
        for (u32 a : {a1, a2})
            if (EepromStore::ReliableChecksum(&img[a], len) == img[a + len]) { std::memcpy(data, &img[a], len); return true; }
        return false;
    };
    auto writeReliable = [&img](u32 a1, u32 a2, const u8* data, size_t len) {
        u8 chk = EepromStore::ReliableChecksum(data, len);
        for (u32 a : {a1, a2}) { std::memcpy(&img[a], data, len); img[a + len] = chk; }
    };

    Identity then {}, now = ReadIdentity();
    if (!reliable(ee::Identity1, ee::Identity2, then.data(), then.size())) { *why = "its copy is damaged"; return false; }
    if ((then[id::Flags] & (id::FlagRegistered | id::FlagHasPokemon)) != (id::FlagRegistered | id::FlagHasPokemon))
    {
        *why = "the copy has no stroll";
        return false;
    }
    if (!(now[id::Flags] & id::FlagRegistered) || std::memcmp(&then[id::Tid], &now[id::Tid], 4) != 0)
    {
        *why = "the walker has been paired again since";
        return false;
    }
    if (nowUnix() - times[0] > kLastWalkKeepSec || nowUnix() < times[0] - 86400)
    {
        *why = "it is too old";
        return false;
    }

    // the stroll as it was, with what the phone counted since
    Health hNow = ReadHealth(), hThen {};
    reliable(ee::Health1, ee::Health2, hThen.data(), hThen.size());
    Health h = hNow;
    u32 watts = std::min<u32>(rd16be(&hThen[hd::Watts]) + rd16be(&hNow[hd::Watts]), MaxWatts);
    wr16be(&h[hd::Watts], (u16)watts);
    std::memcpy(&h[hd::WalkMinutes], &hThen[hd::WalkMinutes], 2);
    h[hd::EventLogIndex] = hThen[hd::EventLogIndex];
    writeReliable(ee::Health1, ee::Health2, h.data(), h.size());
    wr16be(&img[ee::WattsMirror], (u16)watts);
    store.Read(ee::History, &img[ee::History], ee::HistoryDays * 4);

    Identity ident = then;
    std::memcpy(&ident[id::StepCount], &now[id::StepCount], 4);
    std::memcpy(&ident[id::LastSync], &now[id::LastSync], 4);
    writeReliable(ee::Identity1, ee::Identity2, ident.data(), ident.size());

    std::string error;
    if (!store.ReplaceAll(img.data(), img.size(), &error)) { *why = error; return false; }
    store.Sync();
    meta.strollStartUnix = times[1] ? times[1] : nowUnix();
    SaveMeta();
    DropLastWalk();
    Log("Brought back the stroll the game lost (" + StrollName() + ")");
    return true;
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

bool VirtualPokewalker::RadarEncounter(int* slot, std::string* json, std::string* error, bool allowFull)
{
    if (IsOpen() && !SessionActive()) RollOverDay();
    if (!CheckPlayable(RadarCost, error)) return false;
    if (!allowFull && FreeSlot(ee::Caught, ee::CaughtCount, 16) < 0)
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

bool VirtualPokewalker::StoreCaught(int slot, std::string* error, int replace)
{
    if (!CheckPlayable(0, error)) return false;
    if (slot < 0 || slot > 2) { if (error) *error = "invalid route slot"; return false; }
    if (replace >= ee::CaughtCount) { if (error) *error = "invalid catch slot"; return false; }
    int free = replace >= 0 ? replace : FreeSlot(ee::Caught, ee::CaughtCount, 16);
    if (free < 0) { if (error) *error = "you already caught 3 Pokemon on this stroll"; return false; }
    u8 mon[16];
    store.Read(ee::RouteInfo + route::Pokemon + (u32)slot * 16, mon, sizeof(mon));
    store.Write(ee::Caught + (u32)free * 16, mon, sizeof(mon));
    store.Sync();
    Log(std::string("Caught ") + SpeciesName(rd16le(mon)));
    return true;
}

bool VirtualPokewalker::DowsingStart(u16* item, std::string* json, std::string* error, bool allowFull)
{
    if (IsOpen() && !SessionActive()) RollOverDay();
    if (!CheckPlayable(DowsingCost, error)) return false;
    if (!allowFull && FreeSlot(ee::Dowsed, ee::DowsedCount, 4) < 0)
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
    js << "{\"item\":" << *item << ",\"index\":" << chosen << ",\"itemName\":\"" << JsonEscape(ItemName(*item)) << "\""
       << ",\"watts\":" << rd16be(&ReadHealth()[hd::Watts]) << "}";
    *json = js.str();
    return true;
}

bool VirtualPokewalker::StoreDowsed(u16 item, std::string* error, int replace)
{
    if (!CheckPlayable(0, error)) return false;
    u8 ri[route::Size];
    store.Read(ee::RouteInfo, ri, sizeof(ri));
    bool onRoute = false;
    for (int i = 0; i < 10; i++)
        if (item != 0 && rd16le(&ri[route::Items + i * 2]) == item) onRoute = true;
    if (!onRoute) { if (error) *error = "that item is not on this route"; return false; }
    if (replace >= ee::DowsedCount) { if (error) *error = "invalid item slot"; return false; }
    int free = replace >= 0 ? replace : FreeSlot(ee::Dowsed, ee::DowsedCount, 4);
    if (free < 0) { if (error) *error = "you already found 3 items on this stroll"; return false; }
    u8 entry[4] = {0, 0, 0, 0};
    wr16le(entry, item);
    store.Write(ee::Dowsed + (u32)free * 4, entry, sizeof(entry));
    store.Sync();
    Log(std::string("Found ") + ItemName(item));
    return true;
}

VirtualPokewalker::Snapshot VirtualPokewalker::GetSnapshot()
{
    Snapshot snap;
    if (!IsOpen()) return snap;
    snap.open = true;
    snap.session = SessionActive();
    if (!snap.session) RollOverDay();
    Identity ident = ReadIdentity();
    Health h = ReadHealth();
    snap.registered = ident[id::Flags] & id::FlagRegistered;
    snap.hasPokemon = ident[id::Flags] & id::FlagHasPokemon;
    snap.todaySteps = rd32be(&h[hd::TodaySteps]);
    snap.totalSteps = rd32be(&h[hd::TotalSteps]);
    snap.watts = rd16be(&h[hd::Watts]);
    snap.totalDays = rd16be(&h[hd::TotalDays]);
    snap.settings = h[hd::Settings];
    u8 hist[ee::HistoryDays * 4];
    store.Read(ee::History, hist, sizeof(hist));
    for (int k = 0; k < ee::HistoryDays; k++) snap.history[k] = rd32be(&hist[k * 4]);
    return snap;
}

bool VirtualPokewalker::SetSettings(u8 settings, std::string* error)
{
    if (!IsOpen()) { if (error) *error = "the virtual Pokewalker is not open"; return false; }
    if (SessionActive()) { if (error) *error = "the game is connected to the Pokewalker right now"; return false; }
    Health h = ReadHealth();
    if (h[hd::Settings] == settings) return true;
    h[hd::Settings] = settings;
    WriteHealth(h);
    store.Sync();
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
    // the game restarted (or another game was loaded) before it saved the last transfer
    if (IsOpen() && meta.pending != (u8)Pending::None)
        TransferLost("restarted");
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

    if (!listenSeen || sincePoll > kPollGapResetMs)
    {
        if (listenSeen && listenAdverts > 0 && !listenConnected)
        {
            char msg[160];
            std::snprintf(msg, sizeof(msg), "The game stopped looking after %lld ms; it never answered the walker's %d signal(s)",
                          (long long)(lastListenPollMs - listenStartMs), listenAdverts);
            Log(msg);
        }
        Log("The game is looking for a Pokewalker (IR polling started)");
        listenSeen = true;
        listenAdverts = 0;
        listenConnected = false;
        listenStartMs = now;
    }
    lastListenPollMs = now;

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
            if (listenAdverts++ == 0)
                Log("Sending the Pokewalker's connect signal to the game");
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
    listenConnected = true;
    answered = false;
    answeredFlags = 0;
    healUsed = Heal::None;

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

        // A game that lost the last transfer only accepts the walker as it was before it.
        Identity answer = ident;
        Heal heal = (Heal)meta.heal;
        healUsed = Heal::None;
        if (heal != Heal::None && nowUnix() > meta.healUntil)
        {
            ArmHeal(Heal::None, 0);
            heal = Heal::None;
        }
        // only for the walker's own game: the other one gets the walker as it really is
        const u16 ownerVersion = rd16le(&ident[id::Unk2]);
        if (heal != Heal::None && gameVersion && ownerVersion && gameVersion != ownerVersion)
        {
            Log("Not changing the answer for " + GameName(gameVersion) + ": the walker is paired with " + GameName(ownerVersion));
        }
        else if (heal == Heal::Unregistered && (ident[id::Flags] & id::FlagRegistered))
        {
            answer[id::Flags] &= (u8)~(id::FlagRegistered | id::FlagHasPokemon | id::FlagPokemonJoined);
            healUsed = heal;
            Log("Answering as a walker the game can pair with (it lost the last pairing)");
        }
        else if (heal == Heal::NoPokemon && (ident[id::Flags] & id::FlagHasPokemon))
        {
            answer[id::Flags] &= (u8)~(id::FlagHasPokemon | id::FlagPokemonJoined);
            healUsed = heal;
            Log("Answering as a walker without a Pokemon (the game lost the last stroll start)");
        }
        else if (heal != Heal::None)
        {
            ArmHeal(Heal::None, 0); // nothing left to change
        }
        answered = true;
        answeredFlags = answer[id::Flags];
        Reply(cmd::IdentityRsp, ExtraFromWalker, answer.data(), answer.size());
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
        if (healUsed == Heal::Unregistered && c == cmd::IdentitySend)
        {
            Identity ident = ReadIdentity();
            Log(std::memcmp(&ident[id::Tid], &peer[id::Tid], 4) == 0
                    ? "The game is pairing with the walker again"
                    : "Another trainer of this game is pairing with the walker; its old stroll will be replaced");
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

    case cmd::EepromWriteRandom:
    {
        // Unaligned write: payload = [address low byte][data...], extra = address high byte.
        // HG/SS only uses it for writes that don't start on a 128-byte boundary.
        if (payloadLen < 1) { Log("EEPROM random write too short"); break; }
        u32 addr = (u32)(extra << 8) | payload[0];
        size_t n = std::min(payloadLen - 1, MaxPayload);
        store.Write(addr, payload + 1, n);
        Reply(cmd::EepromWriteAck, extra);
        break;
    }

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
        Log(c == cmd::WalkerReset3 ? "Game erased the Pokewalker (including lifetime steps)" : "Game reset the Pokewalker for pairing");
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
        // The game has gone; a reply still waiting for it (0x50/0x68) is moot.
        pending.clear();
        endAfterReply = false;
        EndSession("game disconnected");
        break;

    case cmd::NoComplete:
    case cmd::NoCompleteAlias1:
    case cmd::NoCompleteAlias2:
    case cmd::NoCompleteAlias3:
        EndSession("game could not complete the operation");
        OnRefused(c);
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
    // A walker the game pairs with again (or gives a new stroll) after losing the last
    // transfer keeps what it found and counted since: the game never collected it.
    const bool carry = healUsed != Heal::None;

    u8 marker = 0xA5;
    store.WriteReliable(ee::CopyMarker1, ee::CopyMarker2, &marker, 1);
    store.Copy(ee::RouteArea, ee::ScenarioStaging, ee::RouteAreaSize);
    store.Copy(ee::TeamData, ee::TeamStaging, ee::TeamDataSize);
    marker = 0;
    store.WriteReliable(ee::CopyMarker1, ee::CopyMarker2, &marker, 1);

    Health h = ReadHealth();
    if (initAfterPairing && !carry)
        wr32be(&h[hd::TodaySteps], 0);
    wr16be(&h[hd::WalkMinutes], 0);
    h[hd::EventLogIndex] = 0;
    // a stroll start (0x52) has just collected the watts (it reads them at 0xCE8A); pairing doesn't
    if (!(carry && initAfterPairing))
        wr16be(&h[hd::Watts], 0);
    WriteHealth(h);
    WriteWattsMirror(rd16be(&h[hd::Watts]));

    store.Fill(ee::EventLog, 0, ee::EventLogSize);
    store.Fill(ee::MetPeers, 0, ee::MetPeersSize);
    if (!carry)
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
        // ident[Unk8] stays the walker's own value (0x02 on real hardware); the game sends 0 there.
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
    meta.heal = 0;
    meta.healUntil = 0;
    SaveMeta();
    // the game still has to save this before it counts on its side
    SetPending(initAfterPairing ? Pending::Paired : Pending::Stroll);
    DropLastWalk();

    u8 routeInfo[0xBE];
    store.Read(ee::RouteInfo, routeInfo, sizeof(routeInfo));
    u16 species = rd16le(&routeInfo[route::Species]);
    Log(std::string("Stroll started with ") + SpeciesName(species) + " on " + DecodeGameText(&routeInfo[route::Name], 21) +
        (carry ? (initAfterPairing ? " (paired again; the finds and watts since the lost transfer stay)"
                                   : " (replacing the stroll the game lost; its finds stay)")
               : ""));
    if (carry)
        SetNotice("Back in step with " + GameName(gameVersion ? gameVersion : rd16le(&peer[id::Unk2])) + ": " +
                  StrollName() + " is on its stroll.");
}

void VirtualPokewalker::DoWalkEnd()
{
    // kept in case the game loses this return (reset, rewound or closed before it saves)
    SaveLastWalk();

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
    SetPending(Pending::Return);
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
    meta.heal = 0;
    meta.healUntil = 0;
    meta.pending = 0;
    meta.pendingUnix = 0;
    SaveMeta();
    DropLastWalk();
}

}
