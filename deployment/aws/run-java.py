#!/usr/bin/env python3
"""Run a generated process specification without shell expansion."""
import json, os, pathlib, subprocess, sys

def main():
    cfg = json.loads(pathlib.Path(sys.argv[1]).read_text())
    java = str(pathlib.Path(cfg['javaHome']) / 'bin/java')
    info = subprocess.run([java, '-XshowSettings:properties', '-version'], capture_output=True, text=True, check=True)
    text = info.stdout + info.stderr
    if 'java.specification.version = 25' not in text or 'OpenJ9' in text or not (
            'OpenJDK 64-Bit Server VM' in text or 'Java HotSpot(TM)' in text):
        raise SystemExit('HotSpot JDK 25 required')
    for directory in cfg['directories']:
        pathlib.Path(directory).mkdir(parents=True, exist_ok=True)
    os.chdir(cfg['workDirectory'])
    env = os.environ.copy()
    env.update(cfg.get('environment', {}))
    # Specification contains credentials: never print it or expanded JVM arguments.
    os.execve(java, [java, *cfg['arguments']], env)

if __name__ == '__main__':
    main()
