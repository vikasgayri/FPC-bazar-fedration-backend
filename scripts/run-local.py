#!/usr/bin/env python3
"""Run an isolated local MongoDB + API. Never connects to MONGODB_URI from .env."""
import os
import shutil
import signal
import socket
import subprocess
import time
from pathlib import Path
from envfile import environment

root = Path(__file__).resolve().parents[1]
os.chdir(root)
os.umask(0o077)
env = environment(root / '.env')
api_port = int(os.environ.get('LOCAL_API_PORT', '8081'))
mongo_port = int(os.environ.get('LOCAL_MONGO_PORT', '27018'))
for port in (api_port, mongo_port):
    with socket.socket() as check:
        try: check.bind(('127.0.0.1', port))
        except OSError: raise SystemExit(f'Port {port} is busy. Choose LOCAL_API_PORT / LOCAL_MONGO_PORT or stop the earlier local run.')
executable = os.environ.get('MONGOD_BIN') or shutil.which('mongod')
if not executable: raise SystemExit('Install MongoDB Community Server or set MONGOD_BIN to its executable')
jar = root / 'target/fpc-bazaar-backend-0.1.0.jar'
if not jar.exists(): raise SystemExit('Build first: ./mvnw package')
data = root / 'data/local'
(data / 'mongo').mkdir(parents=True, exist_ok=True)
(data / 'uploads').mkdir(exist_ok=True)
env.update(MONGODB_URI=f'mongodb://127.0.0.1:{mongo_port}/fpcbazaar_local',
           STORAGE_DIR=str(data/'uploads'), PORT=str(api_port), BIND_ADDRESS='127.0.0.1',
           PUBLIC_BASE_URL=f'http://localhost:{api_port}', SEED_DEMO='false',
           MAIL_SANDBOX=env.get('LOCAL_MAIL_SANDBOX', 'true'))
children = []
def stop(signum, frame): raise KeyboardInterrupt()
signal.signal(signal.SIGTERM, stop)
try:
    with (data/'mongo-console.log').open('a') as log:
        mongo = subprocess.Popen([executable, '--bind_ip', '127.0.0.1', '--port', str(mongo_port),
                                  '--dbpath', str(data/'mongo'), '--logpath', str(data/'mongo.log'), '--logappend'],
                                 stdout=log, stderr=log, start_new_session=True)
    children.append(mongo)
    for _ in range(100):
        if mongo.poll() is not None: raise SystemExit('Local MongoDB failed; inspect data/local/mongo.log')
        try:
            with socket.create_connection(('127.0.0.1', mongo_port), timeout=.2): break
        except OSError: time.sleep(.1)
    else: raise SystemExit('Local MongoDB startup timed out')
    print(f'Isolated local API: http://localhost:{api_port}', flush=True)
    print('Atlas is not used. Mail sandbox='+env['MAIL_SANDBOX'], flush=True)
    app = subprocess.Popen(['java', '-jar', str(jar)], env=env, start_new_session=True)
    children.append(app)
    raise SystemExit(app.wait())
except KeyboardInterrupt:
    pass
finally:
    for process in reversed(children):
        if process.poll() is None:
            process.terminate()
            try: process.wait(timeout=30)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
