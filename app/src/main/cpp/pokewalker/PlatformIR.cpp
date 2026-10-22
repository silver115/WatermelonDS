/*
    melonDS::Platform infrared hooks for Android, implemented by the
    Pokéwalker IR front end.
    GPL-3.0-or-later, see PwCommon.h.
*/

#include "Platform.h"
#include "PokewalkerIR.h"

namespace melonDS::Platform
{

void IR_Reset(u32 gamecode, u32 irversion, const u8* rom, u32 romlen, void* /*userdata*/)
{
    pokewalker::ir::OnCartReset(gamecode, irversion, rom, romlen);
}

int IR_SendPacket(const u8* data, int len, void* /*userdata*/)
{
    return pokewalker::ir::OnGameSend(data, len);
}

int IR_ReceivePacket(u8* data, int maxlen, void* /*userdata*/)
{
    return pokewalker::ir::OnGamePoll(data, maxlen);
}

}
