#!/usr/bin/env python3
import hashlib
import json
import sys
from pathlib import Path

root = Path(sys.argv[1]).resolve()
manifest = json.loads((root/'manifest.json').read_text())
expected = manifest['sha256']
for name, checksum in expected.items():
    file = (root/name).resolve()
    if root not in file.parents or not file.is_file() or file.is_symlink(): raise SystemExit('Invalid/missing backup entry')
    with file.open('rb') as stream:
        if hashlib.file_digest(stream, 'sha256').hexdigest() != checksum: raise SystemExit('Backup checksum mismatch: '+name)
actual = {str(p.relative_to(root)) for p in root.rglob('*') if p.is_file() and p.name != 'manifest.json'}
if actual != set(expected): raise SystemExit('Unexpected or missing files in backup')
print('Backup checksums: PASS ('+str(len(expected))+' files)')
