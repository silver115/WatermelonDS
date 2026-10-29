"""Builds a small fake NDS ROM with a Nitro sound archive laid out like HG/SS's
(data/sound/gs_sound_data.sdat) holding a few made-up sequences (at the route music's
sequence numbers), an instrument bank and a wave archive, written from the SDAT/SSEQ/SBNK/SWAR
format notes. No game data: sine waves and square waves.

  python3 gen_music_rom.py out.nds
"""
import math, struct, sys

def u8(v): return struct.pack('<B', v)
def u16(v): return struct.pack('<H', v & 0xFFFF)
def u32(v): return struct.pack('<I', v)

def nitro(tag, data_block):
    return tag + u16(0xFEFF) + u16(0x0100) + u32(16 + len(data_block)) + u16(16) + u16(1) + data_block

def data_block(payload, reserved=True):
    inner = (b'\0' * 32 if reserved else b'') + payload
    return b'DATA' + u32(8 + len(inner)) + inner

def swav(kind, rate, data, loop, loop_words):
    words = (len(data) + 3) // 4
    data = data + b'\0' * (words * 4 - len(data))
    return u8(kind) + u8(1 if loop else 0) + u16(rate) + u16(16756991 // rate) + u16(loop_words) + u32(words - loop_words) + data

def swar(waves):
    n = len(waves)
    base = 0x3C + 4 * n
    offs, blob = [], b''
    for w in waves:
        offs.append(base + len(blob))
        blob += w
    return nitro(b'SWAR', data_block(u32(n) + b''.join(u32(o) for o in offs) + blob))

def sbnk(instruments):
    n = len(instruments)
    head = 0x3C + 4 * n
    recs, blob = b'', b''
    for t, b in instruments:
        off = head + len(blob) if b else 0
        recs += u8(t) + u16(off) + u8(0)
        blob += b
    return nitro(b'SBNK', data_block(u32(n) + recs + blob))

def note10(wave, arc, root, a=127, d=127, s=127, r=127, pan=64):
    return u16(wave) + u16(arc) + bytes([root, a, d, s, r, pan])

def note12(kind, *args, **kw):
    return u8(kind) + u8(0) + note10(*args, **kw)

class Seq:
    """Assembles sequence commands with labels for jumps (offsets from the data start)."""
    def __init__(self):
        self.code = bytearray()
        self.labels = {}
        self.fixups = []
    def label(self, name): self.labels[name] = len(self.code)
    def raw(self, *bs): self.code += bytes(bs)
    def ref(self, name):
        self.fixups.append((len(self.code), name))
        self.code += b'\0\0\0'
    def vlv(self, v):
        out = [v & 0x7F]
        v >>= 7
        while v:
            out.insert(0, 0x80 | (v & 0x7F))
            v >>= 7
        self.code += bytes(out)
    def note(self, key, vel, length):
        self.raw(key, vel)
        self.vlv(length)
    def rest(self, length):
        self.raw(0x80)
        self.vlv(length)
    def build(self):
        for at, name in self.fixups:
            v = self.labels[name]
            self.code[at:at + 3] = bytes([v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF])
        data = b'DATA' + u32(12 + len(self.code)) + u32(0x1C) + bytes(self.code)
        return b'SSEQ' + u16(0xFEFF) + u16(0x0100) + u32(16 + len(data)) + u16(16) + u16(1) + data

# ---- the sequences ----

# A (1028): tempo 120; A4 for a quarter, a quarter rest, C5, a rest, round again
a = Seq()
a.raw(0xFE, 0x01, 0x00)
a.raw(0xE1, 120, 0)
a.raw(0xC1, 127)
a.raw(0x81, 0)
a.label('loop')
a.note(69, 127, 48); a.rest(48)
a.note(72, 127, 48); a.rest(48)
a.raw(0x94); a.ref('loop')

# B (1073): three tracks: a square wave C4 on the left, a key-split sine on the right, a silent one
b = Seq()
b.raw(0xFE, 0x07, 0x00)
b.raw(0x93, 1); b.ref('t1')
b.raw(0x93, 2); b.ref('t2')
b.raw(0xE1, 120, 0)
b.raw(0xC0, 0)
b.raw(0x81, 1)
b.label('l0'); b.note(60, 127, 96); b.raw(0x94); b.ref('l0')
b.label('t1')
b.raw(0xC0, 127)
b.raw(0x81, 3)
b.label('l1'); b.note(57, 127, 96); b.raw(0x94); b.ref('l1')
b.label('t2')
b.raw(0xC1, 0)
b.raw(0x81, 0)
b.label('l2'); b.note(76, 127, 96); b.raw(0x94); b.ref('l2')

# C (1018): tempo 240; a loop of three calls (A4, rest), a variable and conditions, a random rest, the end
c = Seq()
c.raw(0xFE, 0x01, 0x00)
c.raw(0xE1, 240, 0)
c.raw(0x81, 0)
c.raw(0xB0, 0, 5, 0)            # var0 = 5
c.raw(0xD4, 3)                  # loop 3 times
c.raw(0x95); c.ref('sub')       #   call: A4, rest
c.raw(0xFC)
c.raw(0xB8, 0, 5, 0)            # var0 == 5: yes
c.raw(0xA2); c.note(72, 127, 24)   # if: C5
c.raw(0xA2); c.rest(24)            # if: rest
c.raw(0xB8, 0, 6, 0)            # var0 == 6: no
c.raw(0xA2); c.note(76, 127, 24)   # if: E5 (skipped)
c.raw(0xA0, 0x80, 24, 0, 24, 0)    # rest of 24 to 24 ticks
c.raw(0xFF)
c.label('sub'); c.note(69, 127, 24); c.rest(24); c.raw(0xFD)

# D (1157): a slow attack and release (instrument 4), a held note then a rest
d = Seq()
d.raw(0xFE, 0x01, 0x00)
d.raw(0xE1, 120, 0)
d.raw(0x81, 4)
d.label('loop'); d.note(69, 127, 96); d.rest(96); d.raw(0x94); d.ref('loop')

# ---- the bank and the waves ----
sine = b''.join(struct.pack('<h', int(round(20000 * math.sin(2 * math.pi * i / 32)))) for i in range(32))
blip = bytes(((int(100 * math.sin(i * 0.9)) & 0xFF)) for i in range(400))
waves = swar([
    swav(1, 14080, sine, True, 0),       # 0: a looping sine: 440 Hz at its own rate
    swav(0, 8000, blip, False, 0),       # 1: a one-shot blip
])
drums = bytes([36, 38]) + note12(3, 0, 0, 60) + note12(1, 1, 0, 60) + note12(1, 0, 0, 69)
split = bytes([59, 127, 0, 0, 0, 0, 0, 0]) + note12(1, 0, 0, 57) + note12(2, 2, 0, 60)
bank = sbnk([
    (1, note10(0, 0, 69)),                       # 0: the sine, A4 at its own pitch
    (2, note10(3, 0, 60)),                       # 1: a square wave (duty 3), C4 at its own pitch
    (16, drums),                                 # 2: drums 36-38
    (17, split),                                 # 3: key split: up to 59 the sine (A3 at its own pitch), then a square
    (1, note10(0, 0, 69, a=90, r=90)),           # 4: the sine, a slow attack and release
])

files = [a.build(), b.build(), c.build(), d.build(), bank, waves]
SEQS = {1028: 0, 1073: 1, 1018: 2, 1157: 3}

def info_block():
    seq_count = max(SEQS) + 1
    groups = {0: seq_count, 2: 4, 3: 1}
    recs_pos = 8 + 32 + 24
    rec_bytes = b''
    rec_offs = {}
    pos = recs_pos
    for g in range(8):
        n = groups.get(g, 0)
        rec_offs[g] = pos
        pos += 4 + 4 * n
    entry_pos = pos
    ents = b''
    for g in range(8):
        n = groups.get(g, 0)
        offs = []
        for i in range(n):
            e = None
            if g == 0 and i in SEQS:
                e = u16(SEQS[i]) + u16(0) + u16(3) + bytes([127, 64, 64, 0]) + u16(0)
            elif g == 2 and i == 3:
                e = u16(4) + u16(0) + u16(0) + u16(0xFFFF) + u16(0xFFFF) + u16(0xFFFF)
            elif g == 3 and i == 0:
                e = u16(5) + u16(0)
            if e is None:
                offs.append(0)
            else:
                offs.append(entry_pos + len(ents))
                ents += e
        rec_bytes += u32(n) + b''.join(u32(o) for o in offs)
    body = b''.join(u32(rec_offs[g]) for g in range(8)) + b'\0' * 24 + rec_bytes + ents
    return b'INFO' + u32(8 + len(body)) + body

info = info_block()
fat_head = 12 + 16 * len(files)
info_off = 0x40
fat_off = info_off + len(info)
file_off = fat_off + fat_head
blob = b''
fat_entries = b''
for f in files:
    while (file_off + 8 + len(blob)) % 4: blob += b'\0'
    fat_entries += u32(file_off + 8 + len(blob)) + u32(len(f)) + u32(0) + u32(0)
    blob += f
fat = b'FAT ' + u32(fat_head) + u32(len(files)) + fat_entries
file_block = b'FILE' + u32(8 + len(blob)) + blob
total = 0x40 + len(info) + len(fat) + len(file_block)
sdat = (b'SDAT' + u16(0xFEFF) + u16(0x0100) + u32(total) + u16(0x40) + u16(3)
        + u32(0) + u32(0) + u32(info_off) + u32(len(info)) + u32(fat_off) + u32(len(fat))
        + u32(file_off) + u32(len(file_block)) + b'\0' * 16) + info + fat + file_block
assert len(sdat) == total

# NDS file system: root/data/sound/gs_sound_data.sdat
def sub(entries):
    out = b''
    for name, sub_id in entries:
        nb = name.encode()
        if sub_id is None: out += u8(len(nb)) + nb
        else: out += u8(0x80 | len(nb)) + nb + u16(0xF000 | sub_id)
    return out + b'\0'
subs = [sub([('data', 1)]), sub([('sound', 2)]), sub([('gs_sound_data.sdat', None)])]
main_size = 8 * 3
offs, acc = [], main_size
for s_ in subs: offs.append(acc); acc += len(s_)
fnt = (u32(offs[0]) + u16(0) + u16(3) + u32(offs[1]) + u16(0) + u16(0xF000) + u32(offs[2]) + u16(0) + u16(0xF001)) + b''.join(subs)
FNT_OFF, FAT_OFF = 0x200, 0x400
rom = bytearray(0x1000)
start = len(rom)
rom += sdat
fatn = u32(start) + u32(start + len(sdat))
while len(rom) % 0x200: rom.append(0xFF)
rom[0x0C:0x10] = b'IPKE'
rom[0x40:0x50] = u32(FNT_OFF) + u32(len(fnt)) + u32(FAT_OFF) + u32(len(fatn))
rom[FNT_OFF:FNT_OFF + len(fnt)] = fnt
rom[FAT_OFF:FAT_OFF + len(fatn)] = fatn
open(sys.argv[1], 'wb').write(bytes(rom))
