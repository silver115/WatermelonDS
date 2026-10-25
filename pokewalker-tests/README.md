# Pokéwalker tests

## walker_test (runs in CI)

A scripted "HeartGold/SoulSilver" plays the game's side of the IR protocol
against the real `VirtualPokewalker` code. It covers checksums against captured
packets, LZ77 round trips, pairing, stroll start (route and team data copied out
of staging), phone steps and watts, stroll return, day rollover and 7-day
history, caps, bad packets, cooldown, cart reset and factory reset.

```sh
cmake -S pokewalker-tests -B build/pokewalker-tests
cmake --build build/pokewalker-tests
./build/pokewalker-tests/walker_test                     # synthetic data only
./build/pokewalker-tests/walker_test my-walker-dump.bin  # plus a real 64 KiB walker EEPROM dump
```

## hgss_flow_test (runs in CI)

Plays the HeartGold/SoulSilver side of every Pokéwalker flow the game has
(register + first stroll, stroll on a registered walker, return, receive gifts,
erase records) against the real `VirtualPokewalker`. The game side is modelled on
the decompiled game (pret/pokeheartgold `asm/overlay_112.s` and `lib/asm/libirc.s`):
single-byte `0xFC` adverts, the `0xF8` reply rules, 4 ms polling, the 100 ms
reply deadline, the exact EEPROM addresses and sizes it writes and reads
(including LZ77-compressed 128-byte chunks), and its identity checks (already
registered, Pokémon present, other game version or trainer).

It also models the game's save (`POKEWALKER.unk_000`: none / paired / out on a stroll,
which picks the flow) to check that the walker and the game get back in step when the
game loses a transfer: rewound after saving ("already registered", "already a Pokémon",
"no Pokémon" — fixed when the player taps CONNECT again), or reset, rewound or closed
before it saved (fixed on the first try), a wrong guess, the other game version, and Unpair.

```sh
./build/pokewalker-tests/hgss_flow_test
```

## rom_check_test (runs in CI)

The HeartGold/SoulSilver copy check (`RomCheck.cpp`): SHA-1 known answers, the
NitroSDK backward-LZ decompressor (against a blob made by the decompilation's
`compstatic`), and finding and decompressing every ARM9 overlay through the
header, overlay table and FAT of a synthetic ROM, including trimmed, padded and
damaged images. The reference hashes are the decompilation's `rom.sha1` and
`main.sha1` for the US releases.

## device_test (runs in CI)

The walker's own screen (`WalkerDevice.cpp`, shown by the Pokéwalker app icon):
a walker image with labelled test pictures at every EEPROM image address, then
the home screen, menu, Poké Radar (rounds of "!", battle, catching, "switch?"),
dowsing (near/far hints), trainer card, Pokémon & items, settings and connect,
checking the drawn pixels against the images and what ends up in the walker.
It also plays every screen by touch (`WalkerDevice::Touch`): tapping bushes,
menu icons, "switch?" slots, settings rows and the battle placard. And it checks
which Pokémon cry when (`WalkerDevice::TakeCry`): the walking one when talked to,
a wild one when it appears and when it is caught, held ones when tapped.

## cry (manual)

`CryArchive.kt` reads a Pokémon's cry from the player's own HeartGold/SoulSilver
ROM (its sound archive). `cry/gen_cry_rom.py` builds a small fake ROM laid out
the same way (file name table, SDAT with INFO/FAT, banks with a plain, key-split
and drum-set instrument, wave archives with PCM8, PCM16 and IMA-ADPCM waves, a
cry sequence with a program change and a transpose) and the samples and rates a
correct reader must produce:

```sh
python3 pokewalker-tests/cry/gen_cry_rom.py /tmp/cry.nds /tmp/expect.json
kotlinc -d /tmp/cry.jar app/src/main/java/me/magnum/melonds/pokewalker/CryArchive.kt pokewalker-tests/cry/CryTest.kt
java -cp /tmp/cry.jar:$KOTLIN_HOME/lib/kotlin-stdlib.jar CryTestKt /tmp/cry.nds > /tmp/got.json
python3 pokewalker-tests/cry/check.py /tmp/got.json /tmp/expect.json
```
`PW_DEVICE_DUMP=<dir>` writes each checked screen as a `.pgm` picture.

## map (manual)

