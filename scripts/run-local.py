#!/usr/bin/env python3
"""Run the built API without leaking environment secrets to command arguments."""
import os
from pathlib import Path
import subprocess
import sys
import hashlib
import shutil

root = Path(__file__).resolve().parents[1]
mode = sys.argv[1] if len(sys.argv) > 1 else "demo"
if mode not in ("demo", "production"):
    raise SystemExit("Usage: scripts/run-local.py [demo|production]")
env = os.environ.copy()
if mode == "production":
    for line in (root / ".env").read_text().splitlines():
        if line and not line.startswith("#"):
            key, value = line.split("=", 1)
            env.setdefault(key, value)
    env.setdefault("SPRING_R2DBC_URL", "r2dbc:mysql://127.0.0.1:13306/noeriva?sslMode=DISABLED&allowPublicKeyRetrieval=true")
    env.setdefault("SPRING_FLYWAY_URL", "jdbc:mysql://127.0.0.1:13306/noeriva?allowPublicKeyRetrieval=true&sslMode=DISABLED")
    env.setdefault("NOERIVA_ROLLUP_ENABLED", "true")
    env.setdefault("NOERIVA_CLICKHOUSE_URL", "http://127.0.0.1:18123")
    env.setdefault("NOERIVA_METRICS_URL", "http://127.0.0.1:18428")
env.setdefault("JAVA_TOOL_OPTIONS", "--enable-native-access=ALL-UNNAMED -XX:MaxRAMPercentage=30")
jar = root / "services/noeriva-control/target/noeriva-control-0.1.0-SNAPSHOT.jar"
if not jar.exists():
    raise SystemExit("Build the API first: ./mvnw package")
digest = hashlib.sha256(jar.read_bytes()).hexdigest()[:16]
runtime_jar = root / ".local" / "runtime" / digest / "app.jar"
if not runtime_jar.exists():
    runtime_jar.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(jar, runtime_jar)
    runtime_jar.chmod(0o444)
port = env.get("NOERIVA_LOCAL_API_PORT", "18081" if mode == "demo" else "18080")
print(f"Starting NOERIVA {mode} on http://127.0.0.1:{port}", flush=True)
raise SystemExit(subprocess.call(["java", "-jar", str(runtime_jar), f"--spring.profiles.active={mode}", f"--server.port={port}", "--server.address=127.0.0.1"], cwd=root, env=env))
