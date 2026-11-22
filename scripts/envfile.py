"""Read dotenv values as data (never execute shell substitutions or overwrite the environment)."""
import os
from pathlib import Path

def load(path):
    values = {}
    for line in Path(path).read_text().splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith('export '):
            line = line[7:].strip()
        key, sep, value = line.partition('=')
        if not sep or not key.replace('_', '').isalnum():
            raise SystemExit('Invalid dotenv entry; expected KEY=value')
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in '\"\'':
            value = value[1:-1]
        values[key] = value
    return values

def environment(path):
    env = {**(load(path) if Path(path).exists() else {}), **os.environ}
    # Spring's direct APP_MAIL_FROM otherwise shadows the documented MAIL_FROM placeholder.
    # Keep the runner and the Brevo test tool on the same explicitly configured sender.
    if env.get('MAIL_FROM'):
        env['APP_MAIL_FROM'] = env['MAIL_FROM']
    return env
