"""本机真实 Core 保证金未知结果验收；沿用 c2c-fused-live.py 的运行目录/PID/数据库归属校验。"""
import json
import os
from pathlib import Path
import runpy
import signal
import subprocess
import sys
import time
import uuid

qa = runpy.run_path(str(Path(__file__).with_name('c2c-fused-live.py')), run_name='c2c_live_library')
root, req, wait = qa['ROOT'], qa['req'], qa['await_value']
actor = 'other'
user = qa['fixture']['users'][actor]['user']['userId']
current = req('GET', '/api/v1/c2c/me', actor)['merchant']
if current is None or current['status'] in ('WITHDRAWN', 'REJECTED'):
    req('POST', '/api/v1/c2c/me/merchant-application', actor, {
        'revision': '0' if current is None else current['revision'],
        'application': 'Local merchant guarantee unknown Core result and original amount refund verification',
    })
state = req('GET', '/api/v1/c2c/me/merchant-bond', actor)
assert state['policy']['configured'] and (state['bond'] is None or state['bond']['status'] in ('RETURNED', 'FAILED')), 'Inspect the existing original bond before running this scenario'
original = {'requestId': str(uuid.uuid4()), 'policyRevision': state['policy']['revision'], 'verification': qa['challenge'](actor)}
processes = json.loads((root / 'processes.json').read_text())
core = processes['core']['pid']
gateway = processes['gateway']['pid']
crash = '--gateway-crash' in sys.argv
command = subprocess.check_output(['ps', '-p', str(core), '-o', 'command='], text=True)
assert str(root) in command and 'surprising-aeron-service' in command, 'Refusing to pause an unowned Core'
before = qa['totals'](qa['balances']())
evidence = {'scope': 'owned local real Core; synthetic identity and verification only', 'userId': str(user), 'before': before, 'passed': False}
try:
    os.kill(core, signal.SIGSTOP)
    print('Owned Core paused; new merchant bond must remain unconfirmed', flush=True)
    accepted = req('POST', '/api/v1/c2c/me/merchant-bond', actor, original)['bond']
    bond = accepted['bondId']
    assert accepted['status'] == 'RESERVING'
    duplicate = req('POST', '/api/v1/c2c/me/merchant-bond', actor, original)['bond']
    assert duplicate['bondId'] == bond and duplicate['status'] == 'RESERVING'
    req('POST', f'/api/v1/c2c/me/merchant-bonds/{bond}/withdraw', actor, expected=(409,))
    pending = req('GET', f'/api/v1/admin/c2c/fund-intents?ownerId={bond}', 'admin')['items']
    assert len(pending) == 1 and pending[0]['purpose'] == 'MERCHANT_BOND'
    evidence['originalIntent'] = pending[0]
    time.sleep(8)
    if crash:
        gateway_command = subprocess.check_output(['ps', '-p', str(gateway), '-o', 'command='], text=True)
        assert str(root) in gateway_command and 'gateway' in gateway_command
        assert (root / 'artifacts/gateway.jar').is_file(), 'Build the immutable verified Gateway first'
        os.kill(gateway, signal.SIGKILL)
        print('Owned Gateway killed after the merchant bond intent was durable', flush=True)
finally:
    os.kill(core, signal.SIGCONT)
    print('Owned Core resumed; continuing the original bond intent', flush=True)
if crash:
    for _ in range(50):
        if subprocess.run(['ps', '-p', str(gateway)], stdout=subprocess.DEVNULL).returncode != 0:
            break
        time.sleep(.1)
    time.sleep(12)  # Preserve the dead driver's heartbeat protection; never delete an active driver.
    subprocess.run([sys.executable, str(root / 'runtime.py'), 'gateway'], check=True)
    def ready():
        try:
            return req('GET', '/api/v1/c2c/status')
        except (OSError, AssertionError):
            return {}
    wait(ready, lambda value: value.get('spotReady') is True, 'Owned Gateway restart', seconds=90)
    evidence['gatewayCrashRecovered'] = True
confirmed = wait(lambda: req('GET', '/api/v1/c2c/me/merchant-bond', actor), lambda row: row['bond']['status'] == 'LOCKED', 'Original merchant reserve confirmation', seconds=60)
assert confirmed['bond']['bondId'] == bond
same = req('GET', f'/api/v1/admin/c2c/fund-intents?ownerId={bond}', 'admin')['items']
assert len(same) == 1 and same[0]['transferId'] == pending[0]['transferId'] and same[0]['status'] == 'APPLIED'
req('POST', f'/api/v1/c2c/me/merchant-bonds/{bond}/withdraw', actor)
returned = wait(lambda: req('GET', '/api/v1/c2c/me/merchant-bond', actor), lambda row: row['bond']['status'] == 'RETURNED', 'Original merchant guarantee refund', seconds=60)
assert returned['bond']['bondId'] == bond and returned['bond']['amountUnits'] == accepted['amountUnits']
intents = req('GET', f'/api/v1/admin/c2c/fund-intents?ownerId={bond}', 'admin')['items']
assert len(intents) == 2 and all(row['status'] == 'APPLIED' for row in intents)
refund = next(row for row in intents if row['purpose'] == 'MERCHANT_BOND_REFUND')
assert refund['targetUserId'] == str(user) and refund['amountUnits'] == accepted['amountUnits']
qa['reconcile']('merchant-bond-unknown-same-transfer-original-refund')
after = qa['totals'](qa['balances']())
assert before == after
evidence.update({'bondId': bond, 'intents': intents, 'after': after, 'passed': True})
(root / ('merchant-bond-gateway-crash-evidence.json' if crash else 'merchant-bond-live-evidence.json')).write_text(json.dumps(evidence, indent=2))
print('PASS: one original reserve, no premature approval/refund, one original-owner refund; total funds unchanged', flush=True)
