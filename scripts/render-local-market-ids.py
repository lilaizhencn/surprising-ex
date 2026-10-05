#!/usr/bin/env python3
"""Generate readiness probes from the current database catalog, never business configuration."""
import os
from pathlib import Path
import subprocess
import sys

runtime = Path(sys.argv[1])
env = os.environ.copy()
env.update(PGHOST=env["POSTGRES_HOST"], PGPORT=env["POSTGRES_PORT"], PGDATABASE=env["POSTGRES_DB"],
           PGUSER=env["POSTGRES_USER"], PGPASSWORD=env["POSTGRES_PASSWORD"])
result = subprocess.check_output([
    "psql", "-X", "-At", "-v", "ON_ERROR_STOP=1", "-c",
    "SELECT instrument_id FROM instruments WHERE product_line='LINEAR_PERPETUAL' "
    "AND status IN ('PRE_TRADING','TRADING','HALT') ORDER BY instrument_id"
], env=env, text=True).strip()
ids = [int(value) for value in result.splitlines()]
if any(value <= 0 for value in ids):
    raise SystemExit("invalid catalog instrument ID")
joined = ",".join(map(str, ids))
(runtime / "market-ids.env").write_text(
    "PRICE_CONSUMER_REQUIRED_INSTRUMENT_IDS=" + joined + "\n"
    "PRICE_INDEX_REQUIRED_INSTRUMENT_IDS=" + joined + "\n"
    "READINESS_INSTRUMENT_ID=" + (str(ids[0]) if ids else "") + "\n")
print("Readiness probes refreshed from " + str(len(ids)) + " visible database instruments")
