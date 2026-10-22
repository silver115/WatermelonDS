# Pokéwalker support (AYN Thor and other Android devices)

This build adds what melonDS has never had on Android: the infrared chip in the
Pokémon HeartGold / SoulSilver cartridge, plus a **virtual Pokéwalker** built
into the app. Your phone's step count (Samsung Health or any fitness app) can
then turn into Pokéwalker steps and watts.

It's built on WatermelonDS (MelonDualDS), so the AYN Thor's two screens work as
usual.

## Install

1. On the Thor, open the **Releases** page of your fork, find **WatermelonDS
   Pokéwalker (latest build)**, and download `WatermelonDS-Pokewalker-arm64.apk`.
2. Open it and allow installing from your browser if Android asks.
3. It installs as **WatermelonDS Pokéwalker**, next to your normal app. Point it
   at the same ROM folder. Use one app at a time with a game, because both apps
   read the same save file next to the ROM.

## Walk a Pokémon

1. Load HeartGold or SoulSilver and open **Pokéwalker** from the game's menu.
2. Choose to send a Pokémon on a stroll. The virtual Pokéwalker answers by
   itself; there is no button to press. The first time, the game pairs it ("This
   Pokéwalker will be registered").
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

## Bring the Pokémon home

In the game choose **return the Pokémon**. The game receives the steps (the
Pokémon gets experience) and the watts. Anything caught or found on the walker
comes back too, as on the real device.

## Using a real Pokéwalker or another emulator

- **Your real walker's data:** dump its EEPROM (for example with the pwsync
  tool and a Raspberry Pi Pico IR bridge) and use *Import walker data*. *Export walker data* writes the same 64 KB format, which UnrealPowerz's
  online Pokéwalker EEPROM viewer can open.
- **TCP mode:** *Infrared mode → TCP* opens port 8081 on the Thor. Walker
  emulators that speak melonDS-IR's TCP protocol (for example
  `picowalker-sdl-tcp`) can connect to it over Wi-Fi.

## Limits

- The Pokéwalker's own mini-games (Poké Radar, dowsing) aren't playable in
  the app yet. Strolls, steps, watts, pairing and returning are.
- Only HeartGold and SoulSilver get the virtual walker. Other IR games
  (Black/White, B2/W2) are left alone.
- The Health Connect route depends on another app putting your phone's steps
  on the Thor. If that doesn't work, use the export.
- The in-app update check is turned off in this build. Get new Pokéwalker
  builds from your fork's Releases page.

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
- `pokewalker-tests/`: host tests (see its README).

Protocol facts come from public research: Dmitry Grinberg's *PokéWalker
hacking*, mamba2410's reverse-pokewalker and picowalker, and the melonDS-IR
work by DaveuRrr and Ceravia. The virtual walker is an independent
implementation and contains no Nintendo code or data.
