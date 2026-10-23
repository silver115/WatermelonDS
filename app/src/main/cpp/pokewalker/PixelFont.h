/*
    Small 5x7 pixel font for the on-screen walker's own messages (things the
    game never wrote into the walker, such as "not paired yet").
    GPL-3.0-or-later, see PwCommon.h.
*/

#ifndef POKEWALKER_PIXELFONT_H
#define POKEWALKER_PIXELFONT_H

#include "PwCommon.h"

namespace pokewalker::font
{

constexpr int GlyphWidth = 5;
constexpr int GlyphHeight = 7;
constexpr int Advance = 6;     // glyph plus one column of spacing
constexpr int LineHeight = 8;

/// Seven rows of five bits (bit 4 = leftmost column) for `ch`. Lower case is
/// drawn as upper case; unknown characters come back blank.
const u8* Glyph(char ch);

}

#endif
