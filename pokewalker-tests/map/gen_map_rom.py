"""Builds a small fake NDS ROM with the field files laid out like HeartGold/SoulSilver's (map
matrices, land data with tile permissions, buildings, a terrain model and BDHC surfaces, area
data, building lists, texture sets, building models and follower sprites), written from the
format notes, for MapTest.kt. Nothing in it comes from the game.

The fake world:
  - matrix 0 (the overworld): 3x2 cells, map 33 (Route 29, the "Refreshing Field" course) on
    the middle column, land blocks 0 (top) and 1 (bottom);
  - block 0: grass ground at height 16, blocked around the edges (rows 0-3 and 28-31, columns
    0-3) and across row 4; tall grass in x 6-26, y 9-23; water in tiles x 20-24, y 12-15; a
    house (model 1, 64x48x64 units) over tiles x 14-17, y 18-21; a box that isn't in the area's
    building list (model 2) over tiles 9-10, y 12-13; a flat layer floating 160 units up
    (model 3); and a slope rising half a unit per unit from x 96 (tiles 22-31);
  - block 1: rocks (blocked tiles) but for a path down tiles x 15-17;
  - matrix 42 (the "Dim Cave" course, map 99, area 87 with the steady cave light): one block,
    land 2: cave floor in x 4-12, y 4-12 ringed with blocked tiles, open "void" around it;
  - follower sprites: every species gets the same eight 32x32 frames, a normal and a shiny
    palette.
"""
import struct, sys

def u8(v): return struct.pack('<B', v)
def u16(v): return struct.pack('<H', v & 0xFFFF)
def s16(v): return struct.pack('<h', v)
def u32(v): return struct.pack('<I', v & 0xFFFFFFFF)
def s32(v): return struct.pack('<i', v)
def fx(v): return int(round(v * 4096))

# ---- Nitro containers -------------------------------------------------------------------

def nitro_file(stamp, blocks):
    head = 0x10 + 4 * len(blocks)
    offs, body = [], b''
    for b in blocks:
        offs.append(head + len(body))
        body += b
        while len(body) % 4: body += b'\0'
    total = head + len(body)
    return stamp + u16(0xFEFF) + u16(0x0002) + u32(total) + u16(0x10) + u16(len(blocks)) + b''.join(u32(o) for o in offs) + body

def dict_block(entries, datum_size):
    """NNS dictionary: header, (unused) patricia tree, entry data, 16-byte names."""
    n = len(entries)
    out = u8(0) + u8(n) + u16(0) + u16(0) + u16(8 + 4 * (n + 1)) + b'\0' * (4 * (n + 1))
    out += u16(datum_size) + u16(4 + datum_size * n)
    for _, d in entries:
        assert len(d) == datum_size
        out += d
    for name, _ in entries: out += name.encode().ljust(16, b'\0')[:16]
    return out[:2] + u16(len(out)) + out[4:]

def dict_size(n, datum_size):
    return len(dict_block([('x', b'\0' * datum_size)] * n, datum_size))

def tex_params(off, w, h, fmt, color0=False, repeat=True):
    lw = {8: 0, 16: 1, 32: 2, 64: 3}[w]
    lh = {8: 0, 16: 1, 32: 2, 64: 3}[h]
    return (off >> 3) | ((1 << 16) | (1 << 17) if repeat else 0) | (lw << 20) | (lh << 23) | (fmt << 26) | ((1 << 29) if color0 else 0)

