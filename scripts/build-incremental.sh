#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
META_FILE="$(mktemp -t surprising-maven-modules)"
trap 'rm -f "$META_FILE"' EXIT
WITH_TESTS=false
DRY_RUN=false
AUTO_CHANGED=false
BUILD_GOAL="${BUILD_GOAL:-package}"
BUILD_BASE="${BUILD_BASE:-HEAD}"

usage() {
  cat <<'EOF'
Usage:
  scripts/build-incremental.sh [options] <module-or-path> [...]
  scripts/build-incremental.sh --changed [options]

Options:
  --changed       Resolve modules from tracked and untracked workspace changes.
  --with-tests    Run tests while packaging; default is -DskipTests.
  --dry-run       Print the Maven command without executing it.
  --goal GOAL     Maven goal: package, install, or verify (default: package).
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --changed) AUTO_CHANGED=true; shift ;;
    --with-tests) WITH_TESTS=true; shift ;;
    --dry-run) DRY_RUN=true; shift ;;
    --goal) [[ $# -ge 2 ]] || { printf '%s\n' '--goal requires a value' >&2; exit 2; }; BUILD_GOAL="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    --) shift; break ;;
    -*) printf 'unknown option: %s\n' "$1" >&2; usage >&2; exit 2 ;;
    *) break ;;
  esac
done
case "$BUILD_GOAL" in package|install|verify) ;; *) printf 'unsupported Maven goal: %s\n' "$BUILD_GOAL" >&2; exit 2 ;; esac

cd "$ROOT_DIR"
find . -name pom.xml -not -path '*/target/*' -print0 |
  while IFS= read -r -d '' pom; do
    artifact="$(awk 'BEGIN{parent=0; found=0}
      /<parent[ >]/{parent=1}
      /<\/parent>/{parent=0; next}
      !parent && !found && /<artifactId>/{gsub(/.*<artifactId>[[:space:]]*|[[:space:]]*<\/artifactId>.*/, ""); print; found=1}' "$pom")"
    if [[ -n "$artifact" ]]; then
      relative_path="$(printf '%s' "$pom" | sed 's#^\./##')"
      printf '%s\t%s\n' "$artifact" "$relative_path"
    fi
  done | sort -u >"$META_FILE"

module_path() { awk -F '\t' -v id="$1" '$1 == id { print $2; exit }' "$META_FILE"; }
module_id() { awk -F '\t' -v path="$1" '$2 == path { print $1; exit }' "$META_FILE"; }
all_ids() { cut -f1 "$META_FILE"; }
has_id() { printf '%b' "$selected_ids" | awk -v id="$1" '$0 == id { found=1 } END { exit(found ? 0 : 1) }'; }
add_id() { [[ -n "$1" ]] && { has_id "$1" || selected_ids="$selected_ids$1\n"; }; }

dependencies() {
  path="$(module_path "$1")"
  [[ -n "$path" ]] || return 0
  perl -0ne 'while (/<dependency>(.*?)<\/dependency>/sg) {
    $b=$1; next unless $b =~ /<groupId>\s*com\.surprising\s*<\/groupId>/s;
    print "$1\n" if $b =~ /<artifactId>\s*([^<\s]+)\s*<\/artifactId>/s;
  }' "$path" | sort -u
}

children() {
  path="$(module_path "$1")"
  [[ -n "$path" ]] || return 0
  base="$(dirname "$path")"
  sed -n 's:.*<module>[[:space:]]*\([^<]*\)[[:space:]]*</module>.*:\1:p' "$path" |
    while IFS= read -r child; do
      [[ -n "$child" ]] || continue
      child_path="$base/$child/pom.xml"
      child_path="$(printf '%s' "$child_path" | sed 's#//\+#/#g')"
      child_id="$(module_id "$child_path")"
      [[ -n "$child_id" ]] && printf '%s\n' "$child_id"
    done
}

