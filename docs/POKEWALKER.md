# Pokéwalker support (AYN Thor and other Android devices)

This build of melonDS Android adds the infrared chip in the Pokémon
HeartGold / SoulSilver cartridge, plus a **virtual Pokéwalker** built into the
app. Your phone's step count (Samsung Health or any fitness app) can then turn
into Pokéwalker steps and watts.

## Install

On the Thor, open the **Releases** page of your fork and find **melonDS
Pokéwalker (latest build)**. It has two APKs; pick one.

**Next to your normal melonDS:** `melonDS-Pokewalker-arm64.apk`

- It installs as a separate app, **melonDS Pokéwalker**.
- Point it at the same ROM folder.
- Use one app at a time with a game, because both apps read the same save file
  next to the ROM.

**In place of your normal melonDS:** `melonDS-Pokewalker-replaces-melonDS-arm64.apk`

This one uses the official app's id (`me.magnum.melonds`). Android only installs
an update when it's signed with the same key as the installed app, and only the
melonDS developer has the official key. So it can't install over the official
app as an update; you swap once:

1. In melonDS, open *Settings → Save Files*. Game saves are next to your ROMs
   or in the folder picked there, so they survive the swap. Save states do too,
   unless *Save state location* is "Internal directory": those live inside the
   app and are removed with it. To be safe, also copy your `.sav` and
   save-state files somewhere else.
2. Uninstall the official melonDS. Its settings go with it: ROM folders,
   controls, layouts, BIOS/firmware choices and cheats.
3. Install the replaces-melonDS APK, pick your ROM folder again, and redo any
   settings you had changed.

From then on, each new build of that APK installs over the last one like a
normal update, keeping everything. Going back to the official melonDS works
the same way: uninstall this one, then install the official app.

Open the APK and allow installing from your browser if Android asks.

## Before you start: the game's own rules

HeartGold and SoulSilver check your PC as soon as you pick **Pokéwalker** on the
main menu. If either rule isn't met, the game shows a short "Welcome!…" message
and goes straight back to the title screen. A real DS does exactly the same.

- At least **one Pokémon in a PC Box**. Pokémon in your party don't count.
- At least **five empty spaces** in the PC Boxes.

Deposit a Pokémon, save, and try again.

## Walk a Pokémon

1. Load HeartGold or SoulSilver and pick **Pokéwalker** on the game's main menu
   (the menu after the title screen).
2. Choose **Go for a Stroll**, pick a Pokémon from your Box and a route, then
   *Save, then go for a Stroll*. The virtual Pokéwalker answers by itself; there
   is no button to press. The first time, the game registers it to your game.
3. Close the game and open **Settings → Pokéwalker**. You'll see your Pokémon,
   the route, today's steps and watts.

## Get your steps in

Every 20 steps is 1 watt, like the real device. Only steps taken after the
stroll started count. Syncing twice never counts a step twice: each day is
credited once, with the highest number seen.

| Way | What to do | Notes |
|---|---|---|
| **Health Connect** | Settings → Pokéwalker → *Sync steps from Health Connect* | Reads the steps stored on *this* device. On the Thor, install Samsung Health (or Fitbit / Google Fit), sign in, and let it share steps with Health Connect. Samsung Health then pulls your phone's steps from your Samsung account (open it and pull down to refresh if they lag). |
| **Samsung Health export** | On your phone: Samsung Health → Settings → *Download personal data*. Copy the zip to the Thor (Quick Share, Google Drive, USB). Then *Import a step export*. | Works offline. Import a newer export any time; already-counted steps are skipped. Any CSV with `date` and `steps` columns works too. |
| **Manual** | *Add steps manually* | Type the number your phone shows. |

## Poké Radar and dowsing

Spend watts like on the real Pokéwalker, in Settings → Pokéwalker → *On the Pokéwalker*:

- **Poké Radar (10 W):** a route Pokémon appears. Which one follows the walker's
  own rules: the rarer ones need more steps *today*. Throw a Poké Ball to catch
  it. Rarer ones get away more often. Up to 3 per stroll.
- **Dowsing (3 W):** pick one of six grass patches, two tries, with a hint after
  a miss. Up to 3 items per stroll.

Everything you catch or find comes back to the game when the Pokémon returns.

## Bring the Pokémon home

