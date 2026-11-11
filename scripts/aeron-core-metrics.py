#!/usr/bin/env python3
"""Report maintainability baselines for surprising-aeron-core without external dependencies.

The output is intentionally stable so it can be committed to a baseline document and
compared in CI ("only decrease" gates). It measures source shape, not runtime behavior.
"""
import argparse
import json
import re
from collections import Counter
from pathlib import Path

MODULES = (
    "surprising-aeron-service",
    "surprising-aeron-protocol",
    "surprising-aeron-client",
    "surprising-aeron-tools",
    "surprising-aeron-benchmarks",
)
TYPE_DECL = re.compile(r"\b(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)")
PUBLIC_METHOD = re.compile(r"^\s{1,8}public\s+(?:<[^>]+>\s+)?[\w$.<>\[\], ?]+\s+(\w+)\s*\(")
IMPORT = re.compile(r"^import\s+(com\.surprising\.aeron[\w.]*)\.(\w+);", re.M)


def strip_comments(source):
    """Return source with comments removed, preserving newlines and string literals."""
    out = []
    i, n, state = 0, len(source), "code"
    while i < n:
        ch = source[i]
        nxt = source[i + 1] if i + 1 < n else ""
        if state == "code":
            if ch == "/" and nxt == "/":
                state = "line"
                i += 2
                continue
            if ch == "/" and nxt == "*":
                state = "block"
                i += 2
                continue
            if ch == '"':
                state = "string"
                out.append(ch)
                i += 1
                continue
            if ch == "'":
                state = "char"
                out.append(ch)
                i += 1
                continue
            out.append(ch)
            i += 1
        elif state == "line":
            if ch == "\n":
                state = "code"
                out.append("\n")
            i += 1
        elif state == "block":
            if ch == "*" and nxt == "/":
                state = "code"
                i += 2
                continue
            if ch == "\n":
                out.append("\n")
            i += 1
        elif state in ("string", "char"):
            quote = '"' if state == "string" else "'"
            if ch == "\\":
                i += 2
                continue
            if ch == quote:
                state = "code"
            i += 1
    return "".join(out)


def comment_lines(lines):
    count = 0
    for line in lines:
        text = line.strip()
        if text.startswith("//") or text.startswith("*") or text.startswith("/*"):
            count += 1
    return count


def file_metrics(path):
    text = path.read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()
    stripped = strip_comments(text).splitlines()
    code = sum(1 for line in stripped if line.strip())
    return {
        "path": str(path),
        "total": len(lines),
        "code": code,
        "comments": comment_lines(lines),
        "types": len(TYPE_DECL.findall(strip_comments(text))),
        "public_methods": sum(1 for line in lines if PUBLIC_METHOD.match(line)),
    }


def package_edges(module_root):
    packages = {}
    for path in module_root.glob("src/main/java/**/*.java"):
        package = path.parent.relative_to(module_root / "src/main/java").as_posix().replace("/", ".")
        packages.setdefault(package, []).append(path)
    edges = {}
    for package, files in packages.items():
        targets = set()
        for path in files:
            source = path.read_text(encoding="utf-8", errors="replace")
            for match in IMPORT.finditer(source):
                target = match.group(1)
                if target != package and target in packages:
                    targets.add(target)
        edges[package] = targets
    mutual = sorted(
        (a, b) for a in edges for b in edges[a] if a < b and b in edges and a in edges[b]
    )
    return packages, edges, mutual


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1] / "surprising-aeron-core")
    parser.add_argument("--json", action="store_true", help="emit JSON instead of Markdown")
    args = parser.parse_args()
    root = args.root.resolve()

    main_files, test_files = [], []
    for module in MODULES:
        module_root = root / module
        main_files += [file_metrics(p) for p in module_root.glob("src/main/java/**/*.java")]
        test_files += [file_metrics(p) for p in module_root.glob("src/test/java/**/*.java")]
    main_files.sort(key=lambda item: -item["total"])

    service_main = [item for item in main_files if "/surprising-aeron-service/" in item["path"]]
    service_packages, service_edges, mutual = package_edges(root / "surprising-aeron-service")
    test_sources = list((root / "surprising-aeron-service").glob("src/test/java/**/*.java"))
    tests = sum(
        1
        for path in test_sources
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines()
        if re.match(r"\s*@Test\b", line)
    )

    result = {
        "main_files": len(main_files),
        "test_files": len(test_files),
        "main_lines": sum(item["total"] for item in main_files),
        "main_code_lines": sum(item["code"] for item in main_files),
        "main_comment_lines": sum(item["comments"] for item in main_files),
        "service_main_files": len(service_main),
        "service_main_lines": sum(item["total"] for item in service_main),
        "service_main_comments": sum(item["comments"] for item in service_main),
        "types": sum(item["types"] for item in main_files),
        "files_ge_1000": sum(1 for item in main_files if item["total"] >= 1000),
        "files_ge_500": sum(1 for item in main_files if item["total"] >= 500),
        "service_files_ge_1000": sum(1 for item in service_main if item["total"] >= 1000),
        "service_tests": tests,
        "service_test_files": len(test_sources),
        "service_test_classes": sum(
            1 for path in test_sources
            if re.search(r"\s*@Test\b", path.read_text(encoding="utf-8", errors="replace"))
        ),
        "service_packages": len(service_packages),
        "service_package_edges": sum(len(v) for v in service_edges.values()),
        "service_mutual_packages": len(mutual),
        "largest": main_files[:10],
    }

    if args.json:
        print(json.dumps(result, indent=2))
        return

    def pct(part, whole):
        return f"{part / whole * 100:.1f}%" if whole else "0.0%"

    print("# Aeron core metrics\n")
    print(f"- main files: {result['main_files']} (service {result['service_main_files']})")
    print(f"- test files: {result['test_files']}")
    print(f"- main lines: {result['main_lines']} (code {result['main_code_lines']}, comments {result['main_comment_lines']})")
    print(f"- service main: {result['service_main_lines']} lines, comments {pct(result['service_main_comments'], result['service_main_lines'])}")
    print(f"- type declarations: {result['types']}")
    print(f"- main files >=1000 lines: {result['files_ge_1000']} (service {result['service_files_ge_1000']})")
    print(f"- main files >=500 lines: {result['files_ge_500']}")
    print(f"- service tests: {result['service_tests']} @Test in {result['service_test_classes']} classes / {result['service_test_files']} files")
    print(f"- service packages: {result['service_packages']}, edges: {result['service_package_edges']}, mutual pairs: {result['service_mutual_packages']}")
    print("\n## Largest main files\n")
    print("| lines | code | comments | public decls | file |")
    print("| ---: | ---: | ---: | ---: | --- |")
    for item in result["largest"]:
        print(f"| {item['total']} | {item['code']} | {item['comments']} | {item['public_methods']} | {item['path']} |")


if __name__ == "__main__":
    main()