selected_ids=""
if [[ "$AUTO_CHANGED" == true ]]; then
  changed_files="$(git diff --name-only "$BUILD_BASE" --; git ls-files --others --exclude-standard)"
  while IFS= read -r changed; do
    [[ -n "$changed" ]] || continue
    case "$changed" in target/*|*/target/*|reports/*|.local-logs/*|scripts/*|*.md) continue ;; esac
    if [[ "$changed" == "pom.xml" || "$changed" == "surprising-parent/pom.xml" ]]; then
      while IFS= read -r id; do add_id "$id"; done <<EOF
$(all_ids)
EOF
      break
    fi
    best=""
    best_length=0
    while IFS="$(printf '\t')" read -r id path; do
      directory="$(printf '%s' "$path" | sed 's#/pom.xml$##')"
      case "$changed" in
        "$path"|"$directory"/*)
          directory_length="$(expr length "$directory")"
          if [[ "$directory_length" -gt "$best_length" ]]; then best="$id"; best_length="$directory_length"; fi
          ;;
      esac
    done <"$META_FILE"
    add_id "$best"
  done <<EOF
$changed_files
EOF
else
  [[ $# -gt 0 ]] || { printf 'provide a module/path or use --changed\n' >&2; usage >&2; exit 2; }
  while [[ $# -gt 0 ]]; do
    spec="$1"
    case "$spec" in
      :*) id="$(printf '%s' "$spec" | sed 's/^://')" ;;
      */pom.xml) id="$(module_id "$spec")" ;;
      *) [[ -f "$spec/pom.xml" ]] && id="$(module_id "$spec/pom.xml")" || id="$spec" ;;
    esac
    [[ -n "$(module_path "$id")" ]] || { printf 'unknown module/path: %s\n' "$spec" >&2; exit 2; }
    add_id "$id"
    shift
  done
fi

[[ -n "$(printf '%b' "$selected_ids" | sed '/^$/d')" ]] || { printf 'no Maven source or POM changes detected; nothing to build\n'; exit 0; }

expanded=true
while [[ "$expanded" == true ]]; do
  expanded=false
  snapshot="$(printf '%b' "$selected_ids")"
  while IFS= read -r id; do
    [[ -n "$id" ]] || continue
    while IFS= read -r child; do
      [[ -n "$child" ]] || continue
      if ! has_id "$child"; then add_id "$child"; expanded=true; fi
    done <<EOF
$(children "$id")
EOF
  done <<EOF
$snapshot
EOF
done

expanded=true
while [[ "$expanded" == true ]]; do
  expanded=false
  snapshot="$(printf '%b' "$selected_ids")"
  while IFS= read -r candidate; do
    [[ -n "$candidate" ]] || continue
    while IFS= read -r dependency; do
      [[ -n "$dependency" ]] || continue
      if printf '%b' "$snapshot" | awk -v id="$dependency" '$0 == id { found=1 } END { exit(found ? 0 : 1) }'; then
        if ! has_id "$candidate"; then add_id "$candidate"; expanded=true; fi
        break
      fi
    done <<EOF
$(dependencies "$candidate")
EOF
  done <<EOF
$(all_ids)
EOF
done

selector=""
while IFS= read -r id; do
  [[ -n "$id" ]] || continue
  path="$(module_path "$id")"
  component="$(printf '%s' "$path" | sed 's#/pom.xml$##')"
  if [[ -n "$selector" ]]; then selector="$selector,$component"; else selector="$component"; fi
done <<EOF
$(printf '%b' "$selected_ids")
EOF

printf 'incrementalBuild=PLAN goal=%s withTests=%s modules=%s\n' "$BUILD_GOAL" "$WITH_TESTS" "$selector"
if [[ "$DRY_RUN" == true ]]; then
  if [[ "$WITH_TESTS" == true ]]; then
    printf 'mvn -pl %s -am %s\n' "$selector" "$BUILD_GOAL"
  else
    printf 'mvn -pl %s -am -DskipTests %s\n' "$selector" "$BUILD_GOAL"
  fi
  exit 0
fi
if [[ "$WITH_TESTS" == true ]]; then
  mvn -pl "$selector" -am "$BUILD_GOAL"
else
  mvn -pl "$selector" -am -DskipTests "$BUILD_GOAL"
fi
printf 'incrementalBuild=PASS modules=%s\n' "$selector"
