"""Builds a small fake NDS ROM with a Nitro sound archive laid out like HG/SS's
(data/sound/gs_sound_data.sdat), written from the SDAT/SBNK/SWAR/SSEQ format notes,
plus the samples and rates a correct reader must produce."""
import struct, json, random, sys

def u8(v): return struct.pack('<B', v)
def u16(v): return struct.pack('<H', v)
def u32(v): return struct.pack('<I', v)

def nitro(tag, data_block):
    # 16-byte Nitro header + one block
    body = data_block
    return tag + u16(0xFEFF) + u16(0x0100) + u32(16 + len(body)) + u16(16) + u16(1) + body

def data_block(payload, reserved=True):
    inner = (b'\0' * 32 if reserved else b'') + payload
    return b'DATA' + u32(8 + len(inner)) + inner

STEP = [7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97,
        107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871,
        5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623,
        27086, 29794, 32767]
IDX = [-1, -1, -1, -1, 2, 4, 6, 8]

def adpcm_decode(data):
    pred = struct.unpack_from('<h', data, 0)[0]
    index = min(max(data[2], 0), 88)
    out = []
    for b in data[4:]:
        for n in (b & 0xF, b >> 4):
            step = STEP[index]
            diff = step >> 3
            if n & 1: diff += step >> 2
            if n & 2: diff += step >> 1
            if n & 4: diff += step
            pred = max(pred - diff, -0x7FFF) if n & 8 else min(pred + diff, 0x7FFF)
            index = min(max(index + IDX[n & 7], 0), 88)
            out.append(pred)
    return out

