#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <base.jfc> <oldobject-output.jfc>" >&2
  exit 2
fi
BASE_JFC="$1"
OUTPUT_JFC="$2"
if [[ ! -s "${BASE_JFC}" ]] || ! command -v xmllint > /dev/null 2>&1; then
  echo "base JFC and xmllint are required" >&2
  exit 2
fi
if grep -q 'event name="jdk.OldObjectSample"' "${BASE_JFC}"; then
  echo "base JFC must not enable OldObjectSample" >&2
  exit 2
fi
mkdir -p "$(dirname "${OUTPUT_JFC}")"
sed '/<\/configuration>/i\
  <event name="jdk.OldObjectSample"><setting name="enabled">true</setting><setting name="stackTrace">true</setting><setting name="cutoff">infinity</setting></event>' \
  "${BASE_JFC}" > "${OUTPUT_JFC}"
xmllint --noout "${OUTPUT_JFC}"
[[ "$(grep -c 'event name="jdk.OldObjectSample"' "${OUTPUT_JFC}")" -eq 1 ]]
