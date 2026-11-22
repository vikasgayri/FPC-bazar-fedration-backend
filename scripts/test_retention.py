import os
import tempfile
import time
import unittest
from datetime import datetime, timezone
from pathlib import Path
from retention import prune

NOW = datetime(2026, 11, 30, 2, 30, tzinfo=timezone.utc)


def make(root, day):
    p = Path(root) / f'fpc-202611{day:02d}T023000123456Z'
    p.mkdir()
    (p / 'manifest.json').write_text('{}')
    return p


class RetentionTests(unittest.TestCase):
    def test_keeps_last_days_and_deletes_older(self):
        with tempfile.TemporaryDirectory() as d:
            for day in range(1, 30):
                make(d, day)
            removed = prune(d, keep_days=14, now=NOW)
            left = sorted(p.name for p in Path(d).iterdir())
            self.assertEqual(len(removed), 15)                 # 1..15 Nov are older than 14 days
            self.assertTrue(left[0].startswith('fpc-20261116'))
            self.assertEqual(len(left), 14)                    # 16..29 Nov: the last 14 days

    def test_always_keeps_newest_even_if_old(self):
        with tempfile.TemporaryDirectory() as d:
            for day in (1, 2, 3, 4):
                make(d, day)
            prune(d, keep_days=7, keep_min=3, now=NOW)        # server was off for weeks
            self.assertEqual(sorted(p.name[:12] for p in Path(d).iterdir()),
                             ['fpc-20261102', 'fpc-20261103', 'fpc-20261104'])

    def test_never_touches_other_files_or_links(self):
        with tempfile.TemporaryDirectory() as d, tempfile.TemporaryDirectory() as outside:
            for day in range(1, 10):
                make(d, day)
            (Path(d) / 'notes.txt').write_text('keep me')
            (Path(d) / 'my-folder').mkdir()
            target = Path(outside) / 'precious'
            target.mkdir()
            os.symlink(target, Path(d) / 'fpc-20260101T000000Z')
            prune(d, keep_days=1, keep_min=1, now=NOW)
            self.assertTrue((Path(d) / 'notes.txt').exists())
            self.assertTrue((Path(d) / 'my-folder').exists())
            self.assertTrue(target.exists())

    def test_old_incomplete_folders_are_cleaned(self):
        with tempfile.TemporaryDirectory() as d:
            stale = Path(d) / '.fpc-incomplete-abc'
            stale.mkdir()
            old = time.time() - 3 * 86400
            os.utime(stale, (old, old))
            fresh = Path(d) / '.fpc-incomplete-new'
            fresh.mkdir()
            prune(d, keep_days=14, now=datetime.now(timezone.utc))
            self.assertFalse(stale.exists())
            self.assertTrue(fresh.exists())

    def test_rejects_nonsense_settings(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(ValueError):
                prune(d, keep_days=0)


if __name__ == '__main__':
    unittest.main()
