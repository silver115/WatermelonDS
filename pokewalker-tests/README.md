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

```sh
./build/pokewalker-tests/hgss_flow_test
```

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
  -Wl,--start-group build/core/src/libcore.a build/core/src/teakra/src/libteakra.a -Wl,--end-group \
  -Wl,--unresolved-symbols=ignore-all -lpthread -o cart_ir_test && ./cart_ir_test
```
