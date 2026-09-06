#!/usr/bin/env python3
"""Render one product's three-member cluster and application process configurations.
Generated files contain secrets and must stay outside Git. No EC2 resources are created.
"""
import argparse
import ipaddress
import json
import pathlib
import re

PRODUCTS = ['SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL', 'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION']
APPS = {
    'instrument': ('surprising-instrument-provider', 512),
    'market-data': ('surprising-market-data-provider', 1024),
    'price': ('surprising-price-provider', 768),
    'trading': ('surprising-trading-provider', 1024),
    'account': ('surprising-account-provider', 768),
    'funding': ('surprising-funding-provider', 384),
    'derivatives-lifecycle': ('surprising-derivatives-lifecycle-provider', 512),
    'realtime': ('surprising-realtime-provider', 512),
    'gateway': ('surprising-gateway', 768),
    'maker': ('surprising-maker', 512),
}
OPENS = ['--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED', '--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED']

def validate(c):
    assert c['productLine'] in PRODUCTS, 'unknown productLine'
    assert len(c['coreHosts']) == 3 and len(set(c['coreHosts'])) == 3, 'three distinct Core IPs required'
    for host in [*c['coreHosts'], c['appHost'], c['valkeyHost']]:
        ip = ipaddress.ip_address(host)
        assert ip.version == 4 and ip.is_private and not ip.is_loopback and not ip.is_unspecified, 'private IPv4 required'
    assert c['appHost'] not in c['coreHosts'], 'application host must be separate'
    assert re.fullmatch(r'/opt/surprising/releases/[0-9a-f]{40}', c['releaseDirectory']), 'release must use full commit SHA'
    for key in ['javaHome', 'dataDirectory']:
        assert re.fullmatch(r'/[A-Za-z0-9_./-]+', c[key]) and '..' not in pathlib.Path(c[key]).parts
    assert re.fullmatch(r'[a-z_][a-z0-9_-]*', c['serviceUser'])
    for key in ['databasePassword', 'valkeyPassword', 'gatewayJwtSecret', 'accountInternalSecret']:
        assert c[key] and 'REPLACE' not in c[key], key + ' must be supplied'
    assert len(c['gatewayJwtSecret']) >= 32, 'JWT secret must be at least 32 characters'

def write_json(path, data):
    path.write_text(json.dumps(data, indent=2) + '\n')
    path.chmod(0o600)