def tex0(textures, palettes):
    """textures: [(name, w, h, fmt, data, color0)], palettes: [(name, [rgb555...])]"""
    tex_data = b''
    tex_entries = []
    for name, w, h, fmt, data, c0 in textures:
        while len(tex_data) % 8: tex_data += b'\0'
        tex_entries.append((name, u32(tex_params(len(tex_data), w, h, fmt, c0)) + u32(0)))
        tex_data += data
    while len(tex_data) % 8: tex_data += b'\0'
    pal_data = b''
    pal_entries = []
    for name, colors in palettes:
        while len(pal_data) % 8: pal_data += b'\0'
        pal_entries.append((name, u16(len(pal_data) >> 3) + u16(0)))
        pal_data += b''.join(u16(c) for c in colors)
    while len(pal_data) % 8: pal_data += b'\0'
    tdict = dict_block(tex_entries, 8)
    pdict = dict_block(pal_entries, 4)
    head = 0x3C
    tdict_off = head
    pdict_off = tdict_off + len(tdict)
    tdata_off = pdict_off + len(pdict)
    while tdata_off % 8: tdata_off += 1
    pdata_off = tdata_off + len(tex_data)
    size = pdata_off + len(pal_data)
    h = bytearray(head)
    h[0:4] = b'TEX0'
    h[4:8] = u32(size)
    h[0x0C:0x0E] = u16(len(tex_data) >> 3)
    h[0x0E:0x10] = u16(tdict_off)
    h[0x14:0x18] = u32(tdata_off)
    h[0x24:0x28] = u32(size)  # no 4x4 texel data
    h[0x28:0x2C] = u32(size)
    h[0x30:0x34] = u32(len(pal_data) >> 3)
    h[0x34:0x38] = u32(pdict_off)
    h[0x38:0x3C] = u32(pdata_off)
    out = bytes(h) + tdict + pdict
    out += b'\0' * (tdata_off - len(out))
    return out + tex_data + pal_data

def solid_tex(index_rows):
    """8x8 4-colour texture (format 2) from rows of palette indices."""
    out = b''
    for row in index_rows:
        for x in range(0, 8, 4):
            v = 0
            for k in range(4): v |= (row[x + k] & 3) << (2 * k)
            out += u8(v)
    return out

def rgb(r, g, b): return (r >> 3) | ((g >> 3) << 5) | ((b >> 3) << 10)

# ---- models ------------------------------------------------------------------------------

def gpu(cmds):
    """cmds: [(opcode, [params...])] packed four to a word."""
    out = b''
    for i in range(0, len(cmds), 4):
        group = cmds[i:i + 4]
        ops = [c[0] for c in group] + [0] * (4 - len(group))
        out += bytes(ops)
        for _, params in group:
            for p in params: out += u32(p)
    return out

def vtx16(x, y, z, scale):
    q = lambda v: int(round(v / scale * 4096))
    return [(q(x) & 0xFFFF) | ((q(y) & 0xFFFF) << 16), q(z) & 0xFFFF]

def tc(s, t):
    return (int(s * 16) & 0xFFFF) | ((int(t * 16) & 0xFFFF) << 16)

def quads(qs, scale, texels_per_unit=0.25):
    """qs: list of four (x, y, z) corners each (counter-clockwise seen from the front)."""
    cmds = [(0x40, [1])]
    for q in qs:
        for (x, y, z) in q:
            cmds.append((0x22, [tc(x * texels_per_unit, z * texels_per_unit + y * texels_per_unit)]))
            cmds.append((0x23, vtx16(x, y, z, scale)))
    cmds.append((0x41, []))
    return gpu(cmds)

def material(tex_w=8, tex_h=8, both_sides=False, color=0x7FFF):
    poly = (31 << 16) | (1 << 7) | ((1 << 6) if both_sides else 0)
    m = bytearray(0x2C)
    m[0:2] = u16(0)
    m[2:4] = u16(0x2C)
    m[4:8] = u32(color | 0x8000 | (rgb(160, 160, 160) << 16))
    m[8:0xC] = u32(0)
    m[0xC:0x10] = u32(poly)
    m[0x10:0x14] = u32(0xFFFFFFFF)
    m[0x14:0x18] = u32((1 << 16) | (1 << 17))
    m[0x18:0x1C] = u32(0xFFFFFFFF)
    m[0x1E:0x20] = u16(0)
    m[0x20:0x22] = u16(tex_w)
    m[0x22:0x24] = u16(tex_h)
    m[0x24:0x28] = s32(4096)
    m[0x28:0x2C] = s32(4096)
    return bytes(m)

