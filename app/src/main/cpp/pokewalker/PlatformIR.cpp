/*
    melonDS::Platform infrared hooks for Android, implemented by the
    Pokéwalker IR front end.
    GPL-3.0-or-later, see PwCommon.h.
*/

#include "Platform.h"
#include "PokewalkerIR.h"

namespace melonDS::Platform
{

void IR_Reset(u32 gamecode, u32 irversion, void* /*userdata*/)
{
    pokewalker::ir::OnCartReset(gamecode, irversion);
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
