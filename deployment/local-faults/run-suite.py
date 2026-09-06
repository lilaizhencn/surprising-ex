#!/usr/bin/env python3
"""Run the complete local functional matrix sequentially and retain failures as evidence."""
import argparse
import json
import os
from pathlib import Path
import traceback
import tarfile
import shutil
from verify import Fixture, availability, snapshot_cut, storage, corrupt_log, smoke, read_only_disk

parser = argparse.ArgumentParser()
parser.add_argument('--root', required=True)
args = parser.parse_args()
assert os.geteuid() == 0
root = Path(args.root)
root.mkdir(parents=True, exist_ok=False)
cases = [('availability', 'SPOT', availability), ('snapshot-cut', 'SPOT', snapshot_cut),
         ('storage', 'SPOT', storage), ('corrupt-log', 'SPOT', corrupt_log), ('read-only', 'SPOT', read_only_disk)]
cases += [('product-' + product.lower(), product, smoke) for product in
          ['SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL', 'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION']]
results = []
for name, product, scenario in cases:
    fixture = Fixture(root / name, product)
    result = dict(scenario=name, product=product, passed=False)
    try:
        scenario(fixture)
        result['passed'] = True
    except Exception as failure:
        result['error'] = repr(failure)
        fixture.event('FAIL', error=repr(failure))
        (fixture.root / 'failure.txt').write_text(traceback.format_exc())
    finally:
        fixture.cleanup()
    # Closed media buffers are sparse but large; retain compressed evidence between cases.
    media = [path for path in fixture.root.glob('media-surprising-*') if path.is_dir()]
    with tarfile.open(fixture.root / 'media-evidence.tgz', 'w:gz') as archive:
        for path in media:
            archive.add(path, arcname=path.name)
    for path in media:
        shutil.rmtree(path)
    results.append(result)
    (root / 'summary.json').write_text(json.dumps(results, indent=2) + '\n')
    print('SCENARIO_RESULT ' + json.dumps(result), flush=True)
raise SystemExit(0 if all(result['passed'] for result in results) else 1)
