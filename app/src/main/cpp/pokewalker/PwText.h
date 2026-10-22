/*
    Virtual Pokéwalker for melonDS Android - text helpers for the status screen.
    GPL-3.0-or-later, see PwCommon.h.
*/

#ifndef POKEWALKER_PWTEXT_H
#define POKEWALKER_PWTEXT_H

#include <string>
#include "PwCommon.h"

namespace pokewalker
{

/// Decodes a Generation IV in-game string (16-bit codes, terminated by 0xFFFF)
/// into UTF-8. Characters outside the Latin set the Pokéwalker uses become '?'.
std::string DecodeGameText(const u8* data, size_t maxChars);

/// English species name for a national dex number (1..493), or "" if unknown.
const char* SpeciesName(u16 species);

/// Escapes a string for inclusion in a JSON document.
std::string JsonEscape(const std::string& s);

}

#endif
