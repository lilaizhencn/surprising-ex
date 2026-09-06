#!/usr/bin/env python3
"""Run inside the dedicated Linux fault VM as root. Sequential functional samples only."""
import argparse
import json
import os
from pathlib import Path
import queue
import re
import signal
import shutil
import struct
import subprocess
import threading
import time
import zipfile

REPO = Path(__file__).resolve().parents[2]
JAVA = '/opt/fault-jdk25/bin/java'
TOOLS = REPO / 'surprising-aeron-core/surprising-aeron-tools/target'
CP = ':'.join(str(TOOLS / part) for part in ('test-classes', 'classes', 'surprising-aeron-tools.jar'))
FLAGS = ['-Xms128m', '-Xmx512m', '--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED',
         '--add-opens=java.base/java.util.zip=ALL-UNNAMED',
         '--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED']
HOSTS = ['10.231.77.' + str(i + 11) for i in range(4)]


def run(args, **kw):
    return subprocess.run([str(x) for x in args], check=True, text=True, capture_output=True, timeout=30, **kw).stdout


class Fixture:
    def __init__(self, root, product='SPOT'):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=False)
        self.product = product
        self.nodes = {}
        self.stopped_at = {}
        self.client = None
        self.mounts = []
        self.agent = None
        self.owns_network = False
        self.events = open(self.root / 'events.jsonl', 'a', buffering=1)

    def event(self, name, **details):
        item = dict(time=time.time(), event=name, **details)
        self.events.write(json.dumps(item) + '\n')
        print(json.dumps(item), flush=True)

    def ns(self, i, args):
        return ['ip', 'netns', 'exec', 'afqa' + str(i)] + [str(x) for x in args]

    def network(self):
        # Refuse to reuse namespaces: they may belong to an unfinished diagnostic run.
        existing = {line.split()[0] for line in run(['ip', 'netns', 'list']).splitlines() if line.strip()}
        assert not existing.intersection({'afqa' + str(i) for i in range(4)}), 'fault namespaces already exist'
        run(['ip', 'link', 'add', 'afqabr', 'type', 'bridge'])
        self.owns_network = True
        run(['ip', 'link', 'set', 'afqabr', 'up'])
        for i in range(4):
            name = 'afqa' + str(i)
            run(['ip', 'netns', 'add', name])
            run(['ip', 'link', 'add', 'afqav' + str(i), 'type', 'veth', 'peer', 'name', 'afqan' + str(i)])
            run(['ip', 'link', 'set', 'afqan' + str(i), 'netns', name])
            run(['ip', 'link', 'set', 'afqav' + str(i), 'master', 'afqabr'])
            run(['ip', 'link', 'set', 'afqav' + str(i), 'up'])
            run(self.ns(i, ['ip', 'link', 'set', 'lo', 'up']))
            run(self.ns(i, ['ip', 'addr', 'add', HOSTS[i] + '/24', 'dev', 'afqan' + str(i)]))
            run(self.ns(i, ['ip', 'link', 'set', 'afqan' + str(i), 'up']))

    def props(self):
        return ['-Dsurprising.aeron.product-line=' + self.product,
                '-Dsurprising.aeron.hostnames=' + ','.join(HOSTS[:3]),
                '-Dsurprising.aeron.egress-hostname=' + HOSTS[3]]

    def directory(self, i):
        return self.root / 'data' / self.product.lower() / ('node' + str(i)) / 'cluster'

    def start(self, i):
        assert i not in self.nodes or self.nodes[i].poll() is not None
        if i in self.nodes:
            # The previous process is confirmed dead. Allow Aeron's 10s driver heartbeat lease to expire.
            time.sleep(max(0, 11 - (time.monotonic() - self.stopped_at.get(i, time.monotonic()))))
        output = open(self.root / ('node' + str(i) + '.log'), 'a')
        cmd = [JAVA] + FLAGS + self.props() + [
            '-Daeron.dir=' + str(self.root / 'media'),
            '-Dsurprising.aeron.data-dir=' + str(self.root / 'data'),
            '-Dsurprising.aeron.node-id=' + str(i), '-Dsurprising.aeron.matching-engines=1',
            '-Dsurprising.aeron.account-lanes=4', '-Dsurprising.aeron.risk-engines=0',
            '-cp', CP, 'com.surprising.aeron.service.SurprisingClusterNode']
        if self.agent:
            cmd.insert(1, '-javaagent:' + str(self.agent) + '=' + str(self.root / ('snapshot-' + str(i))))
        self.nodes[i] = subprocess.Popen(self.ns(i, cmd), stdout=output, stderr=subprocess.STDOUT)
        self.stopped_at.pop(i, None)
        output.close()
        self.event('start', node=i, pid=self.nodes[i].pid)

    def stop(self, i, sig=signal.SIGKILL):
        p = self.nodes[i]
        if p.poll() is None:
            p.send_signal(sig)
            if sig in (signal.SIGKILL, signal.SIGTERM):
                p.wait(timeout=40)
                self.stopped_at[i] = time.monotonic()
        self.event('signal', node=i, signal=sig.name, returnCode=p.poll())

    def partition(self, i, enabled):
        # Only block member-to-member traffic; leave the client able to test the minority.
        for peer in range(3):
            if peer == i:
                continue
            for chain, address in [('INPUT', '-s'), ('OUTPUT', '-d')]:
                run(self.ns(i, ['iptables', '-A' if enabled else '-D', chain,
                                address, HOSTS[peer], '-j', 'DROP']))
        self.event('partition', node=i, enabled=enabled)

    def admin(self, i, command='members'):
        return run(self.ns(i, [JAVA] + FLAGS + self.props() + ['-cp', CP,
                'com.surprising.aeron.tools.FaultClientMain', command, self.directory(i)]))

    def leader(self, exclude=()):
        deadline = time.monotonic() + 50
        while time.monotonic() < deadline:
            for i, p in self.nodes.items():
                if i in exclude or p.poll() is not None:
                    continue
                try:
                    output = self.admin(i)
                    match = re.search(r'leaderMemberId=(\d+)', output)
                    if match and int(match[1]) not in exclude:
                        return int(match[1])
                except (subprocess.SubprocessError, OSError):
                    pass
            time.sleep(1)
        raise AssertionError('no leader')

    def connect(self):
        if self.client is not None:
            return
        self.responses = queue.Queue()
        self.client = subprocess.Popen(self.ns(3, [JAVA] + FLAGS + self.props() + ['-cp', CP,
                'com.surprising.aeron.tools.FaultClientMain']), stdin=subprocess.PIPE,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        def consume(process, responses):
            with open(self.root / 'client.log', 'a', buffering=1) as output:
                for line in process.stdout:
                    output.write(line)
                    if line.startswith('QA '):
                        responses.put(json.loads(line[3:]))
                responses.put({'ok': False, 'error': 'client exited'})
        threading.Thread(target=consume, args=(self.client, self.responses), daemon=True).start()

    def command(self, op='query', require=True, **args):
        self.connect()
        request = dict(op=op, **args)
        self.client.stdin.write(json.dumps(request) + '\n')
        self.client.stdin.flush()
        result = self.responses.get(timeout=40)
        self.event('command', request=request, result=result)
        if require:
            assert result.get('ok') or result.get('offered', 0) > 0, result
        return result

    def ready(self):
        # Aeron 1.53 defaults to a 60s startup canvass when a cold-start member is missing.
        # Include that lease plus connect/replay time; do not change consensus timing for QA.
        for attempt in range(15):
            result = self.command(require=False, type='BUSINESS_STATE_HASH_QUERY')
            if result.get('ok'):
                return result
            time.sleep(1)
        raise AssertionError('cluster unavailable')

    def balance(self, user, asset, available, locked=0):
        value = self.command(user=user)['user']
        row = next((v for v in value['balances'] if v['asset'] == asset),
                   {'availableUnits': 0, 'lockedUnits': 0})
        assert (row['availableUnits'], row['lockedUnits']) == (available, locked), (user, asset, row, available, locked)

    def gate(self, mode='execute', seed=7001):
        try:
            output = run(self.ns(3, [JAVA] + FLAGS + self.props() + [
                '-Dsurprising.aeron.smoke-mode=' + mode, '-Dsurprising.aeron.smoke-seed=' + str(seed),
                '-cp', CP, 'com.surprising.aeron.tools.ClusterProductLineGateMain']))
        except subprocess.CalledProcessError as failure:
            self.event('product-gate-failed', output=failure.stdout, error=failure.stderr)
            raise
        self.event('product-gate', mode=mode, output=output)
        assert 'productLineGate=PASS' in output

    def snapshot_all(self):
        # A catching-up follower can replay (and skip) a snapshot action. Wait for
        # real same-position snapshots, requesting another cut after it joins.
        deadline = time.monotonic() + 90
        last = None
        while time.monotonic() < deadline:
            try:
                self.admin(self.leader(), 'snapshot')
                time.sleep(2)
                views = [json.loads(self.admin(i, 'snapshot-state').split('QA ')[1]) for i in range(3)]
                if views[0] == views[1] == views[2]:
                    return views
                last = views
            except subprocess.SubprocessError as error:
                last = str(error)
            self.event('waiting-for-replica-snapshots', detail=last)
            time.sleep(2)
        raise AssertionError(('replica snapshots did not converge', last))

    def cleanup(self):
        if self.client:
            self.client.terminate()
            self.client.wait(timeout=15)
        for i, p in self.nodes.items():
            if p.poll() is None:
                self.stop(i)
        for mount in reversed(self.mounts):
            run(['cp', '-a', '--sparse=always', mount, str(mount) + '-evidence'])
            run(['umount', mount])
        if self.owns_network:
            for i in range(4):
                subprocess.run(['ip', 'netns', 'del', 'afqa' + str(i)], capture_output=True)
            subprocess.run(['ip', 'link', 'del', 'afqabr'], capture_output=True)
        self.events.close()


def smoke(f):
    f.network()
    for i in range(3):
        f.start(i)
    f.ready()
    f.event('leader', node=f.leader())
    f.gate()
    f.admin(f.leader(), 'snapshot')
    time.sleep(4)
    views = [json.loads(f.admin(i, 'snapshot-state').split('QA ')[1]) for i in range(3)]
    assert views[0] == views[1] == views[2], views
    f.event('PASS', scenario='all-replicas-financial-snapshot-equal', product=f.product, views=views)
    for i in range(3):
        f.stop(i)
    f.command('close')
    for i in range(3):
        f.start(i)
    f.ready()
    f.gate('verify')
    asset = 'BTC' if f.product.startswith('INVERSE') else 'USDT'
    f.command('adjust', key='post-recovery-funds', user=1005, asset=asset, units=1)
    f.balance(1005, asset, 1)
    f.event('PASS', scenario='product-gate-abrupt-restart', product=f.product)


def availability(f):
    f.network()
    for i in range(3):
        f.start(i)
    f.ready()
    f.command('init', key='instrument')
    f.command('adjust', key='seller-funds', user=1001, asset='BTC', units=20)
    f.command('adjust', key='buyer-funds', user=1002, units=2000)
    f.command('place', key='maker', user=1001, order=101, side='SELL', qty=10)
    f.command('place', key='partial', user=1002, order=102, side='BUY', qty=4)
    def check():
        f.balance(1001, 'BTC', 10, 6)
        f.balance(1001, 'USDT', 400)
        f.balance(1002, 'BTC', 4)
        f.balance(1002, 'USDT', 1600)
    check()
    original = f.command(type='BUSINESS_STATE_HASH_QUERY')['hash']
    # Retry after failover with the same complete request, then verify the actual balances.
    def recovered(name):
        f.ready()
        f.command('place', key='partial', user=1002, order=102, side='BUY', qty=4)
        check()
        assert f.command(type='BUSINESS_STATE_HASH_QUERY')['hash'] == original
        f.event('PASS', scenario=name)
    leader = f.leader()
    follower = (leader + 1) % 3
    f.stop(follower)
    recovered('follower-kill-majority-serving')
    # New committed changes while the follower is offline; cancel restores business balances.
    for number in range(3):
        f.command('place', key='offline-place-' + str(number), user=1002,
                  order=200 + number, side='BUY', price=90, qty=1)
        f.command('cancel', key='offline-cancel-' + str(number), user=1002, order=200 + number)
    original = f.command(type='BUSINESS_STATE_HASH_QUERY')['hash']
    f.start(follower)
    time.sleep(4)
    f.stop(leader)
    f.leader(exclude=[leader])
    recovered('leader-kill-retry')
    f.start(leader)
    time.sleep(4)
    for role in ('follower', 'leader'):
        current = f.leader()
        target = current if role == 'leader' else (current + 1) % 3
        f.stop(target, signal.SIGSTOP)
        time.sleep(12)
        f.command('close')
        f.leader(exclude=[target])
        recovered(role + '-pause-majority-serving')
        f.stop(target, signal.SIGCONT)
        time.sleep(4)
        if f.nodes[target].poll() is not None:
            assert f.nodes[target].returncode == 1, ('unexpected node exit', target, f.nodes[target].returncode)
            f.event('expected-fail-stop', node=target, reason='long pause expired driver lease')
            f.start(target)
            time.sleep(4)
        assert f.nodes[target].poll() is None
        recovered(role + '-resume')
    for role in ('follower', 'leader'):
        current = f.leader()
        target = current if role == 'leader' else (current + 1) % 3
        f.partition(target, True)
        time.sleep(12)
        f.command('close')
        # The same client can now reach only the isolated member, so it cannot silently reroute.
        for peer in range(3):
            if peer != target:
                run(f.ns(3, ['iptables', '-A', 'INPUT', '-s', HOSTS[peer], '-j', 'DROP']))
                run(f.ns(3, ['iptables', '-A', 'OUTPUT', '-d', HOSTS[peer], '-j', 'DROP']))
        result = f.command('adjust', require=False, key=role + '-minority-command', user=1006, units=1)
        assert not result.get('ok'), result
        for peer in range(3):
            if peer != target:
                run(f.ns(3, ['iptables', '-D', 'INPUT', '-s', HOSTS[peer], '-j', 'DROP']))
                run(f.ns(3, ['iptables', '-D', 'OUTPUT', '-d', HOSTS[peer], '-j', 'DROP']))
        f.event('PASS', scenario=role + '-isolated-member-cannot-confirm-command')
        f.leader(exclude=[target])
        recovered(role + '-network-majority-serving')
        f.partition(target, False)
        time.sleep(4)
        recovered(role + '-network-healed')
    current = f.leader()
    others = [i for i in range(3) if i != current]
    for i in others:
        f.stop(i)
    time.sleep(12)
    result = f.command('adjust', require=False, key='ambiguous', user=1002, units=7)
    assert not result.get('ok'), result
    f.event('PASS', scenario='no-majority-no-success')
    for i in others:
        f.start(i)
    f.ready()
    f.command('adjust', key='ambiguous', user=1002, units=7)
    f.command('adjust', key='ambiguous', user=1002, units=7)
    f.balance(1002, 'USDT', 1607)
    f.command('place', key='finish', user=1002, order=103, side='BUY', qty=6)
    f.balance(1001, 'BTC', 10)
    f.balance(1001, 'USDT', 1000)
    f.balance(1002, 'BTC', 10)
    f.balance(1002, 'USDT', 1007)
    f.event('PASS', scenario='majority-restored-ambiguous-dedup-and-new-trade')
    f.event('PASS', scenario='all-replicas-before-full-restart', views=f.snapshot_all())
    for sig in (signal.SIGTERM, signal.SIGKILL):
        before = f.command(type='BUSINESS_STATE_HASH_QUERY')['hash']
        for i in range(3):
            f.stop(i, sig)
        f.command('close')
        for i in range(3):
            f.start(i)
        f.ready()
        assert f.command(type='BUSINESS_STATE_HASH_QUERY')['hash'] == before
        f.balance(1001, 'BTC', 10)
        f.balance(1002, 'USDT', 1007)
        f.event('PASS', scenario='full-restart-' + sig.name)


def storage(f):
    f.network()
    # The only disk filled by this test is this isolated tmpfs, never the VM/host root disk.
    archive = f.directory(2).parent / 'archive'
    archive.mkdir(parents=True)
    run(['mount', '-t', 'tmpfs', '-o', 'size=256m', 'afqa-storage', archive])
    f.mounts.append(archive)
    for i in range(3):
        f.start(i)
    f.ready()
    f.gate()
    f.admin(f.leader(), 'snapshot')
    time.sleep(4)
    baseline = {}
    for i in range(3):
        baseline[i] = json.loads(f.admin(i, 'recordings').split('QA ')[1])
        assert any(e['serviceId'] == 0 and e['isValid'] for e in baseline[i])
    filler = archive / 'fault-only-fill'
    try:
        with open(filler, 'wb', buffering=0) as output:
            block = bytes(1024 * 1024)
            while True:
                output.write(block)
    except OSError as error:
        assert error.errno == 28, error
        f.event('disk-full-injected', mount=str(archive))
    f.admin(f.leader(), 'snapshot')
    time.sleep(6)
    log = (f.root / 'node2.log').read_text()
    assert any(s in log.lower() for s in ('space', 'enospc', 'storage')), log[-2000:]
    f.event('PASS', scenario='disk-full-reported', log=log[-2500:])
    f.stop(2)
    filler.unlink()
    f.start(2)
    f.ready()
    f.gate('verify')
    time.sleep(5)
    f.admin(f.leader(), 'snapshot')
    time.sleep(5)
    views = [json.loads(f.admin(i, 'snapshot-state').split('QA ')[1]) for i in range(3)]
    assert views[0] == views[1] == views[2], views
    f.event('PASS', scenario='disk-space-restored-business-intact')
    # Corrupt one service snapshot payload while all processes are stopped.
    for i in range(3):
        f.stop(i)
    f.command('close')
    entries = json.loads(f.admin(0, 'recordings').split('QA ')[1])
    snapshot = max((e for e in entries if e['serviceId'] == 0 and e['isValid']), key=lambda e: (e['logPosition'], e['entryIndex']))
    segment = next((f.directory(0).parent / 'archive').glob(str(snapshot['recordingId']) + '-*.rec'))
    backup = f.root / 'uncorrupted-snapshot.rec'
    shutil.copy2(segment, backup)
    with open(segment, 'r+b') as stream:
        # Skip Aeron service-container SBE metadata and damage the actual Core snapshot envelope.
        prefix = stream.read(65536)
        offset = prefix.index(b'NSXS') + 4
        stream.seek(offset)
        value = stream.read(1)
        stream.seek(offset)
        stream.write(bytes([value[0] ^ 1]))
    f.event('snapshot-corrupted', segment=str(segment), offset=offset)
    for i in range(3):
        f.start(i)
    f.ready()
    f.gate('verify')
    time.sleep(4)
    log = (f.root / 'node0.log').read_text()
    assert f.nodes[0].poll() is not None, 'corrupt snapshot node stayed alive'
    assert any(s in log.lower() for s in ('checksum', 'snapshot', 'manifest')), log[-2000:]
    f.event('PASS', scenario='corrupt-snapshot-refused-majority-business-intact', log=log[-2500:])
    shutil.copy2(backup, segment)
    f.start(0)
    f.ready()
    f.gate('verify')
    repaired_views = f.snapshot_all()
    assert f.nodes[0].poll() is None, 'repaired member did not remain running'
    f.event('PASS', scenario='repaired-snapshot-member-rejoined', views=repaired_views)


def snapshot_cut(f):
    f.network()
    f.agent = f.root / 'snapshot-agent.jar'
    with zipfile.ZipFile(f.agent, 'w') as jar:
        jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nPremain-Class: com.surprising.aeron.tools.FaultSnapshotAgent\n\n')
        for cls in (TOOLS / 'test-classes/com/surprising/aeron/tools').glob('FaultSnapshotAgent*.class'):
            jar.write(cls, str(cls.relative_to(TOOLS / 'test-classes')))
    for i in range(3):
        f.start(i)
    f.ready()
    f.gate()
    f.admin(f.leader(), 'snapshot')
    time.sleep(4)
    views = [json.loads(f.admin(i, 'snapshot-state').split('QA ')[1]) for i in range(3)]
    assert views[0] == views[1] == views[2], views
    f.event('PASS', scenario='all-replica-snapshot-same-position-and-business-state', views=views)
    current = f.leader()
    arm = f.root / ('snapshot-' + str(current) + '.arm')
    marker = f.root / ('snapshot-' + str(current) + '.paused')
    arm.touch()
    control_result = queue.Queue()
    def request_snapshot():
        try:
            control_result.put(f.admin(current, 'snapshot'))
        except subprocess.SubprocessError as error:
            control_result.put(str(error))
    control_thread = threading.Thread(target=request_snapshot)
    control_thread.start()
    deadline = time.monotonic() + 10
    while not marker.exists() and time.monotonic() < deadline:
        time.sleep(.05)
    assert marker.exists(), 'snapshot injection was not reached'
    f.event('snapshot-first-fragment-accepted', node=current, marker=marker.read_text())
    f.stop(current)
    arm.unlink()
    control_thread.join(timeout=35)
    assert not control_thread.is_alive()
    f.event('interrupted-snapshot-control-result', result=control_result.get_nowait())
    entries = json.loads(f.admin(current, 'recordings').split('QA ')[1])
    latest = max(e['logPosition'] for e in entries if e['serviceId'] == 0 and e['isValid'])
    assert latest == views[current]['logPosition'], (latest, views)
    f.ready()
    f.start(current)
    f.ready()
    f.gate('verify')
    f.event('PASS', scenario='kill-during-snapshot-incomplete-not-adopted-business-recovered')
    # Block replies, successfully offer once, then reconnect and prove it committed before any retry.
    for host in HOSTS[:3]:
        run(f.ns(3, ['iptables', '-A', 'INPUT', '-s', host, '-j', 'DROP']))
    f.command('adjust', key='lost-response', user=1005, units=500, offerOnly=True)
    time.sleep(2)
    f.command('close')
    for host in HOSTS[:3]:
        run(f.ns(3, ['iptables', '-D', 'INPUT', '-s', host, '-j', 'DROP']))
    f.ready()
    f.balance(1005, 'USDT', 500)
    f.command('adjust', key='lost-response', user=1005, units=500)
    f.balance(1005, 'USDT', 500)
    f.event('PASS', scenario='committed-response-dropped-and-retry-deduplicated')
    f.command('init', key='new-instrument')
    f.command('adjust', key='new-seller-funds', user=1001, asset='BTC', units=5)
    f.command('adjust', key='new-buyer-funds', user=1002, units=1000)
    f.command('place', key='new-maker', user=1001, order=900, side='SELL', qty=5)
    f.command('place', key='new-taker', user=1002, order=901, side='BUY', qty=5)
    f.balance(1001, 'USDT', 500)
    f.balance(1002, 'USDT', 500)
    f.balance(1002, 'BTC', 5)
    f.event('PASS', scenario='new-trade-after-interrupted-snapshot')


def corrupt_log(f):
    f.network()
    for i in range(3):
        f.start(i)
    f.ready()
    f.command('adjust', key='log-funds', user=1005, units=1234567)
    f.balance(1005, 'USDT', 1234567)
    for i in range(3):
        f.stop(i)
    f.command('close')
    damaged = []
    for i in range(3):
        segment = f.directory(i).parent / 'archive/0-0.rec'
        backup = f.root / ('node' + str(i) + '-log-before-corruption.rec')
        run(['cp', '--sparse=always', segment, backup])
        with open(segment, 'r+b') as stream:
            prefix = stream.read(65536)
            needle = struct.pack('<q', 1234567)
            assert prefix.count(needle) == 1, 'ambiguous log payload mutation'
            offset = prefix.index(needle)
            stream.seek(offset)
            stream.write(struct.pack('<q', 1234566))
        damaged.append((segment, backup))
        f.event('log-payload-corrupted', node=i, offset=offset, originalUnits=1234567, damagedUnits=1234566)
    for i in range(3):
        f.start(i)
    for attempt in range(3):
        response = f.command(require=False, user=1005)
        assert not response.get('ok'), ('corrupt committed log was accepted', response)
    for i in range(3):
        log = (f.root / ('node' + str(i) + '.log')).read_text()
        assert 'checksum' in log.lower(), log[-2000:]
        f.stop(i)
    f.event('PASS', scenario='corrupt-log-replay-refused')
    for segment, backup in damaged:
        run(['cp', '--sparse=always', backup, segment])
    for i in range(3):
        f.start(i)
    f.ready()
    f.balance(1005, 'USDT', 1234567)
    f.command('adjust', key='after-log-repair', user=1005, units=1)
    f.balance(1005, 'USDT', 1234568)
    f.event('PASS', scenario='repaired-log-restores-confirmed-funds-and-new-commands')


def read_only_disk(f):
    f.network()
    for i in range(3):
        f.start(i)
    f.ready()
    f.gate()
    leader = f.leader()
    target = (leader + 1) % 3
    archive = f.directory(target).parent / 'archive'
    # ip netns exec gives the running JVM a private mount namespace. Inject there.
    mount_ns = ['nsenter', '-t', str(f.nodes[target].pid), '-m', '--']
    run(mount_ns + ['mount', '--bind', archive, archive])
    run(mount_ns + ['mount', '-o', 'remount,bind,ro', archive])
    probe = subprocess.run(mount_ns + ['touch', str(archive / 'write-probe')],
                           capture_output=True, text=True, timeout=10)
    assert probe.returncode != 0 and 'read-only' in probe.stderr.lower(), probe
    f.event('read-only-injected', node=target, mount=str(archive))
    try:
        f.admin(leader, 'snapshot')
    except subprocess.SubprocessError as failure:
        f.event('snapshot-control-error', error=str(failure))
    time.sleep(5)
    log = (f.root / ('node' + str(target) + '.log')).read_text()
    assert 'read-only' in log.lower() or 'read only' in log.lower(), log[-2500:]
    f.event('PASS', scenario='archive-write-error-reported', log=log[-2500:])
    f.stop(target)
    # Killing the target releases its private read-only bind mount.
    f.start(target)
    f.ready()
    time.sleep(5)
    f.gate('verify')
    f.admin(f.leader(), 'snapshot')
    time.sleep(5)
    views = [json.loads(f.admin(i, 'snapshot-state').split('QA ')[1]) for i in range(3)]
    assert views[0] == views[1] == views[2], views
    f.event('PASS', scenario='archive-write-restored-all-replicas-equal', views=views)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', required=True)
    parser.add_argument('--product', default='SPOT')
    parser.add_argument('--scenario', choices=['smoke', 'availability', 'storage', 'snapshot-cut', 'corrupt-log', 'read-only'], default='smoke')
    args = parser.parse_args()
    assert os.geteuid() == 0, 'run in isolated Linux VM with sudo'
    fixture = Fixture(args.root, args.product)
    try:
        {'smoke': smoke, 'availability': availability, 'storage': storage,
         'snapshot-cut': snapshot_cut, 'corrupt-log': corrupt_log, 'read-only': read_only_disk}[args.scenario](fixture)
    except BaseException as error:
        fixture.event('FAIL', error=repr(error))
        raise
    finally:
        fixture.cleanup()