On the game's Pokéwalker menu choose **Return from a Stroll**. The game receives
the steps (the Pokémon gets experience) and the watts. Anything caught or found
on the walker comes back too, as on the real device.

## Messages from the game

| Message | Meaning |
|---|---|
| "Please come back once you have placed at least one Pokémon in a PC Box" / "…opened at least five spaces" | See *Before you start*. |
| "This Pokéwalker has already been registered." | The virtual walker belongs to another save. Use Settings → Pokéwalker → *Reset virtual Pokéwalker*, or the game's *Erase Pokéwalker records*. |
| "This Pokéwalker is not the registered Pokéwalker." | It was registered by another game or trainer, for example HeartGold vs SoulSilver. |
| "There is already a Pokémon on this Pokéwalker." | Return the Pokémon first. |
| "Connection failure." | Turn on *Log IR packets*, try again, and use *Export log* (see Troubleshooting). |

## Using a real Pokéwalker or another emulator

- **Your real walker's data:** dump its EEPROM (for example with the pwsync
  tool and a Raspberry Pi Pico IR bridge) and use *Import walker data*.
  *Export walker data* writes the same 64 KB format, which UnrealPowerz's
  online Pokéwalker EEPROM viewer can open.
- **TCP mode:** *Infrared mode → TCP* opens port 8081 on the Thor. Walker
  emulators that speak melonDS-IR's TCP protocol (for example
  `picowalker-sdl-tcp`) can connect to it over Wi-Fi.

## Limits

- Poké Radar and dowsing are simplified versions: one Poké Ball throw instead
  of the attack/evade battle, and a list of grass patches instead of the
  walker's screen. They use the same encounter rules and storage, so the game
  imports the results normally.
- Special event routes and event Pokémon from Nintendo distributions aren't
  supported.
- Only HeartGold and SoulSilver get the virtual walker. Other IR games
  (Black/White, B2/W2) are left alone.
- The Health Connect route depends on another app putting your phone's steps
  on the Thor. If that doesn't work, use the export.
- The in-app update check is turned off in this build (it would offer official
  builds, which can't install over it). Get new Pokéwalker builds from your
  fork's Releases page.
- The virtual Pokéwalker's data belongs to each app. To move it between the
  side-by-side and replacing APKs, use *Export walker data* and *Import walker
  data*.

## Troubleshooting

Turn on *Log IR packets*, try again, then use *Export log*. The log shows each
connection and every packet the game and the walker exchanged.

## How it works (for developers)

- `melonDS-android-lib/src/NDSCart.cpp`: `CartRetailIR` now implements the IR
  chip's SPI commands `0x01` (receive) and `0x02` (send). Bytes go through
  three new `Platform::IR_*` hooks.
- `app/src/main/cpp/pokewalker/`
  - `PlatformIR.cpp`, `PokewalkerIR.cpp`: the hooks; route to Off / Virtual / TCP
  - `VirtualPokewalker.cpp`: walker side of the protocol (handshake, session id,
    checksums, EEPROM read/write incl. LZ77, pairing resets, stroll start/end)
    and the step/watt/day model. State is a real-format 64 KiB EEPROM image,
    written through to disk packet by packet.
  - `PokewalkerJNI.cpp`: bindings for `me.magnum.melonds.pokewalker.PokewalkerNative`
- `app/src/main/java/me/magnum/melonds/pokewalker/`: Health Connect, export
  import, the per-day step ledger. The screen is
  `ui/settings/fragments/PokewalkerPreferencesFragment.kt`.
- `pokewalker-tests/`: host tests (see its README). `hgss_flow_test` replays
  every Pokéwalker flow the game has (register, stroll, return, gifts, erase),
  with the game's own checks, sizes and 100 ms reply deadline. It is modelled
  on the HeartGold/SoulSilver decompilation (pret/pokeheartgold, overlay 112
  and libirc).
- `.github/workflows/melonds-pokewalker.yaml` builds the APK and publishes it.

Protocol facts come from public research: Dmitry Grinberg's *PokéWalker
hacking*, mamba2410's reverse-pokewalker and picowalker, the melonDS-IR work by
DaveuRrr and Ceravia, and the pret/pokeheartgold decompilation. The virtual
walker is an independent implementation and contains no Nintendo code or data.
