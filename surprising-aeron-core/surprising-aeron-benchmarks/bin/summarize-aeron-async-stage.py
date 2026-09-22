#!/usr/bin/env python3
"""Reduce one staged real-Aeron run to auditable saturation metrics."""
from __future__ import annotations
import argparse, json, math, os, re, subprocess
from datetime import datetime
from pathlib import Path


def f(pattern: str, text: str, default=float("nan")):
    m = re.search(pattern, text)
    return float(m.group(1)) if m else default


def i(pattern: str, text: str, default=-1):
    m = re.search(pattern, text)
    return int(float(m.group(1))) if m else default


def parse_jfr(path: Path, start: datetime | None, end: datetime | None):
    result = {"samples": 0, "threads": {}, "logicalCpus": os.cpu_count() or 1}
    if not path.exists() or path.stat().st_size == 0:
        return result
    java = os.environ.get("SURPRISING_JAVA_HOME") or os.environ.get("JAVA_HOME")
    jfr = str(Path(java) / "bin/jfr") if java else "jfr"
    try:
        raw = subprocess.check_output([jfr, "print", "--json", "--events", "jdk.ThreadCPULoad", str(path)], text=True)
        data = json.loads(raw)
    except (OSError, subprocess.CalledProcessError, json.JSONDecodeError):
        return result
    for event in data.get("recording", {}).get("events", []):
        values = event.get("values", {})
        try:
            when = datetime.fromisoformat(values["startTime"])
        except (KeyError, ValueError):
            continue
        if start and when < start or end and when > end:
            continue
        thread = values.get("eventThread", {})
        name = thread.get("javaName") or thread.get("osName") or "unknown"
        user = float(values.get("user") or 0.0)
        system = float(values.get("system") or 0.0)
        slot = result["threads"].setdefault(name, {"samples": 0, "machinePercent": 0.0})
        slot["samples"] += 1
        slot["machinePercent"] += (user + system) * 100.0
        result["samples"] += 1
    cpus = os.cpu_count() or 1
    for slot in result["threads"].values():
        if slot["samples"]:
            slot["machinePercent"] /= slot["samples"]
            slot["singleCorePercent"] = slot["machinePercent"] * cpus
    result["logicalCpus"] = cpus
    return result


