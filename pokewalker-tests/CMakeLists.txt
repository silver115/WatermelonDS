cmake_minimum_required(VERSION 3.16)
project(pokewalker_tests CXX)

# Host build of the virtual Pokéwalker tests (no Android or melonDS core needed):
#   cmake -S pokewalker-tests -B build/pokewalker-tests && cmake --build build/pokewalker-tests
#   ./build/pokewalker-tests/walker_test [path/to/real-walker-eeprom.bin]

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(PW_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp/pokewalker)

add_executable(walker_test
    walker_test.cpp
    ${PW_SRC}/PwProtocol.cpp
    ${PW_SRC}/EepromStore.cpp
    ${PW_SRC}/PwText.cpp
    ${PW_SRC}/VirtualPokewalker.cpp
)
target_include_directories(walker_test PRIVATE ${PW_SRC})

if (CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    target_compile_options(walker_test PRIVATE -Wall -Wextra -fsanitize=address,undefined -fno-omit-frame-pointer)
    target_link_options(walker_test PRIVATE -fsanitize=address,undefined)
endif()

# cart_ir_test.cpp additionally exercises the real melonDS CartRetailIR SPI
# code; it needs a host build of melonDS-android-lib's "core" target, see
# pokewalker-tests/README.md.
