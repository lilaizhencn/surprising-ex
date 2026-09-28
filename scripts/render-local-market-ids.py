#!/usr/bin/env python3
"""Resolve the local seed markets once; reuse their permanent IDs on later starts."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys

repository = Path(__file__).resolve().parent.parent
runtime = Path(sys.argv[1])
manifest = runtime / "market-ids.json"
names = (repository / "deployment/local-perpetual/symbols.txt").read_text().split()
if not all(re.fullmatch(r"[A-Z0-9-]+", name) for name in names):
    raise SystemExit("invalid local market seed names")
env = os.environ.copy()
env.update(PGHOST=env["POSTGRES_HOST"], PGPORT=env["POSTGRES_PORT"], PGDATABASE=env["POSTGRES_DB"],
           PGUSER=env["POSTGRES_USER"], PGPASSWORD=env["POSTGRES_PASSWORD"])

def query(sql):
    return subprocess.check_output(["psql", "-X", "-At", "-v", "ON_ERROR_STOP=1", "-c", sql], env=env, text=True).strip()

if manifest.exists():
    ids = json.loads(manifest.read_text())
else:
    # Name lookup is restricted to initial import. Renaming a configured market never reallocates it.
    selected = ",".join("'" + name + "'" for name in names)
    result = query("SELECT json_object_agg(symbol,instrument_id) FROM instruments WHERE product_line='LINEAR_PERPETUAL' AND symbol IN (" + selected + ")")
    ids = json.loads(result or "{}")
if set(ids) != set(names) or any(type(value) is not int or value <= 0 for value in ids.values()):
    raise SystemExit("exactly 20 permanent local market IDs must be configured")
joined = ",".join(str(ids[name]) for name in names)
count = query("SELECT count(*) FROM instruments WHERE product_line='LINEAR_PERPETUAL' AND status='TRADING' AND instrument_id IN (" + joined + ")")
if int(count) != len(names):
    raise SystemExit("a configured local instrument ID is absent or not trading")
manifest.write_text(json.dumps(ids, indent=2) + "\n")
text = (repository / "deployment/local-perpetual/application-local.yml").read_text()
for name, identifier in ids.items():
    text = text.replace("@" + name + "@", str(identifier))
if re.search(r"@[A-Z0-9-]+@", text):
    raise SystemExit("unresolved local market ID placeholder")
maker_accounts = sorted({int(account.strip())
                         for group in re.findall(r"account-ids: \[([^\]]+)\]", text)
                         for account in group.split(",")})
if not maker_accounts or any(account <= 0 for account in maker_accounts):
    raise SystemExit("local market maker account IDs must be configured")
text = text.replace("surprising:\n", "surprising:\n  trade-export:\n    market-maker-account-ids: ["
                    + ", ".join(map(str, maker_accounts)) + "]\n", 1)
(runtime / "application-local.yml").write_text(text)
(runtime / "market-ids.env").write_text(
    "PRICE_CONSUMER_REQUIRED_INSTRUMENT_IDS=" + joined + "\n"
    "PRICE_INDEX_REQUIRED_INSTRUMENT_IDS=" + joined + "\n"
    "MM_INSTRUMENT_ID=" + str(ids[names[0]]) + "\n")
print("Configured " + str(len(ids)) + " permanent local instrument IDs")
