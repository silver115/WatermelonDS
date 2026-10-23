/*
    The virtual Pokéwalker's own screen. GPL-3.0-or-later, see PwCommon.h.

    Images are read straight from the walker's EEPROM image, where the game put
    them at pairing and walk start (2 bits per pixel, 8-pixel tall strips: for
    each column, one byte of high bits then one of low bits, bit 0 = top row,
    0 = lightest shade).
*/

#include "WalkerDevice.h"

#include <algorithm>
#include <cstdio>
#include <cstring>

#include "PixelFont.h"
#include "PwText.h"

namespace pokewalker
{

namespace
{

// Where the game stores each picture in the walker (Dmitry Grinberg's EEPROM map).
namespace img
{
constexpr u32 Digits = 0x0280;           // "0123456789:-/", 8x16 each
constexpr u32 Watts = 0x0420;            // 16x16
constexpr u32 Ball = 0x0460;             // 8x8
constexpr u32 Item = 0x0488;             // 8x8
constexpr u32 Arrows = 0x04F8;           // up, down, left, right x (normal, offset, inverted), 8x8
constexpr u32 MenuArrowLeft = 0x05B8;    // 8x16
constexpr u32 MenuArrowRight = 0x05D8;   // 8x16
constexpr u32 MenuReturn = 0x05F8;       // 8x16
constexpr u32 TalkFaces = 0x0670;        // 6 x 24x16
constexpr u32 MenuTitles = 0x0910;       // 6 x 80x16
constexpr u32 MenuIcons = 0x1090;        // 6 x 16x16
constexpr u32 Person = 0x1210;           // 16x16
constexpr u32 TrainerName = 0x1250;      // 80x16
constexpr u32 RouteSmall = 0x1390;       // 16x16
constexpr u32 StepsFrame = 0x13D0;       // 40x16
constexpr u32 TimeFrame = 0x1470;        // 32x16
constexpr u32 DaysFrame = 0x14F0;        // 40x16
constexpr u32 TotalDaysFrame = 0x1590;   // 64x16
constexpr u32 SoundFrame = 0x1690;       // 40x16
constexpr u32 ShadeFrame = 0x1730;       // 40x16
constexpr u32 Speakers = 0x17D0;         // off, low, high: 3 x 24x16
constexpr u32 ContrastBar = 0x18F0;      // 8x16
constexpr u32 TreasureLarge = 0x1910;    // 32x24
constexpr u32 PresentLarge = 0x1A90;     // 32x24
constexpr u32 DowsingBushDark = 0x1B50;  // 16x16
constexpr u32 DowsingBushLight = 0x1B90; // 16x16
constexpr u32 RadarBush = 0x1CB0;        // 32x24
constexpr u32 RadarBubbles = 0x1D70;     // "!", "!!", "!!!": 3 x 16x16
constexpr u32 RadarClick = 0x1E30;       // 16x16
constexpr u32 RadarCloud = 0x1F70;       // 32x24
constexpr u32 RadarHp = 0x2030;          // 8x8
constexpr u32 RadarCatchStar = 0x2040;   // 8x8
constexpr u32 RadarActions = 0x2050;     // attack / evade / catch placard, 96x32
constexpr u32 WalkerBig = 0x2350;        // 32x32
constexpr u32 IrArcs = 0x2450;           // 8x16
// texts, 96x16
constexpr u32 Connecting = 0x2530;
constexpr u32 NeedWatts = 0x4330;
constexpr u32 NoPokemonHeld = 0x44B0;
constexpr u32 NothingHeld = 0x4630;
constexpr u32 DiscoverItem = 0x47B0;
constexpr u32 Found = 0x4930;
constexpr u32 NothingFound = 0x4AB0;
constexpr u32 ItsNear = 0x4C30;
constexpr u32 FarAway = 0x4DB0;
constexpr u32 FindPokemon = 0x4F30;
constexpr u32 GotAway = 0x5230;
constexpr u32 Appeared = 0x53B0;
constexpr u32 WasCaught = 0x5530;
constexpr u32 Fled = 0x56B0;
constexpr u32 TooStrong = 0x5830;
constexpr u32 Attacked = 0x59B0;
constexpr u32 Evaded = 0x5B30;
constexpr u32 CriticalHit = 0x5CB0;
constexpr u32 ThrewBall = 0x5FB0;
constexpr u32 AlmostHad = 0x6130;
constexpr u32 StareDown = 0x62B0;
constexpr u32 SwitchText = 0x8B30;       // "switch?", 80x16
// route data, written at walk start
constexpr u32 RouteLarge = 0x8FBE;       // 32x24
constexpr u32 RouteName = 0x907E;        // 80x16
constexpr u32 WalkerSmall = 0x91BE;      // 2 frames x 32x24
constexpr u32 WalkerLarge = 0x933E;      // 2 frames x 64x48
constexpr u32 WalkerName = 0x993E;       // 80x16
constexpr u32 RouteMonSmall = 0x9A7E;    // 3 Pokémon x 2 frames x 32x24
constexpr u32 RouteMonNames = 0xA4FE;    // 3 x 80x16
constexpr u32 ItemNames = 0xA8BE;        // 10 x 96x16
}

constexpr u32 SmallFrameBytes = 32 * 24 / 4;
constexpr u32 LargeFrameBytes = 64 * 48 / 4;
constexpr u32 NameBytes = 80 * 16 / 4;
constexpr u32 TextBytes = 96 * 16 / 4;
constexpr int AnimationMs = 500;

enum ArrowDir { Up = 0, Down = 1, LeftDir = 2, RightDir = 3 };

bool Empty(u16 v) { return v == 0 || v == 0xFFFF; }

std::string Upper(std::string s)
{
    for (char& c : s)
        if (c >= 'a' && c <= 'z') c = (char)(c - 'a' + 'A');
    return s;
}

std::vector<std::string> Wrap(const std::string& text, size_t width)
{
    std::vector<std::string> lines;
    std::string line, word;
    auto flushWord = [&]() {
        if (word.empty()) return;
        if (!line.empty() && line.size() + 1 + word.size() > width)
        {
            lines.push_back(line);
            line.clear();
        }
        while (word.size() > width)
        {
            lines.push_back(word.substr(0, width));
            word = word.substr(width);
        }
        line += (line.empty() ? "" : " ") + word;
        word.clear();
    };
    for (char c : text)
    {
        if (c == ' ') flushWord();
        else word += c;
    }
    flushWord();
    if (!line.empty()) lines.push_back(line);
    return lines;
}

}

WalkerDevice::WalkerDevice(VirtualPokewalker& walker, Random32 random, LocalMinutes clock)
    : walker(walker), rng(std::move(random)), localMinutes(std::move(clock))
{
}

const char* WalkerDevice::ScreenName() const
{
    switch (screen)
    {
    case Screen::Home: return "home";
    case Screen::Menu: return "menu";
    case Screen::Radar: return "radar";
    case Screen::Battle: return "battle";
    case Screen::Switch: return "switch";
    case Screen::Dowsing: return "dowsing";
    case Screen::Connect: return "connect";
    case Screen::TrainerCard: return "trainer card";
    case Screen::Inventory: return "inventory";
    case Screen::Settings: return "settings";
    case Screen::Message: return "message";
    case Screen::Busy: return "busy";
    }
    return "?";
}

WalkerDevice::Sound WalkerDevice::TakeSound()
{
    Sound s = sound;
    sound = Sound::None;
    return s;
}

u16 WalkerDevice::TakeCry()
{
    u16 c = cry;
    cry = 0;
    return c;
}

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

void WalkerDevice::RefreshSnapshot(bool force)
{
    if (!force && now - snapAt < 1000 && now >= snapAt) return;
    snap = walker.GetSnapshot();
    snapAt = now;
    if (screen != Screen::Settings)
    {
        volume = std::min(2, (snap.settings >> 1) & 3);
        shade = std::min(9, (snap.settings >> 3) & 0xF);
    }
}

void WalkerDevice::Go(Screen next)
{
    screen = next;
    screenUntil = 0;
}

void WalkerDevice::Reset(s64 nowMs)
{
    now = nowMs;
    messages.clear();
    noticeText.clear();
    RefreshSnapshot(true);
    Go(snap.session ? Screen::Busy : Screen::Home);
    firstFrame = true;
}

void WalkerDevice::Queue(Message m)
{
    messages.push_back(std::move(m));
    if (messages.size() == 1) messageUntil = now + messages.front().ms;
}

void WalkerDevice::NextMessage()
{
    if (messages.empty()) return;
    Message done = std::move(messages.front());
    messages.pop_front();
    if (done.after) done.after();
    if (!messages.empty()) messageUntil = now + messages.front().ms;
}

void WalkerDevice::Notice(Line top, Line bottom, Screen then, int ms)
{
    messages.clear();
    noticeText.clear();
    Go(Screen::Message);
    Queue({std::move(top), std::move(bottom), ms, [this, then]() { Go(then); }});
}

void WalkerDevice::NoticeText(const std::string& text, Screen then)
{
    Notice({}, {}, then, 3200);
    noticeText = Wrap(Upper(text), 16);
}

void WalkerDevice::Press(Button button, s64 nowMs)
{
    now = nowMs;
    RefreshSnapshot(false);
    if (screen == Screen::Busy) return;

    if (!messages.empty())
    {
        if (button == Button::Center)
        {
            Play(Sound::Select);
            NextMessage();
        }
        return;
    }

    switch (screen)
    {
    case Screen::Home:
        if (!snap.registered) return;
        if (button == Button::Center)
        {
            if (snap.hasPokemon)
            {
                homeTalkFace = (int)Rand(6);
                homeTalkUntil = now + 2500;
                Play(Sound::Good);
                Cry(WalkingSpecies());
            }
            return;
        }
        menuIndex = button == Button::Left ? 5 : 0;
        Play(Sound::Move);
        Go(Screen::Menu);
        return;

    case Screen::Menu:
        if (button == Button::Center)
        {
            Play(Sound::Select);
            EnterApp(menuIndex);
            return;
        }
        Play(Sound::Move);
        if (button == Button::Left)
        {
            if (menuIndex == 0) Go(Screen::Home);
            else menuIndex--;
        }
        else
        {
            if (menuIndex == 5) Go(Screen::Home);
            else menuIndex++;
        }
        return;

    case Screen::Radar:
        if (button == Button::Center) RadarPress();
        else
        {
            radarCursor = (radarCursor + (button == Button::Left ? 3 : 1)) % 4;
            Play(Sound::Move);
        }
        return;

    case Screen::Battle:
        if (!choosing) return;
        BattleChoose(button == Button::Left ? 0 : button == Button::Right ? 1 : 2);
        return;

    case Screen::Switch:
        if (button == Button::Center) SwitchConfirm();
        else
        {
            switchCursor = (switchCursor + (button == Button::Left ? 3 : 1)) % 4;
            Play(Sound::Move);
        }
        return;

    case Screen::Dowsing:
        if (button == Button::Center) DowsingPress();
        else
        {
            dowseCursor = (dowseCursor + (button == Button::Left ? 5 : 1)) % 6;
            Play(Sound::Move);
        }
        return;

    case Screen::Connect:
        Play(Sound::Back);
        Go(Screen::Menu);
        return;

    case Screen::TrainerCard:
        if (button == Button::Center)
        {
            Play(Sound::Back);
            Go(Screen::Menu);
            return;
        }
        Play(Sound::Move);
        if (button == Button::Left)
        {
            if (cardPage == 0) Go(Screen::Menu);
            else cardPage--;
        }
        else
        {
            if (cardPage == 8) Go(Screen::Menu);
            else cardPage++;
        }
        return;

    case Screen::Inventory:
    {
        if (button == Button::Center)
        {
            Play(Sound::Back);
            Go(Screen::Menu);
            return;
        }
        int count = (int)InventoryEntries().size();
        if (count == 0) return;
        inventoryCursor = (inventoryCursor + (button == Button::Left ? count - 1 : 1)) % count;
        Play(Sound::Move);
        return;
    }

    case Screen::Settings:
        if (button == Button::Center)
        {
            Play(Sound::Select);
            if (settingsRow < 2) settingsRow++;
            else
            {
                SaveSettings();
                Go(Screen::Menu);
            }
            return;
        }
        if (settingsRow == 0)
            pendingVolume = std::clamp(pendingVolume + (button == Button::Left ? -1 : 1), 0, 2);
        else if (settingsRow == 1)
            pendingShade = std::clamp(pendingShade + (button == Button::Left ? -1 : 1), 0, 9);
        volume = pendingVolume;
        shade = pendingShade;
        Play(Sound::Move);
        return;

    case Screen::Message:
    case Screen::Busy:
        return;
    }
}

bool WalkerDevice::Update(s64 nowMs)
{
    now = nowMs;
    RefreshSnapshot(false);

    if (snap.session && screen != Screen::Busy)
    {
        messages.clear();
        Go(Screen::Busy);
    }
    else if (!snap.session && screen == Screen::Busy)
        Go(Screen::Home);

    if (!messages.empty() && now >= messageUntil) NextMessage();
    if (messages.empty())
    {
        if (screen == Screen::Radar) RadarTick();
        if (screen == Screen::Connect && screenUntil && now >= screenUntil) Go(Screen::Menu);
    }

    Draw();
    bool changed = firstFrame || frame != shown;
    shown = frame;
    firstFrame = false;
    return changed;
}

void WalkerDevice::OpenApp(int index, s64 nowMs)
{
    now = nowMs;
    RefreshSnapshot(true);
    if (!snap.registered || snap.session || index < 0 || index > 5) return;
    messages.clear();
    noticeText.clear();
    menuIndex = index;
    Go(Screen::Menu);
    EnterApp(index);
}

bool WalkerDevice::Touch(int x, int y, s64 nowMs)
{
    now = nowMs;
    RefreshSnapshot(false);
    if (x < 0 || x >= Width || y < 0 || y >= Height || screen == Screen::Busy) return false;

    auto press = [&](Button b) {
        Press(b, nowMs);
        return true;
    };
    // the button for that third of the screen
    Button third = x < 32 ? Button::Left : x >= 64 ? Button::Right : Button::Center;

    // a message on screen: a tap moves it on, as the middle button does
    if (!messages.empty()) return press(Button::Center);

    switch (screen)
    {
    case Screen::Home:
        if (!snap.registered) return false;
        if (snap.hasPokemon && x < 64 && y < 48) return press(Button::Center); // the Pokémon reacts
        return press(Button::Right);                                           // the menu

    case Screen::Menu:
        if (y < 16) return press(x < 16 ? Button::Left : x >= 80 ? Button::Right : Button::Center);
        if (y < 48)
        {
            // an icon: the first tap picks it (shows its name and cost), a second opens it
            int i = std::clamp(x / 16, 0, 5);
            if (i == menuIndex) return press(Button::Center);
            menuIndex = i;
            Play(Sound::Move);
            return true;
        }
        return false;

    case Screen::Radar:
    {
        if (radarPhase == RadarPhase::Click || radarPhase == RadarPhase::Cloud) return false;
        static const int bx[4] = {0, 64, 0, 64};
        static const int by[4] = {0, 0, 24, 24};
        for (int i = 0; i < 4; i++)
            if (x >= bx[i] && x < bx[i] + 32 && y >= by[i] && y < by[i] + 24)
            {
                radarCursor = i;
                RadarPress();
                return true;
            }
        return false;
    }

    case Screen::Battle:
        if (!choosing) return false;
        return press(third); // attack / catch / evade, as on the placard

    case Screen::Switch:
        if (y >= 44 && x >= 64)
        {
            switchCursor = 3; // keep what is held
            SwitchConfirm();
            return true;
        }
        if (y >= 16 && y < 44)
        {
            // replacing one is for keeps: the first tap picks it, a second confirms
            int i = std::clamp(x / 32, 0, 2);
            if (switchCursor == i)
            {
                SwitchConfirm();
                return true;
            }
            switchCursor = i;
            Play(Sound::Move);
            if (!switchItems) Cry(CaughtSpecies(i));
            return true;
        }
        return false;

    case Screen::Dowsing:
        if (y >= 8 && y < 40)
        {
            dowseCursor = std::clamp(x / 16, 0, 5);
            DowsingPress();
            return true;
        }
        return false;

    case Screen::Connect:
        return press(Button::Center);

    case Screen::TrainerCard:
        return press(third); // previous page / back / next page

    case Screen::Inventory:
    {
        std::vector<Entry> list = InventoryEntries();
        if (y < 16)
        {
            int i = (x - 3) / 9;
            if (x < 3 || i >= (int)list.size()) return false;
            inventoryCursor = i;
            Play(Sound::Move);
            return true;
        }
        // the Pokémon shown: its cry
        if (!list.empty() && x >= 30 && x < 66 && y >= 14 && y < 40)
        {
            const Entry& e = list[std::min(inventoryCursor, (int)list.size() - 1)];
            if (e.kind == Entry::Walker || e.kind == Entry::Caught)
            {
                Cry(e.id);
                return true;
            }
        }
        return press(third);
    }

    case Screen::Settings:
        if (y < 20)
        {
            settingsRow = 0;
            pendingVolume = (pendingVolume + 1) % 3; // off, low, high, off...
            volume = pendingVolume;
            Play(Sound::Move);
            return true;
        }
        if (y < 44)
        {
            settingsRow = 1;
            pendingShade = std::clamp(pendingShade + (x < 52 ? -1 : 1), 0, 9);
            shade = pendingShade;
            Play(Sound::Move);
            return true;
        }
        if (x >= 64)
        {
            settingsRow = 2; // the return arrow: save and leave
            return press(Button::Center);
        }
        return false;

    case Screen::Message:
    case Screen::Busy:
        return false;
    }
    return false;
}

void WalkerDevice::EnterApp(int index)
{
    switch (index)
    {
    case 0: StartRadar(); break;
    case 1: StartDowsing(); break;
    case 2:
        Go(Screen::Connect);
        screenUntil = now + 8000;
        break;
    case 3:
        cardPage = 0;
        Go(Screen::TrainerCard);
        break;
    case 4:
        inventoryCursor = 0;
        Go(Screen::Inventory);
        break;
    default:
        settingsRow = 0;
        pendingVolume = volume;
        pendingShade = shade;
        Go(Screen::Settings);
        break;
    }
}

// ---------------------------------------------------------------------------
// Poké Radar and its battle
// ---------------------------------------------------------------------------

void WalkerDevice::StartRadar()
{
    if (!snap.hasPokemon) { Notice({img::NoPokemonHeld, 96}, {}, Screen::Menu); return; }
    if (snap.watts < VirtualPokewalker::RadarCost) { Notice({img::NeedWatts, 96}, {}, Screen::Menu); return; }
    int slot = 0;
    std::string json, error;
    if (!walker.RadarEncounter(&slot, &json, &error, true))
    {
        NoticeText(error, Screen::Menu);
        return;
    }
    RefreshSnapshot(true);
    radarSlot = slot;
    radarRounds = slot + 1; // rarer route Pokémon take more rounds of "!"
    radarRound = 1;
    radarCursor = 0;
    radarBush = -1;
    radarMisses = 0;
    radarPhase = RadarPhase::Waiting;
    radarAt = now + 1000 + Rand(1500);
    Go(Screen::Radar);
}

void WalkerDevice::RadarTick()
{
    if (now < radarAt) return;
    switch (radarPhase)
    {
    case RadarPhase::Waiting:
        radarBush = (int)Rand(4);
        radarPhase = RadarPhase::Bubble;
        radarAt = now + (radarRound == 1 ? 1500 : radarRound == 2 ? 1200 : 900);
        Play(Sound::Move);
        break;
    case RadarPhase::Bubble:
        radarBush = -1;
        if (++radarMisses >= 2)
        {
            Play(Sound::Bad);
            Queue({{}, {img::GotAway, 96}, 2000, [this]() { menuIndex = 0; Go(Screen::Menu); }});
            break;
        }
        radarPhase = RadarPhase::Waiting;
        radarAt = now + 800 + Rand(1200);
        break;
    case RadarPhase::Click:
        if (radarRound > radarRounds)
        {
            radarPhase = RadarPhase::Cloud;
            radarAt = now + 700;
        }
        else
        {
            radarPhase = RadarPhase::Waiting;
            radarAt = now + 700 + Rand(1000);
        }
        break;
    case RadarPhase::Cloud:
        StartBattle();
        break;
    }
}

void WalkerDevice::RadarPress()
{
    if (radarPhase == RadarPhase::Click || radarPhase == RadarPhase::Cloud) return;
    if (radarPhase == RadarPhase::Bubble && radarCursor == radarBush)
    {
        Play(Sound::Good);
        radarRound++;
        radarBush = -1;
        radarPhase = RadarPhase::Click;
        radarAt = now + 450;
        return;
    }
    // A bush that wasn't rustling: whatever was there runs off.
    Play(Sound::Bad);
    radarBush = -1;
    radarPhase = RadarPhase::Waiting;
    radarAt = now + 1000000;
    Queue({{}, {img::GotAway, 96}, 2000, [this]() { menuIndex = 0; Go(Screen::Menu); }});
}

void WalkerDevice::StartBattle()
{
    ourHp = theirHp = 4;
    choosing = false;
    throwing = false;
    flashUntil = 0;
    Go(Screen::Battle);
    Play(Sound::Good);
    Cry(RouteSpecies(radarSlot));
    Queue({{img::RouteMonNames + (u32)radarSlot * NameBytes, 80}, {img::Appeared, 96}, 1600,
           [this]() { choosing = true; }});
}

void WalkerDevice::Hit(bool ours, int damage)
{
    if (ours)
    {
        ourHp = std::max(0, ourHp - damage);
        flashOurs = true;
        flashTheirs = false;
        Play(Sound::Bad);
    }
    else
    {
        theirHp = std::max(0, theirHp - damage);
        flashTheirs = true;
        flashOurs = false;
        Play(Sound::Good);
    }
    flashUntil = now + 600;
}

void WalkerDevice::AfterTurn()
{
    Line theirName {img::RouteMonNames + (u32)radarSlot * NameBytes, 80};
    if (ourHp <= 0)
        Queue({theirName, {img::TooStrong, 96}, 1800, [this]() { EndBattle(); }});
    else if (theirHp <= 0)
        Queue({theirName, {img::Fled, 96}, 1800, [this]() { EndBattle(); }});
    else
        choosing = true;
}

void WalkerDevice::BattleChoose(int action)
{
    choosing = false;
    Line ourName {img::WalkerName, 80};
    Line theirName {img::RouteMonNames + (u32)radarSlot * NameBytes, 80};

    if (action == 2)
    {
        // Catch: the weaker it is, the likelier it stays in the ball.
        Play(Sound::Select);
        throwing = true;
        Queue({{}, {img::ThrewBall, 96}, 1300, [this, theirName]() {
                   throwing = false;
                   static const int chance[4] = {85, 65, 45, 30};
                   if ((int)Rand(100) < chance[std::clamp(theirHp, 1, 4) - 1])
                   {
                       Play(Sound::Caught);
                       Cry(RouteSpecies(radarSlot));
                       Queue({theirName, {img::WasCaught, 96}, 1800, [this]() { Caught(); }});
                   }
                   else
                   {
                       Play(Sound::Bad);
                       Queue({{}, {img::AlmostHad, 96}, 1300, [this, theirName]() {
                                  if (Rand(100) < 25)
                                      Queue({theirName, {img::Fled, 96}, 1800, [this]() { EndBattle(); }});
                                  else
                                      choosing = true;
                              }});
                   }
               }});
        return;
    }

    u32 wild = Rand(100); // 0-49 attack, 50-84 evade, 85-99 something special
    bool weAttack = action == 0;
    if (weAttack && wild < 50)
    {
        Queue({ourName, {img::Attacked, 96}, 1100, [this, theirName]() {
                   Hit(false, 1);
                   if (theirHp > 0)
                       Queue({theirName, {img::Attacked, 96}, 1100, [this]() { Hit(true, 1); AfterTurn(); }});
                   else
                       AfterTurn();
               }});
    }
    else if (weAttack && wild < 85)
    {
        Queue({theirName, {img::Evaded, 96}, 1100, [this, theirName]() {
                   Queue({theirName, {img::Attacked, 96}, 1100, [this]() { Hit(true, 1); AfterTurn(); }});
               }});
    }
    else if (weAttack)
    {
        Queue({{}, {img::CriticalHit, 96}, 1300, [this]() { Hit(false, 2); AfterTurn(); }});
    }
    else if (wild < 50)
    {
        Queue({ourName, {img::Evaded, 96}, 1100, [this, ourName]() {
                   Queue({ourName, {img::Attacked, 96}, 1100, [this]() { Hit(false, 1); AfterTurn(); }});
               }});
    }
    else if (wild < 85)
    {
        Queue({{}, {img::StareDown, 96}, 1300, [this]() { AfterTurn(); }});
    }
    else
    {
        Queue({theirName, {img::Fled, 96}, 1800, [this]() { EndBattle(); }});
    }
}

void WalkerDevice::EndBattle()
{
    menuIndex = 0;
    Go(Screen::Menu);
}

void WalkerDevice::Caught()
{
    if (HeldCount(false) < 3)
    {
        std::string error;
        if (!walker.StoreCaught(radarSlot, &error))
        {
            NoticeText(error, Screen::Menu);
            return;
        }
        RefreshSnapshot(true);
        EndBattle();
        return;
    }
    switchItems = false;
    switchRouteSlot = radarSlot;
    switchCursor = 0;
    Go(Screen::Switch);
}

void WalkerDevice::SwitchConfirm()
{
    std::string error;
    if (switchCursor == 3)
    {
        Play(Sound::Back); // keep what is already held
    }
    else
    {
        bool ok = switchItems ? walker.StoreDowsed(switchItem, &error, switchCursor)
                              : walker.StoreCaught(switchRouteSlot, &error, switchCursor);
        if (!ok)
        {
            NoticeText(error, Screen::Menu);
            return;
        }
        Play(Sound::Good);
        RefreshSnapshot(true);
    }
    menuIndex = switchItems ? 1 : 0;
    Go(Screen::Menu);
}

// ---------------------------------------------------------------------------
// Dowsing
// ---------------------------------------------------------------------------

void WalkerDevice::StartDowsing()
{
    if (!snap.hasPokemon) { Notice({img::NoPokemonHeld, 96}, {}, Screen::Menu); return; }
    if (snap.watts < VirtualPokewalker::DowsingCost) { Notice({img::NeedWatts, 96}, {}, Screen::Menu); return; }
    u16 item = 0;
    std::string json, error;
    if (!walker.DowsingStart(&item, &json, &error, true))
    {
        NoticeText(error, Screen::Menu);
        return;
    }
    RefreshSnapshot(true);
    dowseItem = item;
    dowseIndex = RouteIndexOfItem(item);
    dowseHidden = (int)Rand(6);
    dowseCursor = 0;
    dowseTries = 2;
    std::fill(std::begin(dowseSearched), std::end(dowseSearched), false);
    dowseHint = img::DiscoverItem;
    Go(Screen::Dowsing);
}

void WalkerDevice::DowsingPress()
{
    if (dowseSearched[dowseCursor])
    {
        Play(Sound::Bad);
        return;
    }
    dowseSearched[dowseCursor] = true;
    if (dowseCursor == dowseHidden)
    {
        Play(Sound::Good);
        Line name = dowseIndex >= 0 ? Line{img::ItemNames + (u32)dowseIndex * TextBytes, 96}
                                    : Line{0, 0, Upper(ItemName(dowseItem))};
        Queue({name, {img::Found, 96}, 1800, [this]() { Dowsed(); }});
        return;
    }
    if (--dowseTries <= 0)
    {
        Play(Sound::Bad);
        Queue({{}, {img::NothingFound, 96}, 1800, [this]() { menuIndex = 1; Go(Screen::Menu); }});
        return;
    }
    Play(Sound::Bad);
    dowseHint = std::abs(dowseCursor - dowseHidden) <= 1 ? img::ItsNear : img::FarAway;
}

void WalkerDevice::Dowsed()
{
    if (HeldCount(true) < 3)
    {
        std::string error;
        if (!walker.StoreDowsed(dowseItem, &error))
        {
            NoticeText(error, Screen::Menu);
            return;
        }
        RefreshSnapshot(true);
        menuIndex = 1;
        Go(Screen::Menu);
        return;
    }
    switchItems = true;
    switchItem = dowseItem;
    switchCursor = 0;
    Go(Screen::Switch);
}

void WalkerDevice::SaveSettings()
{
    u8 settings = (u8)((snap.settings & ~0x7E) | (pendingVolume << 1) | (pendingShade << 3));
    std::string error;
    walker.SetSettings(settings, &error);
    RefreshSnapshot(true);
}

// ---------------------------------------------------------------------------
// What the walker holds
// ---------------------------------------------------------------------------

u16 WalkerDevice::WalkingSpecies() const
{
    return rd16le(walker.Image() + ee::RouteInfo + route::Species);
}

u16 WalkerDevice::RouteSpecies(int slot) const
{
    if (slot < 0 || slot > 2) return 0;
    return rd16le(walker.Image() + ee::RouteInfo + route::Pokemon + slot * 16);
}

int WalkerDevice::RouteSlotOfSpecies(u16 species) const
{
    if (Empty(species)) return -1;
    const u8* ee = walker.Image();
    for (int i = 0; i < 3; i++)
        if (rd16le(ee + ee::RouteInfo + route::Pokemon + i * 16) == species) return i;
    return -1;
}

int WalkerDevice::RouteIndexOfItem(u16 item) const
{
    if (Empty(item)) return -1;
    const u8* ee = walker.Image();
    for (int i = 0; i < 10; i++)
        if (rd16le(ee + ee::RouteInfo + route::Items + i * 2) == item) return i;
    return -1;
}

u16 WalkerDevice::CaughtSpecies(int i) const { return rd16le(walker.Image() + ee::Caught + i * 16); }
u16 WalkerDevice::DowsedItem(int i) const { return rd16le(walker.Image() + ee::Dowsed + i * 4); }
u16 WalkerDevice::GiftedItem(int i) const { return rd16le(walker.Image() + ee::Gifted + i * 4); }

int WalkerDevice::HeldCount(bool items) const
{
    int n = 0;
    for (int i = 0; i < 3; i++)
        if (!Empty(items ? DowsedItem(i) : CaughtSpecies(i))) n++;
    return n;
}

std::vector<WalkerDevice::Entry> WalkerDevice::InventoryEntries() const
{
    std::vector<Entry> list;
    if (snap.hasPokemon) list.push_back({Entry::Walker, 0, rd16le(walker.Image() + ee::RouteInfo + route::Species)});
    for (int i = 0; i < ee::CaughtCount; i++)
        if (!Empty(CaughtSpecies(i))) list.push_back({Entry::Caught, i, CaughtSpecies(i)});
    for (int i = 0; i < ee::DowsedCount; i++)
        if (!Empty(DowsedItem(i))) list.push_back({Entry::Dowsed, i, DowsedItem(i)});
    for (int i = 0; i < ee::GiftedCount && list.size() < 10; i++)
        if (!Empty(GiftedItem(i))) list.push_back({Entry::Gifted, i, GiftedItem(i)});
    return list;
}

// ---------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------

void WalkerDevice::Clear(u8 v) { frame.fill(v); }

void WalkerDevice::Pixel(int x, int y, u8 v)
{
    if (x >= 0 && x < Width && y >= 0 && y < Height) frame[y * Width + x] = v;
}

void WalkerDevice::Fill(int x, int y, int w, int h, u8 v)
{
    for (int j = 0; j < h; j++)
        for (int i = 0; i < w; i++) Pixel(x + i, y + j, v);
}

void WalkerDevice::Blit(int x, int y, int w, int h, u32 addr, bool transparent)
{
    const u8* ee = walker.Image();
    int strips = h / 8;
    for (int s = 0; s < strips; s++)
        for (int c = 0; c < w; c++)
        {
            u32 off = addr + (u32)((s * w + c) * 2);
            if (off + 1 >= ee::Size) return;
            u8 hi = ee[off], lo = ee[off + 1];
            for (int j = 0; j < 8; j++)
            {
                u8 v = (u8)((((hi >> j) & 1) << 1) | ((lo >> j) & 1));
                if (transparent && v == 0) continue;
                Pixel(x + c, y + s * 8 + j, v);
            }
        }
}

void WalkerDevice::Digit(int x, int y, int index)
{
    Blit(x, y, 8, 16, img::Digits + (u32)index * 32);
}

int WalkerDevice::Number(u32 n, int rightX, int y)
{
    char buf[16];
    int len = std::snprintf(buf, sizeof(buf), "%u", n);
    int x = rightX - len * 8;
    for (int i = 0; i < len; i++) Digit(x + i * 8, y, buf[i] - '0');
    return x;
}

void WalkerDevice::Text(int x, int y, const std::string& s, u8 v)
{
    for (char ch : s)
    {
        const u8* g = font::Glyph(ch);
        for (int r = 0; r < font::GlyphHeight; r++)
            for (int c = 0; c < font::GlyphWidth; c++)
                if (g[r] & (1 << (font::GlyphWidth - 1 - c))) Pixel(x + c, y + r, v);
        x += font::Advance;
    }
}

void WalkerDevice::TextCentered(int y, const std::string& s, u8 v)
{
    int w = (int)s.size() * font::Advance - 1;
    Text((Width - w) / 2, y, s, v);
}

void WalkerDevice::Arrow(int x, int y, int direction)
{
    Blit(x, y, 8, 8, img::Arrows + (u32)(direction * 3) * 16, true);
}

void WalkerDevice::SmallSprite(int x, int y, u32 base, bool transparent)
{
    u32 f = (u32)((now / AnimationMs) % 2);
    Blit(x, y, 32, 24, base + f * SmallFrameBytes, transparent);
}

void WalkerDevice::DrawLine(const Line& line, int y)
{
    if (line.addr)
        Blit((Width - line.width) / 2, y, line.width, 16, line.addr);
    else if (!line.text.empty())
        TextCentered(y + 4, line.text);
}

void WalkerDevice::DrawMessageArea(int y)
{
    const Message& m = messages.front();
    Fill(0, y, Width, Height - y, 0);
    if (y <= 32) DrawLine(m.top, 32);
    DrawLine(m.bottom, 48);
}

void WalkerDevice::Draw()
{
    Clear();
    switch (screen)
    {
    case Screen::Home: DrawHome(); break;
    case Screen::Menu: DrawMenu(); break;
    case Screen::Radar: DrawRadar(); break;
    case Screen::Battle: DrawBattle(); break;
    case Screen::Switch: DrawSwitch(); break;
    case Screen::Dowsing: DrawDowsing(); break;
    case Screen::Connect: DrawConnect(); break;
    case Screen::TrainerCard: DrawTrainerCard(); break;
    case Screen::Inventory: DrawInventory(); break;
    case Screen::Settings: DrawSettings(); break;
    case Screen::Busy: DrawBusy(); break;
    case Screen::Message:
        if (!noticeText.empty())
        {
            int y = (Height - (int)noticeText.size() * font::LineHeight) / 2;
            for (const std::string& l : noticeText)
            {
                TextCentered(y, l);
                y += font::LineHeight;
            }
        }
        else if (!messages.empty())
        {
            DrawLine(messages.front().top, 16);
            DrawLine(messages.front().bottom, 32);
        }
        break;
    }
}

void WalkerDevice::DrawHome()
{
    if (!snap.registered)
    {
        TextCentered(8, "NOT PAIRED YET");
        TextCentered(24, "IN HEARTGOLD OR");
        TextCentered(32, "SOULSILVER, OPEN");
        TextCentered(40, "POKEWALKER AND");
        TextCentered(48, "GO FOR A STROLL.");
        return;
    }
    if (snap.hasPokemon)
    {
        u32 f = (u32)((now / AnimationMs) % 2);
        Blit(0, 0, 64, 48, img::WalkerLarge + f * LargeFrameBytes);
        Blit(64, 24, 32, 24, img::RouteLarge);
        if (now < homeTalkUntil) Blit(64, 0, 24, 16, img::TalkFaces + (u32)homeTalkFace * 96);
    }
    else
    {
        TextCentered(8, "NO POKEMON IS");
        TextCentered(16, "ON A STROLL.");
        TextCentered(28, "SEND ONE FROM");
        TextCentered(36, "THE GAME.");
    }
    Number(snap.todaySteps, Width, 48);
}

void WalkerDevice::DrawMenu()
{
    Blit(0, 0, 8, 16, img::MenuArrowLeft);
    Blit(8, 0, 80, 16, img::MenuTitles + (u32)menuIndex * NameBytes);
    Blit(88, 0, 8, 16, img::MenuArrowRight);
    for (int i = 0; i < 6; i++) Blit(i * 16, 24, 16, 16, img::MenuIcons + (u32)i * 64);
    Arrow(menuIndex * 16 + 4, 16, Down);
    if (menuIndex <= 1)
    {
        Number(menuIndex == 0 ? VirtualPokewalker::RadarCost : VirtualPokewalker::DowsingCost, 16, 48);
        Blit(16, 48, 16, 16, img::Watts);
    }
    Blit(80, 48, 16, 16, img::Watts);
    Number(snap.watts, 80, 48);
}

void WalkerDevice::DrawRadar()
{
    static const int bx[4] = {0, 64, 0, 64};
    static const int by[4] = {0, 0, 24, 24};
    for (int i = 0; i < 4; i++) Blit(bx[i], by[i], 32, 24, img::RadarBush);
    SmallSprite(32, 12, img::WalkerSmall);
    if (radarPhase == RadarPhase::Bubble && radarBush >= 0)
        Blit(bx[radarBush] + 16, by[radarBush], 16, 16, img::RadarBubbles + (u32)(std::min(radarRound, 3) - 1) * 64, true);
    if (radarPhase == RadarPhase::Click)
        Blit(bx[radarCursor] + 8, by[radarCursor] + 4, 16, 16, img::RadarClick, true);
    if (radarPhase == RadarPhase::Cloud)
        Blit(bx[radarCursor], by[radarCursor], 32, 24, img::RadarCloud);
    else
        Arrow(bx[radarCursor] + 12, by[radarCursor] + 16, Up);
    if (!messages.empty())
        DrawMessageArea(48);
    else
        Blit(0, 48, 96, 16, img::FindPokemon);
}

void WalkerDevice::DrawBattle()
{
    for (int i = 0; i < 4; i++)
    {
        if (i < ourHp) Blit(i * 8, 0, 8, 8, img::RadarHp);
        if (i < theirHp) Blit(64 + i * 8, 0, 8, 8, img::RadarHp);
    }
    bool blink = now < flashUntil && (now / 100) % 2 == 0;
    if (!(blink && flashOurs)) SmallSprite(0, 8, img::WalkerSmall);
    if (!(blink && flashTheirs)) SmallSprite(64, 8, img::RouteMonSmall + (u32)radarSlot * 2 * SmallFrameBytes);
    if (throwing) Blit(40 + (int)((now / 150) % 4) * 6, 12, 8, 8, img::RadarCatchStar, true);
    if (!messages.empty())
        DrawMessageArea(32);
    else if (choosing)
        Blit(0, 32, 96, 32, img::RadarActions);
}

void WalkerDevice::DrawSwitch()
{
    Blit(8, 0, 80, 16, img::SwitchText);
    for (int i = 0; i < 3; i++)
    {
        if (switchItems)
        {
            if (!Empty(DowsedItem(i))) Blit(i * 32, 16, 32, 24, img::TreasureLarge);
        }
        else
        {
            int slot = RouteSlotOfSpecies(CaughtSpecies(i));
            if (slot >= 0) SmallSprite(i * 32, 16, img::RouteMonSmall + (u32)slot * 2 * SmallFrameBytes);
            else Blit(i * 32 + 12, 24, 8, 8, img::Ball);
        }
    }
    if (switchCursor < 3)
    {
        Arrow(switchCursor * 32 + 12, 40, Up);
        if (switchItems)
        {
            int idx = RouteIndexOfItem(DowsedItem(switchCursor));
            if (idx >= 0) Blit(0, 48, 80, 16, img::ItemNames + (u32)idx * TextBytes); // left 80 columns
        }
        else
        {
            int slot = RouteSlotOfSpecies(CaughtSpecies(switchCursor));
            if (slot >= 0) Blit(0, 48, 80, 16, img::RouteMonNames + (u32)slot * NameBytes);
        }
    }
    else
        Arrow(78, 52, RightDir);
    Fill(88, 48, 8, 16, 0);
    Blit(88, 48, 8, 16, img::MenuReturn);
}

void WalkerDevice::DrawDowsing()
{
    for (int i = 0; i < 6; i++)
        Blit(i * 16, 16, 16, 16, dowseSearched[i] ? img::DowsingBushLight : img::DowsingBushDark);
    Arrow(dowseCursor * 16 + 4, 8, Down);
    if (!messages.empty())
    {
        const Message& m = messages.front();
        if (m.top.addr || !m.top.text.empty()) Blit(32, 0, 32, 24, img::TreasureLarge);
        DrawMessageArea(32);
    }
    else
        Blit(0, 48, 96, 16, dowseHint);
}

void WalkerDevice::DrawConnect()
{
    Blit(0, 0, 96, 16, img::Connecting);
    Blit(32, 16, 32, 32, img::WalkerBig);
    if ((now / 400) % 2 == 0) Blit(66, 24, 8, 16, img::IrArcs, true);
    TextCentered(49, "OPEN POKEWALKER");
    TextCentered(57, "IN THE GAME.");
}

void WalkerDevice::DrawTrainerCard()
{
    if (cardPage == 0)
    {
        Blit(0, 0, 16, 16, img::Person);
        Blit(16, 0, 80, 16, img::TrainerName);
        Blit(0, 16, 16, 16, img::RouteSmall);
        Blit(16, 16, 80, 16, img::RouteName);
        Blit(0, 32, 32, 16, img::TimeFrame);
        int minutes = localMinutes ? localMinutes() : 0;
        int h = (minutes / 60) % 24, m = minutes % 60;
        Digit(56, 32, h / 10);
        Digit(64, 32, h % 10);
        Digit(72, 32, 10); // ':'
        Digit(80, 32, m / 10);
        Digit(88, 32, m % 10);
        Blit(0, 48, 64, 16, img::TotalDaysFrame);
        Number(snap.totalDays, Width, 48);
    }
    else if (cardPage == 1)
    {
        Blit(0, 0, 40, 16, img::StepsFrame);
        Text(46, 5, "TODAY");
        Number(snap.todaySteps, Width, 16);
        Text(2, 37, "TOTAL");
        Number(snap.totalSteps, Width, 48);
    }
    else
    {
        int day = cardPage - 1; // 1..7 days ago
        Digit(0, 0, 11);        // '-'
        Digit(8, 0, day);
        Blit(16, 0, 40, 16, img::DaysFrame);
        Blit(0, 32, 40, 16, img::StepsFrame);
        Number(snap.history[day - 1], Width, 48);
    }
}

void WalkerDevice::DrawInventory()
{
    std::vector<Entry> list = InventoryEntries();
    if (list.empty())
    {
        Blit(0, 24, 96, 16, img::NothingHeld);
        return;
    }
    inventoryCursor = std::min(inventoryCursor, (int)list.size() - 1);
    for (size_t i = 0; i < list.size(); i++)
    {
        bool mon = list[i].kind == Entry::Walker || list[i].kind == Entry::Caught;
        Blit(3 + (int)i * 9, 0, 8, 8, mon ? img::Ball : img::Item);
    }
    Arrow(3 + inventoryCursor * 9, 8, Up);

    const Entry& e = list[inventoryCursor];
    switch (e.kind)
    {
    case Entry::Walker:
        SmallSprite(32, 16, img::WalkerSmall);
        Blit(8, 44, 80, 16, img::WalkerName);
        break;
    case Entry::Caught:
    {
        int slot = RouteSlotOfSpecies(e.id);
        if (slot >= 0)
        {
            SmallSprite(32, 16, img::RouteMonSmall + (u32)slot * 2 * SmallFrameBytes);
            Blit(8, 44, 80, 16, img::RouteMonNames + (u32)slot * NameBytes);
        }
        else
        {
            Blit(44, 24, 8, 8, img::Ball);
            TextCentered(48, Upper(SpeciesName(e.id)));
        }
        break;
    }
    case Entry::Dowsed:
    case Entry::Gifted:
    {
        Blit(32, 16, 32, 24, e.kind == Entry::Dowsed ? img::TreasureLarge : img::PresentLarge);
        int idx = RouteIndexOfItem(e.id);
        if (idx >= 0) Blit(0, 44, 96, 16, img::ItemNames + (u32)idx * TextBytes);
        else TextCentered(48, Upper(ItemName(e.id)));
        break;
    }
    }
}

void WalkerDevice::DrawSettings()
{
    Blit(8, 0, 40, 16, img::SoundFrame);
    Blit(56, 0, 24, 16, img::Speakers + (u32)pendingVolume * 96);
    Blit(8, 24, 40, 16, img::ShadeFrame);
    Blit(56, 24, 8, 16, img::ContrastBar);
    Digit(72, 24, pendingShade);
    Blit(88, 48, 8, 16, img::MenuReturn);
    if (settingsRow == 0) Arrow(0, 4, RightDir);
    else if (settingsRow == 1) Arrow(0, 28, RightDir);
    else Arrow(78, 52, RightDir);
}

void WalkerDevice::DrawBusy()
{
    TextCentered(20, "TALKING TO");
    TextCentered(28, "THE GAME...");
    if ((now / 400) % 2 == 0) Blit(44, 40, 8, 16, img::IrArcs, true);
}

}