def mdl0(name, display_list, tex_name, pal_name, scale, both_sides=False, color=0x7FFF):
    """One model, one node (identity), one material, one shape."""
    sbc = bytes([0x06, 0, 0, 0, 0x04, 0, 0x05, 0, 0x01])
    while len(sbc) % 4: sbc += b'\0'
    node = u16(0x0007) + s16(0)
    nodes_dict_size = dict_size(1, 4)
    nodes = dict_block([('node0', u32(nodes_dict_size))], 4) + node
    # materials: pair dicts' offsets, material dict, tex pair dict, pal pair dict, index lists, material
    mat_dict_size = dict_size(1, 4)
    tex_pair_size = dict_size(1, 4) if tex_name else dict_size(0, 4)
    pal_pair_size = dict_size(1, 4) if pal_name else dict_size(0, 4)
    tex_pair_off = 4 + mat_dict_size
    pal_pair_off = tex_pair_off + tex_pair_size
    lists_off = pal_pair_off + pal_pair_size
    mat_off = lists_off + 4
    mats = u16(tex_pair_off) + u16(pal_pair_off)
    mats += dict_block([('mat0', u32(mat_off))], 4)
    mats += dict_block([(tex_name, u16(lists_off) + u8(1) + u8(0))], 4) if tex_name else dict_block([], 4)
    mats += dict_block([(pal_name, u16(lists_off) + u8(1) + u8(0))], 4) if pal_name else dict_block([], 4)
    mats += bytes([0, 0, 0, 0])
    assert len(mats) == mat_off
    mats += material(both_sides=both_sides, color=color)
    # shapes
    shp_dict_size = dict_size(1, 4)
    shp_off = shp_dict_size
    shp = bytearray(0x10)
    shp[2:4] = u16(0x10)
    shp[8:0xC] = u32(0x10)
    shp[0xC:0x10] = u32(len(display_list))
    shapes = dict_block([('shp0', u32(shp_off))], 4) + bytes(shp) + display_list
    head = bytearray(0x40)
    body = nodes
    while (0x40 + len(body)) % 4: body += b'\0'
    sbc_off = 0x40 + len(body); body += sbc
    mat_block_off = 0x40 + len(body); body += mats
    while (0x40 + len(body)) % 4: body += b'\0'
    shp_block_off = 0x40 + len(body); body += shapes
    size = 0x40 + len(body)
    head[0:4] = u32(size)
    head[4:8] = u32(sbc_off)
    head[8:0xC] = u32(mat_block_off)
    head[0xC:0x10] = u32(shp_block_off)
    head[0x10:0x14] = u32(0)  # no inverse bind matrices
    head[0x17] = 1
    head[0x18] = 1
    head[0x19] = 1
    head[0x1C:0x20] = s32(fx(scale))
    head[0x20:0x24] = s32(fx(1 / scale))
    model = bytes(head) + body
    mdict = dict_block([(name, u32(8 + dict_size(1, 4)))], 4)
    block = b'MDL0' + u32(8 + len(mdict) + len(model)) + mdict + model
    return block

def box(x0, x1, y0, y1, z0, z1):
    return [
        [(x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0)],  # top
        [(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)],  # south (towards the camera)
        [(x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1)],  # east
        [(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)],  # west
        [(x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0)],  # north
    ]

# ---- field files --------------------------------------------------------------------------

