#!/usr/bin/env python3
"""Load .env without shell execution, then run the built backend."""
import os
from pathlib import Path
from envfile import environment

root = Path(__file__).resolve().parents[1]
os.chdir(root)
env = environment(root / '.env')
jar = root / 'target/fpc-bazaar-backend-0.1.0.jar'
if not jar.exists():
    raise SystemExit('Build first: ./mvnw package')
os.execvpe('java', ['java', '-jar', str(jar)], env)
