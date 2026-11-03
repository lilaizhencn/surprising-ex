# Single-Node Perpetual Test Deployment

This deployment starts `LINEAR_PERPETUAL` with one real Core member and existing PostgreSQL, Redis/Valkey, and Kafka. It is a test topology, not high availability.

## Startup order

1. Verify infrastructure and apply missing schema/migrations.
2. Start Core and wait for the single member to become Leader and pass ClusterProbe.
3. Start Gateway, including its application-side MediaDriver and local trading/account/instrument services.
4. Start Price, Realtime, Derivatives Lifecycle (including funding), then Maker.

The current complete backend has six JVMs: Core, Gateway, Price, Realtime, Lifecycle, and Maker. Export runs inside Realtime; the application driver runs inside Gateway. Pass a one-host member list to clients; do not duplicate one host into three nonexistent members.

## Build

Use HotSpot JDK 27 and install the matching exchange-core dependency first.

```bash
java -version
mvn -version
mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-tools,surprising-price/surprising-price-provider,surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider,surprising-realtime/surprising-realtime-provider,surprising-gateway,surprising-maker -am package -DskipTests
```

Ship source and artifacts from the same revision. Do not mix matching-library versions. Internal local business invocation does not remove public authentication, permissions, or validation.

## Installation and secrets

```bash
install -d -m 0750 /var/lib/surprising/linear-perpetual/runtime
install -d -m 0750 /etc/surprising
cp deployment/test-single-node/linear-perpetual.env.example /etc/surprising/linear-perpetual.env
chmod 600 /etc/surprising/linear-perpetual.env
```

Set real database credentials and `GATEWAY_JWT_SECRET`, including in test environments. Business Kafka topics are product-scoped via `ProductTopicNames`; the global instrument topic is `surprising.instrument.events.v1`. Check broker auto-creation policy before starting.

Heap ceilings can be set separately with `JVM_CORE_XMX`, `JVM_GATEWAY_XMX`, and `JVM_PROVIDER_XMX`; otherwise `JVM_XMX` applies. `JVM_XMS` sets the initial heap. Count native buffers, mapped recordings, thread stacks, and Kafka memory separately. Validate actual JVM arguments and system free memory after changes.

Maker startup is YAML-based, disabled/empty by default; persist wanted hot settings before restart and verify real funds/books before enabling. See [Maker](../../surprising-maker/README.md).

## Network and proxy boundary

Gateway public traffic uses 9094 behind HTTPS; WebSocket is `/ws/v1`. Realtime 9095 and Core/Archive/realtime UDP endpoints stay local/private. `/healthz` is a local ingress probe of Gateway readiness, not a public rate-limit bypass.

When changing Nginx configuration, install both security snippets and validate:

```bash
install -d -m 0755 /etc/nginx/snippets
install -m 0644 deployment/test-single-node/surprising-api-proxy.conf /etc/nginx/snippets/
install -m 0644 deployment/test-single-node/surprising-admin-headers.conf /etc/nginx/snippets/
nginx -t
```

Trust only actual proxy addresses. Same-host loopback is the default deployment case; configure `GATEWAY_ADMIN_TRUSTED_PROXY_IP_ALLOWLIST` for actual remote proxies. Configure admin browser origins, HTTPS, private probes, and ClamAV scanning. Upload scanning fails closed if unavailable. See [Gateway security](../../docs/gateway-security.md).

## Upgrade, startup, and shutdown

Stop writes/tasks, allow committed export to stop, snapshot the old Core, and shut down normally. Back up Archive, database, and checkpoints; retain balances, positions, and identities. An optional temporary `CORE_EXPECTED_STATE_HASH` checks the paused 16-character lowercase state hash before HTTP services start. Remove it after successful upgrade validation.

```bash
ACTION=dry-run ./scripts/linear-perpetual-single-node.sh
cp deployment/test-single-node/surprising-linear-perpetual.service /etc/systemd/system/surprising-linear-perpetual.service
systemctl daemon-reload
systemctl start surprising-linear-perpetual
systemctl status surprising-linear-perpetual --no-pager
./scripts/linear-perpetual-single-node.sh status
curl -fsS http://127.0.0.1:9094/actuator/health/readiness
```

The unit allows 15 minutes for ordered multi-JVM startup. `active (exited)` means the launcher finished. Runtime PID/log/Core/JFR/checkpoint files are under `/var/lib/surprising/linear-perpetual/runtime/linear-perpetual-single-node/`.

```bash
systemctl stop surprising-linear-perpetual
```

Stop Realtime/export before Core/Archive. Preserve the old exporter checkpoint (`TRADE_EXPORT_CHECKPOINT`) when moving directories. The Core Archive path is not the application MediaDriver path. Stop any previous driver owner before reusing `APP_AERON_DIR`; do not overwrite an active directory.

Deposits, withdrawals, and cross-product transfers require appropriate SPOT Core/account access registered through administration. Product containers hot-load database configuration; do not deploy a second legacy Gateway or reintroduce removed product-list switches. Apply existing-environment migrations, including product registration changes, as required.

## Acceptance limits

A running unit does not establish three-member failover, sustained capacity, external-feed reliability, or full order/position/liquidation/funding conservation. Validate those separately before treating the deployment as production-ready.

## Architecture reference

See the [project architecture and class-level trading flows](../../README.md). Historical validation notes describe their recorded versions, not guarantees for the current implementation.