def bdhc(plates):
    """plates: [(x0, z0, x1, z1, (nx, ny, nz), d)] in block-local units."""
    pts, nrm, con, pl = [], [], [], []
    for x0, z0, x1, z1, n, d in plates:
        pts.append((x0, z0)); pts.append((x1, z1))
        if n not in nrm: nrm.append(n)
        if d not in con: con.append(d)
        pl.append((len(pts) - 2, len(pts) - 1, nrm.index(n), con.index(d)))
    out = b'BDHC' + u16(len(pts)) + u16(len(nrm)) + u16(len(con)) + u16(len(pl)) + u16(0) + u16(0)
    for x, z in pts: out += s32(fx(x)) + s32(fx(z))
    for n in nrm: out += b''.join(s32(fx(c)) for c in n)
    for d in con: out += s32(fx(d))
    for p in pl: out += b''.join(u16(v) for v in p)
    return out

def land(perms, buildings, model, surfaces, bgs=b''):
    """perms: 1024 (behaviour, collision); buildings: [(model, x, y, z)]."""
    p = b''.join(u8(b) + u8(c) for b, c in perms)
    bl = b''
    for m, x, y, z in buildings:
        bl += s32(m) + s32(fx(x)) + s32(fx(y)) + s32(fx(z)) + s32(0) + s32(0) + s32(0) + s32(4096) * 3 + b'\0' * 8
    return u32(len(p)) + u32(len(bl)) + u32(len(model)) + u32(len(surfaces)) + u16(0x1234) + u16(len(bgs)) + bgs + p + bl + model + surfaces

def matrix(w, h, headers, altitudes, models, name=b'map'):
    out = u8(w) + u8(h) + u8(1 if headers else 0) + u8(1 if altitudes else 0) + u8(len(name)) + name
    if headers: out += b''.join(u16(v) for v in headers)
    if altitudes: out += bytes(altitudes)
    return out + b''.join(u16(v) for v in models)

def narc(members, shared=None):
    """shared: {index: index_of_member_it_repeats} (members can point at the same bytes)."""
    fat, data, where = b'', b'', {}
    for i, m in enumerate(members):
        if shared and i in shared:
            s, e = where[shared[i]]
        else:
            s = len(data)
            data += m
            e = len(data)
            while len(data) % 4: data += b'\xff'
        where[i] = (s, e)
        fat += u32(s) + u32(e)
    btaf = b'BTAF' + u32(12 + len(fat)) + u16(len(members)) + u16(0) + fat
    btnf = b'BTNF' + u32(16) + u32(4) + u16(0) + u16(1)
    gmif = b'GMIF' + u32(8 + len(data)) + data
    total = 16 + len(btaf) + len(btnf) + len(gmif)
    return b'NARC' + u16(0xFFFE) + u16(0x0100) + u32(total) + u16(16) + u16(3) + btaf + btnf + gmif

def rom(paths, code=b'IPGE'):
    """A ROM image with a file name table for {path: bytes}."""
    dirs = {''}
    for p in paths:
        parts = p.split('/')
        for i in range(1, len(parts)): dirs.add('/'.join(parts[:i]))
    dirs = sorted(dirs, key=lambda s: (s.count('/') if s else -1, s))
    ids = {d: i for i, d in enumerate(dirs)}
    order, subs, first = [], [], []
    for d in dirs:
        first.append(len(order))
        out = b''
        for f in sorted(p for p in paths if '/'.join(p.split('/')[:-1]) == d):
            nm = f.split('/')[-1].encode(); out += u8(len(nm)) + nm; order.append(f)
        for c in sorted(x for x in dirs if x and '/'.join(x.split('/')[:-1]) == d):
            nm = c.split('/')[-1].encode(); out += u8(0x80 | len(nm)) + nm + u16(0xF000 | ids[c])
        subs.append(out + b'\0')
    main, acc = b'', 8 * len(dirs)
    for i, d in enumerate(dirs):
        parent = len(dirs) if d == '' else 0xF000 | ids['/'.join(d.split('/')[:-1])]
        main += u32(acc) + u16(first[i]) + u16(parent)
        acc += len(subs[i])
    fnt = main + b''.join(subs)
    fnt_off = 0x200
    fat_off = (fnt_off + len(fnt) + 0x1FF) & ~0x1FF
    image = bytearray((fat_off + 8 * len(order) + 0x1FF) & ~0x1FF)
    fat = b''
    for p in order:
        s = len(image); image += paths[p]; fat += u32(s) + u32(len(image))
        while len(image) % 0x200: image.append(0xFF)
    image[0x0C:0x10] = code
    image[0x40:0x50] = u32(fnt_off) + u32(len(fnt)) + u32(fat_off) + u32(len(fat))
    image[fnt_off:fnt_off + len(fnt)] = fnt
    image[fat_off:fat_off + len(fat)] = fat
    return bytes(image)

