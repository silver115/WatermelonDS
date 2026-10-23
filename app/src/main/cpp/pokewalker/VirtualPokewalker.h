/*
    Virtual Pokéwalker for melonDS Android.

    Plays the Pokéwalker's side of the IR protocol against the emulated
    HeartGold/SoulSilver cartridge, backed by a 64 KiB EEPROM image file in the
    same format as a real walker's EEPROM (so dumps can be imported/exported).

    Phone step data is added through AddSteps(); watts accrue at 20 steps each,
    like on the real device, and are transferred to the game when the Pokémon
    returns from its stroll.

    GPL-3.0-or-later, see PwCommon.h.
*/

#ifndef POKEWALKER_VIRTUALPOKEWALKER_H
#define POKEWALKER_VIRTUALPOKEWALKER_H

#include <array>
#include <functional>
#include <string>
#include <vector>

#include "EepromStore.h"
#include "PwCommon.h"

namespace pokewalker
{

class VirtualPokewalker
{
public:
    using MonotonicMs = std::function<s64()>;
    using UnixSeconds = std::function<s64()>;
    using LocalDayOf = std::function<s64(s64 unixSeconds)>;
    using Logger = std::function<void(const std::string&)>;
    using Random32 = std::function<u32()>;

    VirtualPokewalker();
    ~VirtualPokewalker();

    /// Opens (or creates) the walker image. A missing file becomes a fresh,
    /// unregistered walker, like a Pokéwalker straight out of the box.
    bool Open(const std::string& eepromPath, std::string* error);
    void Close();
    bool IsOpen() const { return store.IsOpen(); }
    const std::string& Path() const { return store.Path(); }

    // ---- infrared side, driven by the emulated cartridge -----------------------
    /// The cart was reset: forget any half-finished session.
    void OnCartReset();
    /// The game transmitted one packet (still XOR-scrambled, as on air).
    void OnGameSend(const u8* data, size_t len);
    /// The game polls its receiver. Returns the number of bytes copied to out.
    int OnGamePoll(u8* out, size_t maxLen);
    /// True while the game is talking to the walker (UI edits are refused).
    bool SessionActive() const;

    // ---- walker model, used by the app UI ---------------------------------------
    std::string StatusJson();
    /// Adds steps walked daysAgo days ago (0 = today). Updates today's count or
    /// the 7-day history, the lifetime total, and watts.
    bool AddSteps(u32 steps, int daysAgo, std::string* error);
    /// Replaces the image with a fresh, unregistered walker.
    bool ResetToFactory(std::string* error);
    bool ImportImage(const std::vector<u8>& image, std::string* error);
    std::vector<u8> ExportImage() const;

    // ---- the walker's own games, played from the app -----------------------------------
    static constexpr u32 RadarCost = 10;
    static constexpr u32 DowsingCost = 3;
    /// Poké Radar: spends 10 W and picks which route Pokémon appears, using the
    /// walker's rules (today's steps against each slot's requirement, then its
    /// chance). Sets slot (0-2) and a JSON description of the encounter.
    /// allowFull: also play when 3 Pokémon are already held (the catch then
    /// replaces one, like the device's "switch?").
    bool RadarEncounter(int* slot, std::string* json, std::string* error, bool allowFull = false);
    /// Keeps route Pokémon `slot` in the first free catch slot (3 per stroll),
    /// or in place of catch slot `replace` (0-2) when given.
    bool StoreCaught(int slot, std::string* error, int replace = -1);
    /// Dowsing: spends 3 W and picks the item hidden in the grass.
    bool DowsingStart(u16* item, std::string* json, std::string* error, bool allowFull = false);
    /// Keeps a dowsed item in the first free item slot (3 per stroll), or in
    /// place of item slot `replace` (0-2) when given.
    bool StoreDowsed(u16 item, std::string* error, int replace = -1);

    // ---- read access for the on-screen walker (WalkerDevice) --------------------------
    struct Snapshot
    {
        bool open = false;
        bool registered = false;
        bool hasPokemon = false;
        bool session = false;
        u32 todaySteps = 0;
        u32 totalSteps = 0;
        u16 watts = 0;
        u16 totalDays = 0;
        u8 settings = 0;
        u32 history[ee::HistoryDays] {};
    };
    /// Current numbers; rolls the day over first when no game is connected.
    Snapshot GetSnapshot();
    /// The raw 64 KiB image, for drawing the walker's own screens.
    const u8* Image() const { return store.Data(); }
    /// Stores the sound/shade settings byte (HealthData +0x17).
    bool SetSettings(u8 settings, std::string* error);

    // ---- hooks for tests / platform integration ------------------------------------
    void SetClocks(MonotonicMs mono, UnixSeconds wall, LocalDayOf localDay);
    void SetLogger(Logger logger) { log = std::move(logger); }
    void SetRandom(Random32 generator) { rng = std::move(generator); }

    /// Builds the image of a brand-new walker (exposed for tests).
    std::vector<u8> BuildFactoryImage();

private:
    enum class AirState { Idle, Connected, Cooldown };

    struct Meta
    {
        s64 dayNumber = -1;       // local day that today's step count belongs to
        s64 strollStartUnix = 0;  // when the current stroll began (0 = none)
    };

    using Health = std::array<u8, ee::HealthSize>;
    using Identity = std::array<u8, ee::IdentitySize>;

    EepromStore store;
    std::string metaPath;
    Meta meta;

    MonotonicMs nowMs;
    UnixSeconds nowUnix;
    LocalDayOf localDay;
    Logger log;
    Random32 rng;

    // air interface state
    AirState air = AirState::Idle;
    int quietPolls = 0;
    s64 lastPollMs = 0;
    // Diagnostics: one "listening period" per connection attempt by the game.
    bool listenSeen = false;
    int listenAdverts = 0;
    bool listenConnected = false;
    s64 listenStartMs = 0;
    s64 lastListenPollMs = 0;
    s64 lastAdvertMs = -1000000;
    s64 lastActivityMs = 0;
    s64 cooldownSinceMs = 0;
    bool endAfterReply = false;
    std::vector<u8> pending;
    u8 session[4] {};
    Identity peer {};
    bool havePeer = false;

    void Log(const std::string& msg) const;

    // EEPROM structures
    Health ReadHealth() const;
    void WriteHealth(const Health& h);
    Identity ReadIdentity() const;
    void WriteIdentity(const Identity& id);
    void WriteWattsMirror(u16 watts);

    void LoadMeta();
    void SaveMeta();
    void RollOverDay();

    // protocol
    void StartSession(const std::vector<u8>& packet);
    void EndSession(const char* reason);
    void Handle(const std::vector<u8>& packet);
    void Reply(u8 command, u8 extra, const u8* payload = nullptr, size_t len = 0);
    void DoEepromWrite(const std::vector<u8>& packet);
    void DoWalkStart(bool initAfterPairing);
    void DoWalkEnd();
    void DoReset(bool clearEvents, bool clearSteps);

    bool CheckPlayable(u32 cost, std::string* error);
    void SpendWatts(u32 cost);
    int FreeSlot(u32 base, int count, int stride) const;
};

}

#endif
