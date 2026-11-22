#!/usr/bin/env python3
"""Validate local configuration without printing values or contacting services."""
import base64
import sys
from pathlib import Path
from urllib.parse import urlparse
from envfile import environment

env = environment(Path(__file__).resolve().parents[1] / '.env')
errors = []
for key in ['MONGODB_URI', 'JWT_SECRET', 'ADMIN_USERNAME', 'ADMIN_PASSWORD']:
    print(key + ': ' + ('SET' if env.get(key) else 'MISSING'))
    if not env.get(key): errors.append(key + ' is missing')
if len(env.get('JWT_SECRET', '').encode()) < 32: errors.append('JWT_SECRET needs at least 32 bytes')
password = env.get('ADMIN_PASSWORD', '')
if len(password) < 10 or len(password.encode()) > 72: errors.append('ADMIN_PASSWORD must be 10 characters to 72 bytes')
if not env.get('MONGODB_URI', '').startswith(('mongodb://', 'mongodb+srv://')): errors.append('MONGODB_URI scheme is invalid')
print('BREVO_API_KEY: ' + ('SET' if env.get('BREVO_API_KEY') else 'MISSING'))
print('MAIL_ENABLED: ' + env.get('MAIL_ENABLED', 'false'))
print('MAIL_SANDBOX: ' + env.get('MAIL_SANDBOX', 'false'))
if env.get('MAIL_ENABLED', '').lower() == 'true':
    if not env.get('BREVO_API_KEY'): errors.append('BREVO_API_KEY is missing')
    sender = env.get('MAIL_FROM', '')
    if '@' not in sender or sender.endswith('@example.com'): errors.append('MAIL_FROM needs a real Brevo sender')
    try:
        if len(base64.b64decode(env.get('MAIL_ENCRYPTION_KEY', ''), validate=True)) != 32: raise ValueError()
    except ValueError: errors.append('MAIL_ENCRYPTION_KEY must encode 32 random bytes')
for key in ['PUBLIC_BASE_URL', 'FRONTEND_URL']:
    uri = urlparse(env.get(key, ''))
    if uri.scheme not in ('http', 'https') or not uri.hostname: errors.append(key + ' needs an http(s) URL')
for error in errors: print('ERROR: ' + error)
print('Local config: ' + ('FAIL' if errors else 'PASS'))
sys.exit(bool(errors))
