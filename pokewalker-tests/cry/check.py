"""Compares CryTest's output with what gen_cry_rom.py says a correct reader produces."""
import json, sys
got = json.load(open(sys.argv[1]))
exp = json.load(open(sys.argv[2]))
ok = True
for k in ('25', '26'):
    same = got[k]['rate'] == exp[k]['rate'] and got[k]['samples'] == exp[k]['samples']
    ok &= same
    print(k, 'rate', got[k]['rate'], 'samples', len(got[k]['samples']), 'ok' if same else 'MISMATCH')
for k, want in (('err27', 'no bank 27'), ('err600', 'species 600'), ('missing', 'null')):
    same = got[k] == want
    ok &= same
    print(k, got[k], 'ok' if same else 'MISMATCH')
print('ALL OK' if ok else 'FAILED')
sys.exit(0 if ok else 1)