# ---- the fake world -------------------------------------------------------------------------

SCALE = 64.0  # model position scale: fx16 vertices times 64 cover a whole block

def ground_model(y=16.0):
    h = 256.0
    return nitro_file(b'BMD0', [mdl0('ground', quads([[(-h, y, -h), (-h, y, h), (h, y, h), (h, y, -h)]], SCALE), 'ground', 'ground_pl', SCALE)])

def perms_block0():
    perms = [(0, 0)] * 1024
    def put(x, y, b, c=0): perms[y * 32 + x] = (b, c)
    for y in range(32):
        for x in range(32):
            if y < 4 or y >= 28 or x < 4: put(x, y, 0, 0x80)     # rocks around the edges
    for x in range(32): put(x, 4, 0, 0x80)                       # a wall across row 4
    for y in range(9, 24):
        for x in range(6, 27): put(x, y, 0x02)                   # tall grass
    for y in range(12, 16):
        for x in range(20, 25): put(x, y, 0x15)                  # sea water
    for y in range(18, 22):
        for x in range(14, 18): put(x, y, 0, 0x80)               # the house
    for y in range(12, 14):
        for x in range(9, 11): put(x, y, 0, 0x80)                # the box nobody loads
    return perms

def perms_cave():
    perms = [(0, 0)] * 1024
    for y in range(3, 14):
        for x in range(3, 14):
            inside = 4 <= x <= 12 and 4 <= y <= 12
            perms[y * 32 + x] = (0x08, 0) if inside else (0, 0x80)
    return perms

def flat(y): return (-256, -256, 256, 256, (0.0, 1.0, 0.0), -float(y))

# the slope: y = 16 + (x - 96) / 2 for local x 96..256  ->  n = (-1, 2, 0)/sqrt(5), d = -(n.x x + n.y y) at (96, 16)
SLOPE_X = 96
SN = (-1 / 5 ** 0.5, 2 / 5 ** 0.5, 0.0)
SD = -(SN[0] * SLOPE_X + SN[1] * 16)

def follower_btx():
    textures = []
    for d in range(4):           # up, down, left, right
        for step in range(2):
            px = [[0] * 32 for _ in range(32)]
            cx, cy = 16 + step, 18
            for y in range(32):
                for x in range(32):
                    if (x - cx) ** 2 + (y - cy) ** 2 <= 100 and y <= 29: px[y][x] = 1
            ex, ey = [(16, 12), (16, 24), (9, 18), (23, 18)][d]
            for y in range(ey - 1, ey + 2):
                for x in range(ex - 1, ex + 2): px[y][x] = 2
            data = b''
            for y in range(32):
                for x in range(0, 32, 2): data += u8(px[y][x] | (px[y][x + 1] << 4))
            textures.append(('tsure_poke.%d' % (d * 2 + step + 1), 32, 32, 3, data, True))
    normal = [0, rgb(248, 208, 48), rgb(40, 40, 40)] + [0] * 13
    shiny = [0, rgb(248, 120, 160), rgb(40, 40, 40)] + [0] * 13
    return nitro_file(b'BTX0', [tex0(textures, [('normal_pl', normal), ('shiny_pl', shiny)])])

