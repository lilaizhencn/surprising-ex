#!/usr/bin/env bash
set -euo pipefail
: "${FORK_SOURCE:?Set FORK_SOURCE to a checkout location}"
ROOT=$(git rev-parse --show-toplevel)
if [[ ! -d "$FORK_SOURCE/.git" ]]; then
  git clone https://github.com/lilaizhencn/exchange-core.git "$FORK_SOURCE"
fi
python3 - "$ROOT" "$FORK_SOURCE" <<'PY'
import pathlib,subprocess,sys,xml.etree.ElementTree as E
p=E.parse(pathlib.Path(sys.argv[1])/'surprising-parent/pom.xml').getroot().find('{*}properties')
f=sys.argv[2]
assert subprocess.check_output(['git','-C',f,'rev-parse','HEAD'],text=True).strip()==p.find('{*}exchange-core.git-sha').text, 'fork HEAD differs from approved pin; do not silently upgrade'
assert not subprocess.check_output(['git','-C',f,'status','--porcelain']), 'fork source must be clean'
PY
cd "$FORK_SOURCE"
java -version
mvn -version
java -XshowSettings:properties -version 2>&1 | python3 -c 'import sys; s=sys.stdin.read(); assert "java.specification.version = 25" in s and "OpenJ9" not in s and ("OpenJDK 64-Bit Server VM" in s or "Java HotSpot(TM)" in s)'
# Compile the pinned dependency. This is not a fork test qualification.
mvn -B -ntp -DskipTests package
python3 - "$ROOT" "$FORK_SOURCE" <<'PY'
import hashlib,pathlib,sys,xml.etree.ElementTree as E
p=E.parse(pathlib.Path(sys.argv[1])/'surprising-parent/pom.xml').getroot().find('{*}properties')
jar=pathlib.Path(sys.argv[2])/'target'/('exchange-core-'+p.find('{*}exchange-core.version').text+'.jar')
assert hashlib.file_digest(jar.open('rb'),'sha256').hexdigest()==p.find('{*}exchange-core.sha256').text, 'JAR differs from approved SHA256; stop without changing the pin'
print('Verified pinned fork artifact:',jar)
PY
