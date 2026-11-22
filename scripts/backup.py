#!/usr/bin/env python3
"""Back up one configured MongoDB database and its upload directory. No secrets in command arguments."""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse
from envfile import environment
from retention import prune

parser = argparse.ArgumentParser()
parser.add_argument('--maintenance-confirmed', action='store_true', help='API writers are stopped for a consistent database/files snapshot')
parser.add_argument('--check', action='store_true', help='Validate prerequisites without connecting to MongoDB')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
env = environment(root / '.env')
os.umask(0o077)
uri = env.get('MONGODB_URI', '')
if not urlparse(uri).path.strip('/'):
    raise SystemExit('MONGODB_URI must name a database; refusing an all-database backup')
if not shutil.which('mongodump'): raise SystemExit('Install MongoDB Database Tools (mongodump)')
uploads = Path(env.get('STORAGE_DIR', './data/uploads'))
if not uploads.is_absolute(): uploads = root / uploads
uploads = uploads.resolve()
if not uploads.is_dir(): raise SystemExit('STORAGE_DIR does not exist')
backup_root = Path(env.get('BACKUP_DIR', str(root / 'data/backups'))).resolve()
if backup_root == uploads or uploads in backup_root.parents:
    raise SystemExit('BACKUP_DIR must be outside STORAGE_DIR')
try:
    if int(env.get('BACKUP_KEEP_DAYS', '14') or '14') < 1: raise ValueError()
except ValueError:
    raise SystemExit('BACKUP_KEEP_DAYS must be a whole number of days, at least 1')
if args.check:
    print('Backup prerequisites: PASS'); raise SystemExit(0)
if not args.maintenance_confirmed:
    raise SystemExit('Stop API writers first, then pass --maintenance-confirmed; see docs/OPERATIONS.md')
backup_root.mkdir(parents=True, exist_ok=True, mode=0o700)
stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
final = backup_root / ('fpc-' + stamp)
with tempfile.TemporaryDirectory(prefix='.fpc-incomplete-', dir=backup_root) as temp:
    temp = Path(temp)
    # YAML accepts JSON-quoted strings; keeps the URI/password out of ps output.
    with tempfile.TemporaryDirectory(prefix='fpc-mongo-config-') as config_dir:
        config = Path(config_dir) / 'mongo.yml'
        config.write_text('uri: ' + json.dumps(uri) + '\n'); config.chmod(0o600)
        result = subprocess.run(['mongodump', '--config='+str(config), '--archive='+str(temp/'mongodb.archive.gz'), '--gzip'],
                                stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, env=env)
        if result.returncode:
            raise SystemExit('mongodump failed; check connectivity and database permissions (credentials suppressed)')
    # Refuse symlinks so the backup cannot include files outside the upload root.
    if any(p.is_symlink() for p in uploads.rglob('*')): raise SystemExit('Symlink in uploads; backup refused')
    shutil.copytree(uploads, temp/'uploads', symlinks=False)
    checksums = {str(p.relative_to(temp)): hashlib.file_digest(p.open('rb'), 'sha256').hexdigest()
                 for p in temp.rglob('*') if p.is_file()}
    (temp/'manifest.json').write_text(json.dumps({'createdAt':stamp,'sha256':checksums}, indent=2)+'\n')
    # Config files and MAIL_ENCRYPTION_KEY are intentionally NOT copied into a data backup.
    shutil.move(str(temp), final)
print('Backup completed: ' + str(final))
# Delete old local backups (default: keep 14 days, and always the newest 3) so the disk never fills up.
keep_days = int(env.get('BACKUP_KEEP_DAYS', '14') or '14')
removed = prune(backup_root, keep_days=keep_days, keep_min=3)
print(f'Old local backups removed: {len(removed)} (keeping {keep_days} days)')
if env.get('RESTIC_REPOSITORY'):
    if not shutil.which('restic'): raise SystemExit('Local backup succeeded, but encrypted offsite copy needs restic installed')
    result = subprocess.run(['restic', 'backup', str(final), '--tag', 'fpc-bazaar'], env=env,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if result.returncode: raise SystemExit('Local backup succeeded; offsite restic backup FAILED')
    print('Encrypted offsite backup: PASS')
else:
    print('Offsite copy: NOT CONFIGURED (local backup only)')
