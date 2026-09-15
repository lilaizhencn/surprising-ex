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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("stage_dir", type=Path)
    ap.add_argument("--stage", required=True)
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
        lane_work.setdefault(lane, {"operations": {}, "usefulNanos": 0, "measuredNanos": measured})
        lane_work[lane]["operations"][op] = {"completed": completed, "executionNanos": execution}
        lane_work[lane]["usefulNanos"] += execution
    for value in lane_work.values():
        value["usefulExecutionRatio"] = value["usefulNanos"] / value["measuredNanos"] if value["measuredNanos"] else 0.0
    p99s = [f(r"business=[^\n]*p99us=(\d+)", client)]
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
        ratios = [v["usefulExecutionRatio"] for v in lane_work.values()]
        metric["laneUsefulExecutionRatioMin"] = min(ratios)
        metric["laneUsefulExecutionRatioAvg"] = sum(ratios) / len(ratios)
    else:
        metric["laneUsefulExecutionRatioMin"] = None
        metric["laneUsefulExecutionRatioAvg"] = None
    owner = metric["cpu"]["ownerSingleCorePercent"]
    matcher = metric["cpu"]["matcherSingleCorePercent"]
    lane = metric["cpu"]["laneSingleCorePercentMax"]
    def finite(value):
        return value if isinstance(value, (int, float)) and math.isfinite(value) else None
    metric["cpu"] = {key: ([finite(item) for item in value] if isinstance(value, list) else finite(value)) for key, value in metric["cpu"].items()}
    owner = owner if math.isfinite(owner) else 0.0
    matcher = matcher if math.isfinite(matcher) else 0.0
    lane = lane if math.isfinite(lane) else 0.0
    useful = metric["laneUsefulExecutionRatioMin"] or 0.0
    high_matcher = metric["pipelineHighWater"]["matcher"] if metric["pipelineHighWater"] else 0
    thresholds = {"owner": owner >= 80.0, "matcher": matcher >= 80.0, "lane": lane >= 80.0 and useful >= 0.70}
    reasons = []
    if args.stage == "owner":
        gate = thresholds["owner"] and high_matcher >= args.window * 0.8
        if not thresholds["owner"]: reasons.append("owner-single-core-below-80-percent")
        if high_matcher < args.window * 0.8: reasons.append("matcher-high-water-below-80-percent")
    elif args.stage == "matcher":
        gate = thresholds["matcher"] and high_matcher >= args.window * 0.8
        if not thresholds["matcher"]: reasons.append("matcher-single-core-below-80-percent")
        if high_matcher < args.window * 0.8: reasons.append("matcher-high-water-below-80-percent")
        if owner >= 80.0: reasons.append("owner-upstream-is-already-saturated")
    elif args.stage == "lane":
        gate = thresholds["lane"]
        if lane < 80.0: reasons.append("lane-single-core-below-80-percent")
        if useful < 0.70: reasons.append("lane-useful-execution-below-70-percent")
    else:
        gate = metric["clientPass"] and (metric["peakInFlight"] or 0) >= args.window * 0.8
        if not metric["clientPass"]: reasons.append("client-functional-check-failed")
    metric["saturationGate"] = {"status": "PASS" if gate else "NOT_SATURATED", "target": args.stage, "thresholds": thresholds, "reasons": reasons}
    (root / "metrics.json").write_text(json.dumps(metric, indent=2, allow_nan=False) + "\n")
    print(json.dumps(metric, indent=2, allow_nan=False))

if __name__ == "__main__":
    main()