The walker screen's route map (`NitroFiles.kt`, `SoftRenderer.kt`, `FieldMap.kt`,
`FollowerSprites.kt`, `MapExplorer.kt`, `MapPeople.kt`) draws the place in the player's own
HeartGold/SoulSilver ROM that the walk's course looks like and lets the walking
Pokémon wander it. `map/gen_map_rom.py` builds a small fake ROM with the field
files laid out the same way, written from the format notes (no game data): map
matrices, land data with tile permissions, a background-sound section, buildings,
a terrain model and BDHC surfaces (one of them a slope), area data with the day
and the steady cave light, a building list, texture sets, building models (one the
area doesn't load, one a floating effect layer) and follower sprites with a shiny
palette, plus a sea patch with the game's texture swaps (`a/1/3/9`) and scroll
(`a/1/4/0`), and the map's people (`a/0/3/2` events, sixteen-frame sprites): a
wanderer, a trainer, a swimmer, someone hidden by a flag, an apricorn tree and
someone with nowhere to stand. `MapTest.kt` checks which spot is picked, the colours drawn, which
tiles the Pokémon may stand on and how high, that a cave counts only its own
floor, the moving sea, the drifting ambience (clouds and petals by day, fireflies
at night, grass shaken up), the sprite and the walking Pokémon read from the
walker's memory, hiding behind the house and in tall grass, and three minutes of
wandering, being sent somewhere and walked by hand, and the people: who is shown,
their frames, five minutes of them going about (staying near home, never sharing
a tile with anyone), turning to the Pokémon when it faces them, and the swimmer
without a shadow:

```sh
python3 pokewalker-tests/map/gen_map_rom.py /tmp/map.nds /tmp/map_empty.nds
P=app/src/main/java/me/magnum/melonds/pokewalker
kotlinc -d /tmp/map.jar $P/NitroFiles.kt $P/SoftRenderer.kt $P/CryArchive.kt $P/FieldMap.kt \
    $P/FollowerSprites.kt $P/MapExplorer.kt $P/MapAnimation.kt $P/MapAmbience.kt $P/MapPeople.kt \
    pokewalker-tests/map/MapTest.kt
java -cp /tmp/map.jar:$KOTLIN_HOME/lib/kotlin-stdlib.jar MapTestKt /tmp/map.nds /tmp/map_empty.nds
```

## game_trace_test (runs in CI)

The screen trace (`GameTrace.cpp`) on a simulated DS main RAM laid out like the
game's (`_02111868` in `src/main.c`, `OverlayManager`, overlay 112's data):
main menu, Pokéwalker screen steps, title screen, buttons and touches, an
unexpected program in memory, unrecognised memory and the line limit.

## cart_ir_test (manual)

Drives the real melonDS `CartRetailIR` SPI code (`0x01`/`0x02`/`0x08`) through
the `Platform::IR_*` hooks and the Android IR front end, exactly as in the app.
It needs a host build of the core library:

```sh
git clone https://github.com/KhronosGroup/Vulkan-Headers /tmp/vkh
cmake -S melonDS-android-lib -B build/core -G Ninja -DBUILD_QT_SDL=OFF -DENABLE_JIT=OFF \
  -DENABLE_OGLRENDERER=OFF -DENABLE_GDBSTUB=OFF \
  -DCMAKE_CXX_FLAGS="-I/tmp/vkh/include -DVK_NO_PROTOTYPES" -DCMAKE_C_FLAGS="-I/tmp/vkh/include -DVK_NO_PROTOTYPES"
ninja -C build/core core
P=app/src/main/cpp/pokewalker
g++ -std=c++17 -ImelonDS-android-lib/src -Ibuild/core/src -I$P pokewalker-tests/cart_ir_test.cpp \
  $P/PokewalkerIR.cpp $P/PlatformIR.cpp $P/VirtualPokewalker.cpp $P/EepromStore.cpp $P/PwProtocol.cpp $P/PwText.cpp \
  $P/RomCheck.cpp $P/GameTrace.cpp $P/WalkerDevice.cpp $P/PixelFont.cpp \
  -Wl,--start-group build/core/src/libcore.a build/core/src/teakra/src/libteakra.a -Wl,--end-group \
  -Wl,--unresolved-symbols=ignore-all -lpthread -o cart_ir_test && ./cart_ir_test
```