def swav(kind, rate, data):
    words = (len(data) + 3) // 4
    data = data + b'\0' * (words * 4 - len(data))
    return u8(kind) + u8(0) + u16(rate) + u16(16756991 // rate) + u16(0) + u32(words) + data

def swar(waves):
    n = len(waves)
    base = 0x3C + 4 * n
    offs, blob = [], b''
    for w in waves:
        offs.append(base + len(blob))
        blob += w
    payload = u32(n) + b''.join(u32(o) for o in offs) + blob
    return nitro(b'SWAR', data_block(payload))

def sbnk(instruments):
    # instruments: list of (type, bytes)
    n = len(instruments)
    head = 0x3C + 4 * n
    recs, blob = b'', b''
    for t, b in instruments:
        off = head + len(blob) if b else 0
        recs += u8(t) + u16(off) + u8(0)
        blob += b
    payload = u32(n) + recs + blob
    return nitro(b'SBNK', data_block(payload))

def region10(swav_i, swar_i, note):
    return u16(swav_i) + u16(swar_i) + u8(note) + bytes([127, 127, 127, 127, 64])

def region12(kind, swav_i, swar_i, note):
    return u16(kind) + region10(swav_i, swar_i, note)

def sseq(events):
    # header 16 + 'DATA' + size + dataOffset(=0x1C) + events
    data = b'DATA' + u32(12 + len(events)) + u32(0x1C) + events
    return b'SSEQ' + u16(0xFEFF) + u16(0x0100) + u32(16 + len(data)) + u16(16) + u16(1) + data

random.seed(7)
adpcm = struct.pack('<h', -1200) + bytes([20, 0]) + bytes(random.randrange(256) for _ in range(60))
pcm8 = bytes(random.randrange(256) for _ in range(40))
pcm16 = b''.join(struct.pack('<h', random.randrange(-30000, 30000)) for _ in range(30))
pcm8b = bytes(random.randrange(256) for _ in range(52))

files = []
# 0: SEQ_PV: alloc tracks, volume, transpose +2, program 1, note 60 (vel 127, len 128), end
files.append(sseq(b'\xFE\x01\x00' + b'\xC1\x7F' + b'\xC3\x02' + b'\x81\x01' + bytes([60, 127, 0x81, 0x00]) + b'\xFF'))
# 1: bank 25: [0] PSG (no wave), [1] PCM swav 1 of slot 0, base note 60
files.append(sbnk([(2, region10(3, 0, 60)), (1, region10(1, 0, 60))]))
# 2: bank 26: [0] key split, [1] drum set 60..63 (key 62 -> swav 1 of slot 1, base note 50)
ks = bytes([50, 70, 127, 0, 0, 0, 0, 0]) + region12(1, 0, 1, 40) + region12(1, 1, 1, 62) + region12(1, 0, 1, 80)
drums = bytes([60, 63]) + region12(1, 0, 1, 60) + region12(1, 0, 1, 61) + region12(1, 1, 1, 50) + region12(1, 0, 1, 63)
files.append(sbnk([(17, ks), (16, drums)]))
# 3: wave archive 7: PCM8 @ 8000, ADPCM @ 11025
files.append(swar([swav(0, 8000, pcm8), swav(2, 11025, adpcm)]))
# 4: wave archive 8: PCM16 @ 16000, PCM8 @ 22050
files.append(swar([swav(1, 16000, pcm16), swav(0, 22050, pcm8b)]))

# INFO block
def info_block():
    groups = {}
    groups[0] = [None, None, (0, 0, 0), None]              # SEQ: entry 2 = SEQ_PV -> file 0
    banks = [None] * 30
    banks[25] = ('bank', 1, [7, 0xFFFF, 0xFFFF, 0xFFFF])
    banks[26] = ('bank', 2, [0xFFFF, 8, 0xFFFF, 0xFFFF])
    groups[2] = banks
    arcs = [None] * 10
    arcs[7] = ('arc', 3)
    arcs[8] = ('arc', 4)
    groups[3] = arcs
    head = 8 + 32 + 24
    body = b''
    rec_offsets = [0] * 8
    entries_blob = b''
    # lay out records first, then entries
    recs = {}
    pos = head
    for g in range(8):
        lst = groups.get(g, [])
        recs[g] = pos
        pos += 4 + 4 * len(lst)
    entry_pos = pos
    rec_bytes = b''
    ent_bytes = b''
    for g in range(8):
        lst = groups.get(g, [])
        offs = []
        for e in lst:
            if e is None:
                offs.append(0); continue
            offs.append(entry_pos + len(ent_bytes))
            if g == 0:
                fid, bank, _ = e
                ent_bytes += u16(fid) + u16(0) + u16(bank) + bytes([127, 64, 64, 0]) + u16(0)
            elif g == 2:
                _, fid, wa = e
                ent_bytes += u16(fid) + u16(0) + b''.join(u16(w) for w in wa)
            elif g == 3:
                _, fid = e
                ent_bytes += u16(fid) + u16(0)
        rec_bytes += u32(len(lst)) + b''.join(u32(o) for o in offs)
    body = b''.join(u32(recs[g]) for g in range(8)) + b'\0' * 24 + rec_bytes + ent_bytes
    return b'INFO' + u32(8 + len(body)) + body

info = info_block()
fat_head = 12 + 16 * len(files)
sdat_head = 0x40
info_off = sdat_head
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
total = sdat_head + len(info) + len(fat) + len(file_block)
sdat = (b'SDAT' + u16(0xFEFF) + u16(0x0100) + u32(total) + u16(0x40) + u16(3)
        + u32(0) + u32(0) + u32(info_off) + u32(len(info)) + u32(fat_off) + u32(len(fat))
        + u32(file_off) + u32(len(file_block)) + b'\0' * 16) + info + fat + file_block
assert len(sdat) == total

# NDS file system: root/a.bin, root/data/x.dat, root/data/sound/{other.sdat, gs_sound_data.sdat}
def sub(entries):
    out = b''
    for name, sub_id in entries:
        nb = name.encode()
        if sub_id is None: out += u8(len(nb)) + nb
        else: out += u8(0x80 | len(nb)) + nb + u16(0xF000 | sub_id)
    return out + b'\0'
subs = [sub([('a.bin', None), ('data', 1)]), sub([('x.dat', None), ('sound', 2)]), sub([('other.sdat', None), ('gs_sound_data.sdat', None)])]
main_size = 8 * 3
offs, acc = [], main_size
for s_ in subs: offs.append(acc); acc += len(s_)
fnt = (u32(offs[0]) + u16(0) + u16(3) + u32(offs[1]) + u16(1) + u16(0xF000) + u32(offs[2]) + u16(2) + u16(0xF001)) + b''.join(subs)
other = b'SDAT' + b'\0' * 60
contents = [b'hello', b'xx', other, sdat]
FNT_OFF, FAT_OFF = 0x200, 0x400
data_off = 0x1000
rom = bytearray(data_off)
fatn = b''
for c in contents:
    start = len(rom)
    rom += c
    fatn += u32(start) + u32(start + len(c))
    while len(rom) % 0x200: rom.append(0xFF)
rom[0x0C:0x10] = b'IPKE'
rom[0x40:0x50] = u32(FNT_OFF) + u32(len(fnt)) + u32(FAT_OFF) + u32(len(fatn))
rom[FNT_OFF:FNT_OFF + len(fnt)] = fnt
rom[FAT_OFF:FAT_OFF + len(fatn)] = fatn
open(sys.argv[1], 'wb').write(bytes(rom))

expect = {
    '25': {'rate': int(11025 * 2 ** (2 / 12)), 'samples': adpcm_decode(adpcm)},
    '26': {'rate': int(22050 * 2 ** (12 / 12)), 'samples': [((b ^ 0x80) - 0x80) * 256 for b in pcm8b]},
}
json.dump(expect, open(sys.argv[2], 'w'))
print('rom', len(rom), 'bytes; sdat', len(sdat))
