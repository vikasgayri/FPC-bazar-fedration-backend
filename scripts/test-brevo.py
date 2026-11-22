#!/usr/bin/env python3
"""Sandbox by default; --send explicitly sends ONE test email to --to."""
import argparse
import json
import ssl
import urllib.request
import urllib.error
from pathlib import Path
from envfile import environment

parser = argparse.ArgumentParser()
parser.add_argument('--to', required=True)
parser.add_argument('--send', action='store_true')
args = parser.parse_args()
env = environment(Path(__file__).resolve().parents[1] / '.env')
if not env.get('BREVO_API_KEY') or not env.get('MAIL_FROM'): raise SystemExit('Set BREVO_API_KEY and MAIL_FROM')
payload = {'sender': {'name': env.get('MAIL_FROM_NAME', 'FPC Bazaar Federation'), 'email': env['MAIL_FROM']},
           'to': [{'email': args.to}], 'subject': 'FPC Bazaar — backend email test',
           'textContent': 'Namaste! This is one requested local backend test email. Brevo email transport is working. No application or account was created by this test.'}
if not args.send: payload['headers'] = {'X-Sib-Sandbox': 'drop'}
request = urllib.request.Request('https://api.brevo.com/v3/smtp/email', data=json.dumps(payload).encode(),
    headers={'api-key': env['BREVO_API_KEY'], 'content-type': 'application/json', 'accept': 'application/json'})
context = ssl.create_default_context(cafile='/etc/ssl/cert.pem') if Path('/etc/ssl/cert.pem').exists() else ssl.create_default_context()
try:
    with urllib.request.urlopen(request, timeout=20, context=context) as response:
        result = json.load(response)
        print(json.dumps({'mode': 'live' if args.send else 'sandbox', 'status': response.status, 'messageId': result.get('messageId')}))
except urllib.error.HTTPError as e:
    try: code = json.loads(e.read()).get('code', 'unknown')
    except Exception: code = 'unknown'
    raise SystemExit(f'Brevo rejected test: HTTP {e.code}, code={code}')
except Exception as e:
    raise SystemExit('Brevo connection failed: ' + type(e).__name__)
