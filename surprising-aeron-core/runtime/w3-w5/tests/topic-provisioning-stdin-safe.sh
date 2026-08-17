#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNTIME_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$RUNTIME_DIR/../../.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/surprising-w3w5-topic-provisioning.XXXXXX")"
trap 'rm -rf "$TEST_ROOT"' EXIT INT TERM

extract_function() {
  local name="$1" script="$2"
  awk -v name="$name" '
    $0 ~ "^" name "\\(\\) \\{" { in_function=1 }
    in_function { print }
    in_function && /^}$/ { exit }
  ' "$script"
}

run_case() {
  local script="$1" case_name provisioned expected actual missing expected_count actual_count
  case_name="$(basename "$script" .sh)"
  provisioned="$TEST_ROOT/$case_name.provisioned"
  : > "$provisioned"

  export REPO_ROOT PROVISIONED_FILE="$provisioned"
  eval "$(extract_function topic_list "$script")"

  compose() {
    local args="$*" topic='' previous=''
    while (($#)); do
      [[ "$previous" == --topic ]] && topic="$1"
      previous="$1"
      shift
    done
    if [[ "$args" == *--create* ]]; then
      printf '%s\n' "$topic" >> "$PROVISIONED_FILE"
      cat >/dev/null
    elif [[ "$args" == *--list* ]]; then
      topic_list | LC_ALL=C sort
    elif [[ "$args" == *--describe* ]]; then
      printf 'Topic: %s PartitionCount: 1 ReplicationFactor: 1\n' "$topic"
    else
      cat >/dev/null
    fi
  }

  mark_ready() { :; }
  eval "$(extract_function create_topics "$script")"
  create_topics

  expected="$(topic_list | LC_ALL=C sort)"
  actual="$(LC_ALL=C sort -u "$PROVISIONED_FILE")"
  expected_count="$(printf '%s\n' "$expected" | awk 'NF { count++ } END { print count + 0 }')"
  actual_count="$(printf '%s\n' "$actual" | awk 'NF { count++ } END { print count + 0 }')"
  if [[ "$actual" != "$expected" ]]; then
    missing="$(comm -23 <(printf '%s\n' "$expected") <(printf '%s\n' "$actual") | paste -sd, -)"
    printf 'FAIL every ProductTopicNames topic must be provisioned even when the invoked create command consumes stdin: script=%s expected=%s actual=%s missing=%s\n' \
      "$case_name" "$expected_count" "$actual_count" "${missing:-none}" >&2
    return 1
  fi
  printf 'PASS every ProductTopicNames topic is provisioned even when the invoked create command consumes stdin: script=%s topics=%s partitions=1\n' \
    "$case_name" "$expected_count"
}

run_case "$RUNTIME_DIR/run.sh"
run_case "$RUNTIME_DIR/scenarios/w5-export-projection.sh"
printf 'TOPIC_PROVISIONING_STDIN_SAFE=PASS scripts=2\n'
