#!/usr/bin/env python3
"""Audit declared Maven SBOM / Bun / pnpm locks against public advisory APIs; no application credentials are read."""
import argparse
import datetime
import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request


def post(url, payload):
    request = urllib.request.Request(url, data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
    for attempt in range(2):
        try:
            with urllib.request.urlopen(request, timeout=25) as response:
                body = response.read(8 * 1024 * 1024 + 1)
                if len(body) > 8 * 1024 * 1024:
                    raise ValueError("advisory response exceeds limit")
                return json.loads(body)
        except (urllib.error.URLError, TimeoutError):
            if attempt:
                raise
            time.sleep(1)


def npm_packages(path):
    code = """
      const path=Bun.argv.at(-1);
      const text=await Bun.file(path).text();
      const bun=path.endsWith('bun.lock');
      const lock=bun?Bun.JSONC.parse(text):Bun.YAML.parse(text);
      const packages={};
      for(const [key,value] of Object.entries(lock.packages??{})) {
        const spec=bun?value[0]:key;
        const at=spec.lastIndexOf('@');
        const name=spec.slice(0,at),version=spec.slice(at+1).split('(')[0];
        if(at<1 || !/^\\d/.test(version)) throw Error('Unsupported registry package in lock');
        (packages[name]??=[]).push(version);
      }
      console.log(JSON.stringify(packages));
    """
    result = subprocess.run(["bun", "--no-env-file", "-e", code, str(path.resolve())], capture_output=True, text=True, check=True)
    packages = json.loads(result.stdout)
    if not packages:
        raise ValueError("lock has no auditable registry packages")
    return {name: sorted(set(versions)) for name, versions in packages.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bom", type=Path)
    parser.add_argument("--npm-lock", action="append", type=Path, default=[])
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.bom and not args.npm_lock:
        parser.error("provide a Maven SBOM or an npm registry lock")
    report = {"checkedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(), "complete": False, "scopes": [], "findings": []}
    try:
        if args.bom:
            components = [c for c in json.loads(args.bom.read_text())["components"]
                          if c.get("purl", "").startswith("pkg:maven/") and not c.get("group", "").startswith("com.surprising")]
            if not components:
                raise ValueError("SBOM contains no external Maven components")
            report["scopes"].append({"file": str(args.bom), "components": len(components), "excludedGroupPrefix": "com.surprising"})
            for start in range(0, len(components), 250):
                chunk = components[start:start + 250]
                results = post("https://api.osv.dev/v1/querybatch", {"queries": [{"package": {"purl": c["purl"]}} for c in chunk]})["results"]
                if len(results) != len(chunk):
                    raise ValueError("advisory result count does not match queried components")
                for component, result in zip(chunk, results):
                    if result.get("vulns"):
                        report["findings"].append({"component": component["purl"], "ids": sorted(v["id"] for v in result["vulns"])})
        for path in args.npm_lock:
            packages = npm_packages(path)
            findings = post("https://registry.npmjs.org/-/npm/v1/security/advisories/bulk", packages)
            report["scopes"].append({"file": str(path), "registryPackages": len(packages)})
            for name, advisories in findings.items():
                for advisory in advisories:
                    report["findings"].append({"component": name, "checkedVersions": packages[name], "severity": advisory.get("severity"),
                                               "advisory": advisory.get("url"), "vulnerableVersions": advisory.get("vulnerable_versions")})
        report["complete"] = True
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        # No request body, environment, application configuration or credentials enter the report.
        report["error"] = type(error).__name__
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(f"complete={report['complete']} scopes={len(report['scopes'])} findings={len(report['findings'])}")
    return 2 if not report["complete"] else 1 if report["findings"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
