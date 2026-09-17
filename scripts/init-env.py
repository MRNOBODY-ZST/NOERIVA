#!/usr/bin/env python3
"""Create credentials for the loopback-only COMPACT environment, never overwrite."""
import os
from pathlib import Path
import secrets
import base64

target = Path(__file__).resolve().parents[1] / ".env"
names = (
    "NOERIVA_DB_PASSWORD",
    "NOERIVA_DB_ROOT_PASSWORD",
    "NOERIVA_REDIS_PASSWORD",
    "NOERIVA_CLICKHOUSE_PASSWORD",
    "NOERIVA_BOOTSTRAP_PASSWORD",
    "NOERIVA_COLLECTOR_PASSWORD",
    "NOERIVA_GRAFANA_PASSWORD",
    "NOERIVA_METRICS_PASSWORD",
)
try:
    descriptor = os.open(target, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
except FileExistsError:
    print(f"Existing {target} preserved; credentials were not replaced.")
else:
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        output.write("# Generated local COMPACT credentials. Never commit or reuse in production.\n")
        for name in names:
            output.write(f"{name}={secrets.token_urlsafe(32)}\n")
    print(f"Created {target} with owner-only permissions; {len(names)} unique local credentials.")

# Add the encryption key once to older local installs; never rotate an existing key implicitly.
if not any(line.startswith("NOERIVA_CREDENTIAL_KEY=") for line in target.read_text().splitlines()):
    with target.open("a", encoding="utf-8") as output:
        output.write("\nNOERIVA_CREDENTIAL_KEY=" + base64.b64encode(secrets.token_bytes(32)).decode() + "\n")
    target.chmod(0o600)
    print("Added the device credential encryption key without changing existing credentials.")

if not any(line.startswith("NOERIVA_ELASTICSEARCH_PASSWORD=") for line in target.read_text().splitlines()):
    with target.open("a", encoding="utf-8") as output:
        output.write("\nNOERIVA_ELASTICSEARCH_PASSWORD=" + secrets.token_urlsafe(40) + "\n")
    target.chmod(0o600)
    print("Added a separate local Elasticsearch credential; existing keys preserved.")

values = dict(line.split("=", 1) for line in target.read_text().splitlines()
              if line and not line.startswith("#") and "=" in line)
metric_password = values.get("NOERIVA_METRICS_PASSWORD", "").strip().strip("\"'")
if not metric_password:
    raise SystemExit("Existing .env lacks NOERIVA_METRICS_PASSWORD; add a separate random credential before starting vmagent.")
private_dir = target.parent / ".local"
private_dir.mkdir(mode=0o700, exist_ok=True)
private_dir.chmod(0o700)
metric_file = private_dir / "metrics-password"
if metric_file.exists():
    if metric_file.read_text() != metric_password:
        raise SystemExit("Existing metrics-password differs from .env; rotate the mounted file explicitly.")
else:
    # The parent is owner-only; the direct read-only container bind must permit a non-root UID.
    descriptor = os.open(metric_file, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o400)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        output.write(metric_password)
metric_file.chmod(0o444)
print("Prepared the metrics-only password file in owner-only .local/.")
