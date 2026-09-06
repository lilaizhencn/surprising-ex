#!/usr/bin/env bash
# Run from a clean current master checkout. Release output must be outside the repository.
set -euo pipefail
ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
: "${RELEASE_OUTPUT:?Set RELEASE_OUTPUT to an absolute directory outside this checkout}"
: "${PINNED_FORK_JAR:?Set PINNED_FORK_JAR to the approved exchange-core JAR}"
: "${FORK_SOURCE:?Set FORK_SOURCE to the clean pinned exchange-core checkout}"
test "$(git branch --show-current)" = master
 test -z "$(git status --porcelain --untracked-files=no)"
git fetch origin master
COMMIT=$(git rev-parse HEAD)
test "$COMMIT" = "$(git rev-parse origin/master)"
java -version
mvn -version
java -XshowSettings:properties -version 2>&1 | python3 -c 'import sys; s=sys.stdin.read(); assert "java.specification.version = 25" in s and "OpenJ9" not in s and ("OpenJDK 64-Bit Server VM" in s or "Java HotSpot(TM)" in s), "HotSpot JDK 25 required"'
export ROOT RELEASE_OUTPUT PINNED_FORK_JAR FORK_SOURCE
python3 - <<'PY'
import os,pathlib,hashlib,subprocess,xml.etree.ElementTree as E,zipfile
root=pathlib.Path(os.environ['ROOT']).resolve(); out=pathlib.Path(os.environ['RELEASE_OUTPUT']).resolve()
assert root not in [out,*out.parents], 'release output must be outside source tree'
p=E.parse(root/'surprising-parent/pom.xml').getroot().find('{*}properties')
prop=lambda n:p.find('{*}'+n).text
fork=os.environ['FORK_SOURCE']; jar=pathlib.Path(os.environ['PINNED_FORK_JAR'])
assert subprocess.check_output(['git','-C',fork,'rev-parse','HEAD'],text=True).strip()==prop('exchange-core.git-sha')
assert not subprocess.check_output(['git','-C',fork,'status','--porcelain','--untracked-files=no'])
assert hashlib.file_digest(jar.open('rb'),'sha256').hexdigest()==prop('exchange-core.sha256')
with zipfile.ZipFile(jar) as z:
 s=z.read('META-INF/surprising-exchange-core.properties').decode()
 assert 'fork.git.sha='+prop('exchange-core.git-sha') in s and 'fork.git.dirty=false' in s
PY
mvn -B -ntp org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
  -Dfile="$PINNED_FORK_JAR" -DpomFile="$FORK_SOURCE/pom.xml"
DEST="$RELEASE_OUTPUT/$COMMIT"
test ! -e "$DEST"
mkdir -p "$DEST/artifacts" "$DEST/evidence"
java -version >"$DEST/evidence/java.txt" 2>&1
mvn -version >"$DEST/evidence/maven.txt" 2>&1
uname -a >"$DEST/evidence/os.txt"
# BUILD_TESTS=false is an explicitly labelled packaging check, never a test pass.
TEST_ARGS=()
if [[ ${BUILD_TESTS:-true} == false ]]; then TEST_ARGS=(-DskipTests); fi
printf '%s\n' "tests=${BUILD_TESTS:-true}" >"$DEST/evidence/build-mode.txt"
mvn -B -ntp "${TEST_ARGS[@]}" package 2>&1 | tee "$DEST/evidence/build.log"
test "$COMMIT" = "$(git rev-parse HEAD)"
test -z "$(git status --porcelain --untracked-files=no)"
python3 - "$DEST" <<'PY'
import pathlib,shutil,sys,json,hashlib
root=pathlib.Path.cwd(); dst=pathlib.Path(sys.argv[1]); jars={}
for p in sorted(root.glob('**/target/*.jar')):
 if p.name.endswith('-exec.jar') or p.name in ('surprising-aeron-service.jar','surprising-aeron-tools.jar','product-core-benchmarks.jar'):
  if p.name in jars: raise RuntimeError('duplicate artifact '+p.name)
  shutil.copy2(p,dst/'artifacts'/p.name); jars[p.name]=str(p.relative_to(root))
assert all(name in jars for name in ('surprising-aeron-service.jar','surprising-aeron-tools.jar','product-core-benchmarks.jar')), 'required executable artifact is missing'
shutil.copytree(root/'deployment/aws',dst/'deployment/aws',ignore=shutil.ignore_patterns('__pycache__','*.pyc'))
shutil.copy2(root/'init.sql',dst/'init.sql')
(dst/'manifest.json').write_text(json.dumps({'commit':dst.name,'artifacts':jars},indent=2)+'\n')
with (dst/'SHA256SUMS').open('w') as f:
 for p in sorted(dst.rglob('*')):
  if p.is_file() and p.name!='SHA256SUMS': f.write(hashlib.file_digest(p.open('rb'),'sha256').hexdigest()+'  '+str(p.relative_to(dst))+'\n')
PY
tar -czf "$DEST.tar.gz" -C "$RELEASE_OUTPUT" "$COMMIT"
sha256sum "$DEST.tar.gz" >"$DEST.tar.gz.sha256"
printf 'Release: %s\n' "$DEST.tar.gz"