def build():
    files = {}
    # map matrices: 0 the overworld, 42 the cave, the rest a single empty cell
    empty = matrix(1, 1, None, None, [0xFFFF])
    world = matrix(3, 2, [0, 33, 0, 0, 33, 0], [0] * 6, [0xFFFF, 0, 0xFFFF, 0xFFFF, 1, 0xFFFF])
    cave = matrix(1, 1, None, None, [2], name=b'm_dun2501_')
    files['a/0/4/1'] = narc([world] + [empty] * 41 + [cave])
    # land data
    b0 = land(perms_block0(), [(1, 0.0, 16.0, 64.0), (2, -96.0, 16.0, -48.0), (3, 0.0, 0.0, 0.0)], ground_model(),
              bdhc([flat(16), (SLOPE_X, -256, 256, 256, SN, SD)]), bgs=b'\x01\x00\x00\x80\x08\x14\x0c\x18')
    b1 = land([(0, 0) if 15 <= i % 32 <= 17 else (0, 0x80) for i in range(1024)], [], ground_model(), bdhc([flat(16)]))
    b2 = land(perms_cave(), [], ground_model(0.0), bdhc([flat(0)]))
    files['a/0/6/5'] = narc([b0, b1, b2])
    # area data: building set, map textures, animations, outside, light type
    day = u16(0) + u16(0) + u16(0xFFFF) + u8(1) + u8(1)
    steady = u16(0) + u16(0) + u16(0xFFFF) + u8(1) + u8(0)
    files['a/0/4/2'] = narc([day] * 87 + [steady] + [day] * 2)
    files['a/0/4/3'] = narc([u16(2) + u16(1) + u16(3)])
    # textures
    green = [rgb(64, 168, 72), rgb(56, 152, 64), rgb(80, 184, 88), rgb(48, 136, 56)]
    ground_tex = solid_tex([[(x + y) % 2 for x in range(8)] for y in range(8)])
    files['a/0/4/4'] = narc([nitro_file(b'BTX0', [tex0([('ground', 8, 8, 2, ground_tex, False)], [('ground_pl', green + [0] * 12)])])])
    red = [rgb(200, 48, 40), rgb(176, 40, 32), rgb(216, 64, 56), rgb(160, 32, 24)]
    wall_tex = solid_tex([[0] * 8 for _ in range(8)])
    files['a/0/7/0'] = narc([nitro_file(b'BTX0', [tex0([('wall', 8, 8, 2, wall_tex, False)], [('wall_pl', red + [0] * 12)])])])
    # building models: 0 a dummy, 1 the house (textured by the area's set), 2 a box, 3 a floating layer
    house = nitro_file(b'BMD0', [mdl0('house', quads(box(-32, 32, 0, 48, -32, 32), SCALE), 'wall', 'wall_pl', SCALE, both_sides=True)])
    dummy = nitro_file(b'BMD0', [mdl0('dummy', quads(box(-2, 2, 0, 4, -2, 2), SCALE), None, None, SCALE)])
    other = nitro_file(b'BMD0', [mdl0('box', quads(box(-16, 16, 0, 32, -16, 16), SCALE), None, None, SCALE, both_sides=True, color=rgb(40, 40, 248))])
    layer = nitro_file(b'BMD0', [mdl0('wind', quads([[(-200, 160, -200), (-200, 160, 200), (200, 160, 200), (200, 160, -200)]], SCALE), None, None, SCALE, color=0x7FFF)])
    files['a/0/4/0'] = narc([dummy, house, other, layer])
    files['a/1/4/8'] = narc([dummy])
    # follower sprites: one set of frames shared by every member
    btx = follower_btx()
    count = 297 + 0x224 + 2
    files['a/0/8/1'] = narc([b''] * 297 + [btx] + [b''] * (count - 298), shared={i: 297 for i in range(298, count)})
    return files

if __name__ == '__main__':
    files = build()
    open(sys.argv[1], 'wb').write(rom(files))
    # a ROM without the map files at all
    open(sys.argv[2], 'wb').write(rom({'a/0/6/5': files['a/0/6/5']}))
    print('rom', sys.argv[1], 'and', sys.argv[2])
