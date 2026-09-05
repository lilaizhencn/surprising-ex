#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "Usage: $0 <surefire-report-dir> <exact-class-csv> <invocation-marker> <output-tsv>" >&2
  exit 2
fi

REPORT_DIR="$1"
CLASS_CSV="$2"
INVOCATION_MARKER="$3"
OUTPUT_TSV="$4"

if ! command -v xmllint > /dev/null 2>&1; then
  echo "xmllint is required for exact Surefire report verification" >&2
  exit 2
fi
if [[ ! -d "${REPORT_DIR}" || ! -f "${INVOCATION_MARKER}" ]]; then
  echo "missing Surefire report directory or invocation marker" >&2
  exit 2
fi

mkdir -p "$(dirname "${OUTPUT_TSV}")"
: > "${OUTPUT_TSV}"
printf 'requestedClass\ttests\tfailures\terrors\tskipped\ttestsuite\treport\n' >> "${OUTPUT_TSV}"

xml_attr() {
  local report="$1" attribute="$2"
  xmllint --xpath "string(/testsuite/@${attribute})" "${report}"
}

IFS=',' read -r -a requested_classes <<< "${CLASS_CSV}"
for requested_class in "${requested_classes[@]}"; do
  [[ -n "${requested_class}" ]] || { echo "empty requested test class" >&2; exit 3; }
  matches=()
  while IFS= read -r report; do
    [[ -s "${report}" ]] || continue
    testsuite="$(xml_attr "${report}" name)"
    if [[ "${requested_class}" == *.* ]]; then
      [[ "${testsuite}" == "${requested_class}" ]] && matches+=("${report}")
    else
      [[ "${testsuite##*.}" == "${requested_class}" ]] && matches+=("${report}")
    fi
  done < <(find "${REPORT_DIR}" -maxdepth 1 -type f -name 'TEST-*.xml' -print | sort)

  if [[ "${#matches[@]}" -ne 1 ]]; then
    echo "Surefire exact-class match failed: ${requested_class} matches=${#matches[@]}" >&2
    exit 3
  fi
  report="${matches[0]}"
  if [[ ! "${report}" -nt "${INVOCATION_MARKER}" ]]; then
    echo "stale Surefire report predates current invocation: ${requested_class} report=${report}" >&2
    exit 3
  fi

  testsuite="$(xml_attr "${report}" name)"
  tests="$(xml_attr "${report}" tests)"
  failures="$(xml_attr "${report}" failures)"
  errors="$(xml_attr "${report}" errors)"
  skipped="$(xml_attr "${report}" skipped)"
  if [[ ! "${tests}" =~ ^[0-9]+$ || ! "${failures}" =~ ^[0-9]+$ \
      || ! "${errors}" =~ ^[0-9]+$ || ! "${skipped}" =~ ^[0-9]+$ \
      || "${tests}" -le 0 || "${failures}" -ne 0 || "${errors}" -ne 0 \
      || "${skipped}" -ne 0 ]]; then
    echo "Surefire class contract failed: ${requested_class} tests=${tests:-missing} failures=${failures:-missing} errors=${errors:-missing} skipped=${skipped:-missing}" >&2
    exit 3
  fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "${requested_class}" "${tests}" "${failures}" "${errors}" "${skipped}" \
    "${testsuite}" "${report}" >> "${OUTPUT_TSV}"
done
