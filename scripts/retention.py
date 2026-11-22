"""Delete old local backups so the server disk does not fill up. Used by backup.py after a successful backup."""
import re
import shutil
from datetime import datetime, timedelta, timezone
from pathlib import Path

BACKUP_NAME = re.compile(r'^fpc-(\d{8}T\d{6})\d*Z$')


def backup_time(path):
    match = BACKUP_NAME.match(path.name)
    if not match:
        return None
    return datetime.strptime(match.group(1), '%Y%m%dT%H%M%S').replace(tzinfo=timezone.utc)


def prune(backup_root, keep_days, keep_min=3, now=None):
    """Removes completed backups older than keep_days, always keeping the newest keep_min.
    Only real directories named like fpc-YYYYMMDDTHHMMSS...Z are touched; links and other files never are.
    Leftover .fpc-incomplete-* folders older than one day (from a killed run) are removed too.
    Returns the removed paths."""
    if keep_days < 1 or keep_min < 1:
        raise ValueError('keep_days and keep_min must be at least 1')
    now = now or datetime.now(timezone.utc)
    root = Path(backup_root)
    backups = sorted((p for p in root.iterdir() if p.is_dir() and not p.is_symlink() and backup_time(p)),
                     key=backup_time, reverse=True)
    removed = []
    for old in backups[keep_min:]:
        if backup_time(old) < now - timedelta(days=keep_days):
            shutil.rmtree(old)
            removed.append(old)
    for leftover in root.glob('.fpc-incomplete-*'):
        if leftover.is_dir() and not leftover.is_symlink() and \
                datetime.fromtimestamp(leftover.stat().st_mtime, timezone.utc) < now - timedelta(days=1):
            shutil.rmtree(leftover)
            removed.append(leftover)
    return removed
