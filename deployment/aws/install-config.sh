#!/usr/bin/env bash
# Run on the target, after SCP of its generated coreN/ or app/ directory.
set -euo pipefail
if (( $# != 2 )); then echo 'usage: install-config.sh /path/to/generated/host PRODUCT_LINE' >&2; exit 2; fi
DIR=$1
case "$2" in SPOT|LINEAR_PERPETUAL|INVERSE_PERPETUAL|LINEAR_DELIVERY|INVERSE_DELIVERY|OPTION) ;; *) exit 2;; esac
PRODUCT=$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')
# The runner creates only per-process directories. Data ownership is configured here.
python3 - "$DIR" <<'PY'
import json,pathlib,subprocess,sys
for p in pathlib.Path(sys.argv[1]).glob('*.json'):
 c=json.loads(p.read_text())
 # Resolve user from generated service rather than assuming root.
 units=list(p.parent.glob('*-'+p.stem+'.service')); assert len(units)==1
 user=next(l[5:] for l in units[0].read_text().splitlines() if l.startswith('User='))
 for d in c['directories']:
  subprocess.run(['sudo','install','-d','-m','700','-o',user,'-g',user,d],check=True)
 # Core also writes a persistent archive tree, separate from its working directory.
 for arg in c['arguments']:
  if arg.startswith('-Dsurprising.aeron.data-dir='):
   subprocess.run(['sudo','install','-d','-m','700','-o',user,'-g',user,arg.split('=',1)[1]],check=True)
PY
sudo install -d -m 755 "/etc/surprising/$PRODUCT"
for UNIT in "$DIR"/*.service; do
  NAME=$(basename "$UNIT")
  USER_NAME=$(sed -n 's/^User=//p' "$UNIT")
  SPEC=${NAME#surprising-$PRODUCT-}; SPEC=${SPEC%.service}
  sudo install -m 600 -o "$USER_NAME" "$DIR/$SPEC.json" "/etc/surprising/$PRODUCT/$SPEC.json"
  sudo install -m 644 "$UNIT" "/etc/systemd/system/$NAME"
done
sudo systemctl daemon-reload
printf 'Installed units; start explicitly with systemctl. No services enabled or started.\n'
