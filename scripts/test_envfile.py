import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from envfile import environment

class EnvironmentTests(unittest.TestCase):
    def test_documented_sender_wins_over_spring_alias(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / '.env'
            p.write_text('MAIL_FROM=verified@example.org\n')
            with patch.dict(os.environ, {'APP_MAIL_FROM': 'unverified@example.org'}, clear=True):
                env = environment(p)
            self.assertEqual(env['APP_MAIL_FROM'], 'verified@example.org')

    def test_explicit_canonical_shell_override_is_preserved(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / '.env'
            p.write_text('MAIL_FROM=file@example.org\nLOCAL_MAIL_SANDBOX=false\n')
            with patch.dict(os.environ, {'MAIL_FROM': 'shell@example.org', 'LOCAL_MAIL_SANDBOX': 'true'}, clear=True):
                env = environment(p)
            self.assertEqual(env['APP_MAIL_FROM'], 'shell@example.org')
            self.assertEqual(env['LOCAL_MAIL_SANDBOX'], 'true')

    def test_values_are_data(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / '.env'
            p.write_text('VALUE="$(echo must-not-execute)"\n')
            with patch.dict(os.environ, {}, clear=True):
                self.assertEqual(environment(p)['VALUE'], '$(echo must-not-execute)')

if __name__ == '__main__':
    unittest.main()
