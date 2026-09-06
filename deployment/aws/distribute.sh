#!/usr/bin/env bash
# SSH config supplies identity, host verification, and optional ProxyJump.
set -euo pipefail
if (( $# < 2 )); then echo 'usage: distribute.sh /absolute/COMMIT.tar.gz user@host [...]' >&2; exit 2; fi
BUNDLE=$1; shift
NAME=$(basename "$BUNDLE" .tar.gz)
[[ "$NAME" =~ ^[0-9a-f]{40}$ ]] || { echo 'Bundle name must be a commit SHA' >&2; exit 2; }
SUM=$(shasum -a 256 "$BUNDLE" | awk '{print $1}')
for HOST in "$@"; do
  [[ "$HOST" =~ ^[a-zA-Z0-9_.@-]+$ ]] || exit 2
  scp -o StrictHostKeyChecking=yes "$BUNDLE" "$HOST:/tmp/$NAME.tar.gz"
  ssh -o StrictHostKeyChecking=yes "$HOST" bash -s -- "$NAME" "$SUM" <<'REMOTE'
set -euo pipefail
NAME=$1; SUM=$2
cd /tmp
printf '%s  %s.tar.gz\n' "$SUM" "$NAME" | sha256sum -c -
# Never overwrite a deployed release, and never delete Archive/snapshots.
sudo mkdir -p /opt/surprising/releases
sudo test ! -e "/opt/surprising/releases/$NAME"
sudo tar -xzf "$NAME.tar.gz" -C /opt/surprising/releases --no-same-owner
cd "/opt/surprising/releases/$NAME"
sha256sum -c SHA256SUMS >/dev/null
printf 'Verified release %s on %s\n' "$NAME" "$(hostname)"
REMOTE
done