def add_saturation_assessment(metric):
    """Report observed load separately from unproven business saturation.

    CPU includes idle spinning; high-water marks contain no duration; Lane
    execution intervals include preemption/GC and are not useful CPU time.
    None of these alone, or together, proves a stage's processing capacity.
    """
    seconds = metric["steadyMeasurementSeconds"]
    blocked = metric["windowBlockedNanos"]
    valid_window = isinstance(seconds, (int, float)) and math.isfinite(seconds) and seconds > 0
    ratio = blocked / (seconds * 1e9) if valid_window and blocked >= 0 else None
    valid_blocked = ratio is not None and 0 <= ratio <= 1
    metric["loadEvidence"] = {
        "windowReached": (metric["peakInFlight"] >= metric["window"]
                          if metric["peakInFlight"] is not None else None),
        "windowBackpressureObserved": blocked > 0 if blocked >= 0 else None,
        "windowBlockedTimeRatio": ratio if valid_blocked else None,
        "windowBlockedTimeValid": valid_blocked,
        "queueEvidence": "HIGH_WATER_ONLY" if metric["pipelineHighWater"] else "MISSING",
    }
    reasons = [
        "sustained-queue-occupancy-not-measured",
        "useful-processing-idle-and-dependency-wait-not-separated",
        "throughput-plateau-under-increasing-load-not-established",
    ]
    if metric["jfr"]["threadCpuSamples"] == 0:
        reasons.append("thread-cpu-evidence-missing")
    if not valid_blocked:
        reasons.append("window-blocked-time-missing-or-invalid")
    if not metric["clientPass"]:
        reasons.insert(0, "client-functional-check-failed-or-missing")
    metric["saturationGate"] = {
        "status": "UNCONFIRMED" if metric["clientPass"] else "INVALID",
        "target": metric["stage"],
        "reasons": reasons,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("stage_dir", type=Path)
    ap.add_argument("--stage", required=True, choices=("owner", "matcher", "lane", "end_to_end"))
    ap.add_argument("--window", type=int, required=True)
    args = ap.parse_args()
    root = args.stage_dir
    client = (root / "client.log").read_text(errors="replace") if (root / "client.log").exists() else ""
    start_epoch = f(r"measurementStartEpochMillis=(\d+)", client, float("nan"))
    end_epoch = f(r"measurementEndEpochMillis=(\d+)", client, float("nan"))
    start = datetime.fromtimestamp(start_epoch / 1000).astimezone() if math.isfinite(start_epoch) else None
    end = datetime.fromtimestamp(end_epoch / 1000).astimezone() if math.isfinite(end_epoch) else None
    jfr = parse_jfr(root / "node.jfr", start, end)
    thread = jfr["threads"]
    def cpu(prefix):
        vals = [v["singleCorePercent"] for n, v in thread.items() if prefix in n]
        return max(vals) if vals else float("nan")
    lanes = [v["singleCorePercent"] for n, v in thread.items() if "core-account-lane-" in n]
    lane_work = {}
    for m in re.finditer(r"laneWork lane=(\d+) operation=(\d+) completed=(\d+) executionNanos=(\d+) measuredNanos=(\d+)", client):
        lane, op, completed, execution, measured = map(int, m.groups())
        lane_work.setdefault(lane, {"operations": {}, "executionWallNanos": 0, "measuredNanos": measured})
        lane_work[lane]["operations"][op] = {"completed": completed, "executionNanos": execution}
        lane_work[lane]["executionWallNanos"] += execution
    for value in lane_work.values():
        value["executionWallRatio"] = value["executionWallNanos"] / value["measuredNanos"] if value["measuredNanos"] else 0.0
    p99_matches = [float(x) for x in re.findall(r"business=[^\n]*p99us=(\d+)", client)]
    high = re.search(r"pipelineHighWater matcher=(\d+) completion=(\d+) context=(\d+) lanes=\[([^]]*)\]", client)
    mixed = re.search(r"steadyCapacity[^\n]*businessOpsPerSec=([0-9.]+)[^\n]*coreMessagesPerSec=([0-9.]+)[^\n]*peakInFlight=(\d+)", client)
    metric = {
        "stage": args.stage, "window": args.window,
        "steadyMeasurementSeconds": f(r"steadyCapacity elapsedSeconds=([0-9.]+)", client, None),
        "drainNanos": i(r"drain elapsedNanos=(\d+)", client),
        "drainBusinessOperations": i(r"drain [^\n]*terminalBusinessOperations=(\d+)", client),
        "windowBlockedNanos": i(r"windowBlockedNanos=(\d+)", client),
        "clientPass": "mixedCapacity=PASS" in client,
        "businessOpsPerSec": float(mixed.group(1)) if mixed else None,
        "coreMessagesPerSec": float(mixed.group(2)) if mixed else None,
        "peakInFlight": int(mixed.group(3)) if mixed else None,
        "p99usMaxAcrossBusinessTypes": max(p99_matches) if p99_matches else None,
        "pipelineHighWater": ({"matcher": int(high.group(1)), "completion": int(high.group(2)), "context": int(high.group(3)), "lanes": [int(x.strip()) for x in high.group(4).split(",") if x.strip()]} if high else None),
        "laneWork": lane_work,
        "cpu": {
            "ownerSingleCorePercent": cpu("trading-owner"),
            "matcherSingleCorePercent": cpu("core-matcher"),
            "laneSingleCorePercentMax": max(lanes) if lanes else float("nan"),
            "laneSingleCorePercentByThread": lanes,
        },
        "jfr": {"threadCpuSamples": jfr["samples"], "logicalCpus": jfr["logicalCpus"]},
    }
    if lane_work:
        ratios = [v["executionWallRatio"] for v in lane_work.values()]
        metric["laneExecutionWallRatioMin"] = min(ratios)
        metric["laneExecutionWallRatioAvg"] = sum(ratios) / len(ratios)
    else:
        metric["laneExecutionWallRatioMin"] = None
        metric["laneExecutionWallRatioAvg"] = None
    def finite(value):
        return value if isinstance(value, (int, float)) and math.isfinite(value) else None
    metric["cpu"] = {key: ([finite(item) for item in value] if isinstance(value, list) else finite(value)) for key, value in metric["cpu"].items()}
    add_saturation_assessment(metric)
    (root / "metrics.json").write_text(json.dumps(metric, indent=2, allow_nan=False) + "\n")
    print(json.dumps(metric, indent=2, allow_nan=False))

if __name__ == "__main__":
    main()
