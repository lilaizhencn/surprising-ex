#!/usr/bin/env python3
"""Periodic snapshots for the owned local single-node perpetual demo; never delete data."""
import argparse
import json
import re
import shutil
import subprocess
import time
import urllib.request
from pathlib import Path


def latest_complete_snapshot(recording_log):
    """Only a valid service + consensus pair is a completed recovery checkpoint."""
    pairs = {}
    for entry in re.findall(r"Entry\{([^}]+)\}", recording_log):
        fields = dict(item.split("=", 1) for item in entry.split(", "))
        if fields.get("type") != "SNAPSHOT" or fields.get("isValid") != "true":
            continue
        key = (int(fields["timestamp"]), int(fields["logPosition"]), int(fields["leadershipTermId"]))
        pairs.setdefault(key, set()).add(int(fields["serviceId"]))
    return max((key[:2] for key, services in pairs.items() if {-1, 0} <= services), default=(0, 0))


def checkpoint(runtime, java, tools_jar, interval):
    if shutil.disk_usage(runtime).free < 5 * 1024**3:
        raise RuntimeError("less than 5 GiB disk space; snapshot skipped")
    cluster = runtime / "runtime/local-perpetual/aeron/linear_perpetual/node0/cluster"
    command = [str(java), "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
               "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", str(tools_jar),
               "io.aeron.cluster.ClusterTool", str(cluster)]

    def tool(action):
        result = subprocess.run(command + [action], capture_output=True, text=True, timeout=15)
        if result.returncode:
            raise RuntimeError(f"ClusterTool {action} failed: {(result.stderr or result.stdout)[-500:]}")
        return result.stdout

    previous = latest_complete_snapshot(tool("recording-log"))
    if time.time() * 1000 - previous[0] < interval * 1000:
        return "RECENT", previous
    # Do not interrupt replay or mistake HTTP liveness for trading readiness.
    catalog_request = urllib.request.Request(
        "http://127.0.0.1:9094/api/v1/gateway/instrument/list",
        headers={"X-Product-Line": "LINEAR_PERPETUAL"})
    with urllib.request.urlopen(catalog_request, timeout=5) as response:
        instruments = json.load(response)["instruments"]
    if not instruments:
        raise RuntimeError("no visible contract available for Core readiness probe")
    instrument_id = int(instruments[0]["instrumentId"])
    request = urllib.request.Request(
        f"http://127.0.0.1:9094/api/v1/gateway/trading-market/orderbook?instrumentId={instrument_id}&depth=1",
        headers={"X-Product-Line": "LINEAR_PERPETUAL"})
    with urllib.request.urlopen(request, timeout=5) as response:
        book = json.load(response)
        if not isinstance(book.get("bids"), list) or not isinstance(book.get("asks"), list):
            raise RuntimeError("Core order-book query not ready")
    tool("is-leader")
    tool("snapshot")
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        current = latest_complete_snapshot(tool("recording-log"))
        if current[0] > previous[0]:
            return "COMPLETED", current
        time.sleep(2)
    raise RuntimeError("snapshot completion not confirmed within 120 seconds")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--tools-jar", type=Path, required=True)
    parser.add_argument("--interval", type=int, default=300)
    parser.add_argument("--once", action="store_true")
    args = parser.parse_args()
    if args.interval < 60:
        parser.error("interval must be at least 60 seconds")
    while True:
        try:
            status, (timestamp, position) = checkpoint(args.runtime, args.java, args.tools_jar, args.interval)
            if status == "COMPLETED" or args.once:
                print(f"SNAPSHOT={status} timestamp={timestamp} position={position}", flush=True)
        except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
            print(f"SNAPSHOT=UNAVAILABLE reason={error}", flush=True)
            if args.once:
                return 1
        if args.once:
            return 0
        time.sleep(60)


if __name__ == "__main__":
    raise SystemExit(main())
