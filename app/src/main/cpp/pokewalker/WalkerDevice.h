/*
    The virtual Pokéwalker's own screen: a 96x64, four-shade picture with three
    buttons, drawn from the graphics HeartGold/SoulSilver wrote into the walker
    (walking Pokémon, route, menus, texts) and driving the same walker the
    emulated game talks to. Home screen with today's steps, a menu, Poké Radar
    with its battle, dowsing, trainer card, Pokémon & items and settings.

    This is our own implementation of the device's behaviour; the EEPROM layout
    of the images follows Dmitry Grinberg's published EEPROM map.
    GPL-3.0-or-later, see PwCommon.h.
*/

#ifndef POKEWALKER_WALKERDEVICE_H
#define POKEWALKER_WALKERDEVICE_H

#include <array>
#include <deque>
#include <functional>
#include <string>
#include <vector>

#include "PwCommon.h"
#include "VirtualPokewalker.h"

namespace pokewalker
{

class WalkerDevice
{
public:
    static constexpr int Width = 96;
    static constexpr int Height = 64;

    enum class Button { Left, Center, Right };
    enum class Sound : u8 { None = 0, Move, Select, Back, Good, Bad, Caught };

    using Random32 = std::function<u32()>;
    using LocalMinutes = std::function<int()>;  // minutes since local midnight

    WalkerDevice(VirtualPokewalker& walker, Random32 random, LocalMinutes clock);

    /// Back to the home screen (when the screen is opened).
    void Reset(s64 nowMs);
    void Press(Button button, s64 nowMs);
    /// Advances timers and redraws. Returns true when the picture changed.
    bool Update(s64 nowMs);

    /// Shades 0 (lightest) to 3 (darkest), row by row.
    const std::array<u8, Width * Height>& Frame() const { return frame; }
    /// The most recent sound to play since the last call (None if nothing).
    Sound TakeSound();
    /// From the walker's settings byte: 0 off, 1 low, 2 high.
    int Volume() const { return volume; }
    /// From the walker's settings byte: 0..9 (lighter to darker).
    int Shade() const { return shade; }
    /// For tests and logs.
    const char* ScreenName() const;

private:
    enum class Screen { Home, Menu, Radar, Battle, Switch, Dowsing, Connect, TrainerCard, Inventory, Settings, Message, Busy };
    enum class RadarPhase { Waiting, Bubble, Click, Cloud };

    // One line of a message: an image from the walker (80 or 96 wide, 16 tall)
    // or text in our own font.
    struct Line
    {
        u32 addr = 0;
        int width = 0;
        std::string text;
        Line() = default;
        Line(u32 a, int w) : addr(a), width(w) {}
        Line(u32 a, int w, std::string t) : addr(a), width(w), text(std::move(t)) {}
    };
    struct Message
    {
        Line top, bottom;
        int ms = 1400;
        std::function<void()> after; // runs when the message is dismissed
    };

    VirtualPokewalker& walker;
    Random32 rng;
    LocalMinutes localMinutes;

    std::array<u8, Width * Height> frame {};
    std::array<u8, Width * Height> shown {};
    bool firstFrame = true;
    Sound sound = Sound::None;
    int volume = 2;
    int shade = 4;

    VirtualPokewalker::Snapshot snap;
    s64 snapAt = -1000000;
    s64 now = 0;

    Screen screen = Screen::Home;
    Screen returnTo = Screen::Menu;
    int menuIndex = 0;
    s64 homeTalkUntil = 0;
    int homeTalkFace = 0;

    // messages shown over the current screen (battle, dowsing) or on their own
    std::deque<Message> messages;
    s64 messageUntil = 0;
    std::vector<std::string> noticeText; // own-font notice on the message screen

    // Poké Radar
    int radarSlot = 0;
    int radarRounds = 1;
    int radarRound = 1;
    int radarCursor = 0;
    int radarBush = -1;
    int radarMisses = 0;
    RadarPhase radarPhase = RadarPhase::Waiting;
    s64 radarAt = 0;

    // battle
    int ourHp = 4, theirHp = 4;
    bool choosing = false;
    bool flashOurs = false, flashTheirs = false; // flash the sprite that was hit
    s64 flashUntil = 0;
    bool throwing = false;

    // switch? (catch or find with all three slots full)
    bool switchItems = false;
    int switchCursor = 0;
    int switchRouteSlot = 0;   // Pokémon caught (route slot)
    u16 switchItem = 0;        // item found

    // dowsing
    u16 dowseItem = 0;
    int dowseIndex = -1;
    int dowseHidden = 0;
    int dowseCursor = 0;
    int dowseTries = 2;
    bool dowseSearched[6] {};
    u32 dowseHint = 0;

    int cardPage = 0;
    int inventoryCursor = 0;
    int settingsRow = 0;
    int pendingVolume = 2, pendingShade = 4;
    s64 screenUntil = 0;

    // ---- state -------------------------------------------------------------------------
    void RefreshSnapshot(bool force);
    void Go(Screen next);
    void Play(Sound s) { sound = s; }
    u32 Rand(u32 n) { return n ? rng() % n : 0; }

    void Queue(Message m);
    void NextMessage();
    bool MessageActive() const { return !messages.empty(); }
    void Notice(Line top, Line bottom, Screen then, int ms = 2000);
    void NoticeText(const std::string& text, Screen then);

    void EnterApp(int index);
    void StartRadar();
    void RadarPress();
    void RadarTick();
    void StartBattle();
    void BattleChoose(int action); // 0 attack, 1 evade, 2 catch
    void Hit(bool ours, int damage);
    void AfterTurn();
    void EndBattle();
    void Caught();
    void StartDowsing();
    void DowsingPress();
    void Dowsed();
    void SwitchConfirm();
    void SaveSettings();

    // ---- what the walker holds ------------------------------------------------------------
    int RouteSlotOfSpecies(u16 species) const;
    int RouteIndexOfItem(u16 item) const;
    u16 CaughtSpecies(int i) const;
    u16 DowsedItem(int i) const;
    u16 GiftedItem(int i) const;
    int HeldCount(bool items) const;
    struct Entry
    {
        enum Kind { Walker, Caught, Dowsed, Gifted } kind;
        int index;
        u16 id;
    };
    std::vector<Entry> InventoryEntries() const;

    // ---- drawing -------------------------------------------------------------------------
    void Draw();
    void DrawHome();
    void DrawMenu();
    void DrawRadar();
    void DrawBattle();
    void DrawSwitch();
    void DrawDowsing();
    void DrawConnect();
    void DrawTrainerCard();
    void DrawInventory();
    void DrawSettings();
    void DrawBusy();
    void DrawMessageArea(int y);
    void DrawLine(const Line& line, int y);

    void Clear(u8 v = 0);
    void Pixel(int x, int y, u8 v);
    void Fill(int x, int y, int w, int h, u8 v);
    void Blit(int x, int y, int w, int h, u32 addr, bool transparent = false);
    int Number(u32 n, int rightX, int y);        // right-aligned digits, returns the left x
    void Digit(int x, int y, int index);         // 0-9, 10 ':', 11 '-', 12 '/'
    void Text(int x, int y, const std::string& s, u8 v = 3);
    void TextCentered(int y, const std::string& s, u8 v = 3);
    void Arrow(int x, int y, int direction);     // 0 up, 1 down, 2 left, 3 right
    void SmallSprite(int x, int y, u32 base, bool transparent = false);
};

}

#endif