def render(c, output):
    validate(c)
    output.mkdir(parents=True, exist_ok=False)
    output.chmod(0o700)
    product = c['productLine']; lower = product.lower(); ordinal = PRODUCTS.index(product)
    release = c['releaseDirectory']; data = c['dataDirectory']; app = c['appHost']
    hosts = ','.join(c['coreHosts']); shared = f'/dev/shm/surprising-app-{lower}'
    tools = release + '/artifacts/surprising-aeron-tools.jar'
    service = release + '/artifacts/surprising-aeron-service.jar'

    def process(hostdir, name, arguments, heap=512, environment=None, needs_driver=False):
        dest = output / hostdir; dest.mkdir(exist_ok=True)
        work = f'{data}/{lower}/{name}'
        log = f'{work}/logs'
        args = [f'-Xms{heap}m', f'-Xmx{heap}m', '-XX:+UseG1GC', '-XX:NativeMemoryTracking=summary',
                '-XX:+ExitOnOutOfMemoryError', *OPENS,
                f'-Xlog:gc*,safepoint:file={log}/gc.log:time,uptime,level,tags:filecount=5,filesize=20M', *arguments]
        write_json(dest / f'{name}.json', {'javaHome':c['javaHome'], 'directories':[work,log],
                   'workDirectory':work, 'arguments':args, 'environment':environment or {}})
        unitname = f'surprising-{lower}-{name}'
        unit = ['[Unit]', f'Description=Surprising {product} {name}', 'After=network-online.target', 'Wants=network-online.target']
        if needs_driver:
            unit += [f'After=surprising-{lower}-driver.service', f'Requires=surprising-{lower}-driver.service']
        unit += ['[Service]', 'Type=simple', f'User={c["serviceUser"]}', 'UMask=0077',
                 f'ExecStart=/usr/bin/python3 {release}/deployment/aws/run-java.py /etc/surprising/{lower}/{name}.json',
                 'LimitNOFILE=65536', 'TimeoutStopSec=120', 'KillSignal=SIGTERM', 'Restart=no',
                 '[Install]', 'WantedBy=multi-user.target', '']
        (dest / f'{unitname}.service').write_text('\n'.join(unit))

    for node, host in enumerate(c['coreHosts']):
        # ClusterTopology adds the product/node suffix to aeron.dir.
        base = '/dev/shm/aeron'; actual = f'{base}-surprising-{lower}-{node}'
        process(f'core{node}', 'core', [
            f'-Daeron.dir={base}', f'-Dsurprising.aeron.product-line={product}',
            f'-Dsurprising.aeron.node-id={node}', f'-Dsurprising.aeron.hostnames={hosts}',
            f'-Dsurprising.aeron.data-dir={data}/aeron', '-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK',
            '-Dsurprising.aeron.matching-engines=1', '-Dsurprising.aeron.risk-engines=0',
            '-Dsurprising.aeron.account-lanes=4', '-Dsurprising.aeron.max-concurrent-sessions=64',
            f'-Dsurprising.realtime.directory={actual}',
            f'-Dsurprising.realtime.channel=aeron:udp?endpoint={app}:21010',
            f'-Dsurprising.realtime.control-channel=aeron:udp?endpoint={host}:21020',
            '-jar', service], heap=4096)

    # A single exporter owns the product's Kafka transactional ID. Do not start one per member.
    process('core0', 'trade-export', ['-cp', tools, 'com.surprising.aeron.tools.CommittedTradeExportMain',
            product, f'{data}/aeron/{lower}/node0/cluster', f'/dev/shm/aeron-surprising-{lower}-0',
            'aeron:ipc?term-length=1m', c['kafkaBootstrap'],
            f'{data}/{lower}/trade-export/checkpoint.bin', str(100+ordinal)], heap=1024)

    process('app', 'driver', [f'-Daeron.dir={shared}', '-Daeron.threading.mode=SHARED',
            '-cp', tools, 'io.aeron.driver.MediaDriver'], heap=128)
    common = {
        'spring.datasource.url': c['databaseUrl'], 'spring.datasource.username': c['databaseUser'],
        'spring.datasource.password': c['databasePassword'], 'spring.data.redis.host':c['valkeyHost'],
        'spring.data.redis.password':c['valkeyPassword'], 'spring.data.redis.connect-timeout':'500ms',
        'spring.data.redis.timeout':'500ms', 'spring.kafka.bootstrap-servers':c['kafkaBootstrap'],
        'surprising.realtime.directory':shared,
        'surprising.gateway.security.jwt-secret':c['gatewayJwtSecret'],
        'surprising.websocket.security.jwt-secret':c['gatewayJwtSecret'],
        'surprising.account.internal-service-secret':c['accountInternalSecret'],
    }
    # Module-specific Kafka properties do not inherit spring.kafka automatically.
    for prefix in ['price.consumer', 'price.index.kafka', 'price.mark.kafka', 'instrument.kafka',
                   'trading.order.kafka', 'account.kafka', 'candlestick.kafka', 'websocket.kafka',
                   'funding.kafka', 'insurance.kafka', 'adl.kafka']:
        common[f'surprising.{prefix}.bootstrap-servers'] = c['kafkaBootstrap']
    for name, (artifact, heap) in APPS.items():
        config = dict(common)
        if name in ('trading','account','gateway'):
            config['surprising.realtime.enabled'] = True
        if name in ('price','market-data'):
            config.update({'surprising.realtime.publish-json':True,
                           'surprising.realtime.channel':f'aeron:udp?endpoint={app}:21010'})
        if name == 'gateway':
            config['surprising.realtime.ws.channel'] = f'aeron:udp?endpoint={app}:21030'
        if name == 'realtime':
            config.update({'surprising.realtime.router.directory':shared,
                'surprising.realtime.router.channel':f'aeron:udp?endpoint={app}:21010',
                'surprising.realtime.router.control-channels':{product:'aeron:udp?control-mode=manual'},
                'surprising.realtime.router.control-destinations':{product:[f'aeron:udp?endpoint={h}:21020' for h in c['coreHosts']]}})
        environment = {'PRODUCT_LINE':product, 'AERON_CLUSTER_HOSTNAMES':hosts, 'AERON_EGRESS_HOSTNAME':app,
                       'AERON_CLIENT_CONNECTIONS':'4', 'AERON_CLIENT_EGRESS_HOSTNAME':app,
                       'KAFKA_BOOTSTRAP_SERVERS':c['kafkaBootstrap'], 'SPRING_APPLICATION_JSON':json.dumps(config),
                       'ACCOUNT_INTERNAL_SERVICE_SECRET':c['accountInternalSecret'],
                       'GATEWAY_JWT_SECRET':c['gatewayJwtSecret']}
        process('app', name, [f'-Daeron.dir={shared}', '-jar', f'{release}/artifacts/{artifact}-1.0.0-SNAPSHOT-exec.jar'],
                heap, environment, needs_driver=True)
    # Probe defaults to a read query; no synthetic financial or capacity work is started.
    process('app', 'probe', [f'-Daeron.dir={shared}', f'-Dsurprising.aeron.product-line={product}',
            f'-Dsurprising.aeron.hostnames={hosts}', f'-Dsurprising.aeron.egress-hostname={app}',
            '-Dsurprising.aeron.probe-mode=query', '-cp',tools,'com.surprising.aeron.tools.ClusterProbeMain'], needs_driver=True)
    ports = {f'core{n}':list(range(20000+ordinal*1000+n*100+1,20000+ordinal*1000+n*100+6)) for n in range(3)}
    write_json(output/'network.json', {'coreStaticUdpPorts':ports,'realtimeUdpPorts':[21010,21020,21030],
        'dynamicUdp':'Aeron egress, replication and archive response use endpoint :0. Permit UDP within the dedicated test security group only; no public UDP.',
        'hosts':[*c['coreHosts'],app]})

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('inventory', type=pathlib.Path)
    parser.add_argument('output', type=pathlib.Path)
    args=parser.parse_args()
    render(json.loads(args.inventory.read_text()), args.output)
    print('Generated configuration; credentials are stored with mode 0600. Nothing started.')

if __name__ == '__main__':
    main()
