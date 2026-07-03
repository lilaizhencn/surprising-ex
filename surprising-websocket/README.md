# surprising-websocket

[简体中文](README_CN.md)

Client-facing WebSocket fanout for the exchange.

This service is not a calculator. It consumes Kafka domain events, filters subscriptions in memory, and pushes realtime updates to clients connected to the current node.

## Modules

- `surprising-websocket-api`: shared channel, command, and server-message protocol models.
- `surprising-websocket-provider`: Spring Boot WebSocket server and Kafka fanout consumer.

## Endpoint

- HTTP port: `9093`
- WebSocket path: `/ws/v1`

Example public subscription:

```json
{"op":"subscribe","id":"c1","channel":"candles","symbol":"BTC-USDT","period":"1m"}
```

Example public depth subscription:

```json
{"op":"subscribe","id":"d1","channel":"depth","symbol":"BTC-USDT"}
```

Example private position subscription:

```json
{"op":"subscribe","id":"p1","channel":"positions","symbol":"BTC-USDT"}
```

Example private risk subscriptions:

```json
{"op":"subscribe","id":"r1","channel":"positionRisk","symbol":"BTC-USDT"}
{"op":"subscribe","id":"r2","channel":"accountRisk"}
```

Private channels require an authenticated user id. In production the ingress/auth layer should inject `X-User-Id`; direct local debugging may pass the same value as a query parameter, but that is not a production security model.

## Channels

| Channel | Public | Required fields | Source topic |
| --- | --- | --- | --- |
| `candles` | yes | `symbol`, `period` | `surprising.perp.candle.events.v1` |
| `trades` | yes | `symbol` | `surprising.perp.trade.events.v1` |
| `depth` | yes | `symbol` | `surprising.perp.orderbook.depth.v1` |
| `index` | yes | `symbol` | `surprising.perp.index.price.v1` |
| `mark` | yes | `symbol` | `surprising.perp.mark.price.v1` |
| `funding` | yes | `symbol` | `surprising.perp.funding.rate.v1` |
| `orders` | no | optional `symbol` | `surprising.perp.order.events.v1` |
| `matches` | no | optional `symbol` | `surprising.perp.match.results.v1`, `surprising.perp.match.trades.v1` |
| `positions` | no | optional `symbol` | `surprising.account.position.events.v1` |
| `positionRisk` | no | optional `symbol` | `surprising.risk.position.events.v1` |
| `accountRisk` | no | optional `symbol` ignored as wildcard | `surprising.risk.account.events.v1` |

Private subscriptions without `symbol` use wildcard symbol `*` and receive all events for the authenticated user.

## Depth Push Flow

Depth is produced by matching from the live exchange-core L2 book:

```text
order command
  -> exchange-core mutates the book
  -> matching compares the latest L2 book with the previous in-memory book image
  -> matching outbox publishes surprising.perp.orderbook.depth.v1
  -> websocket node consumes the event and pushes channel=depth
```

The first depth event for a symbol is `SNAPSHOT`. Later events are usually `DELTA` and contain only changed price levels. Delta levels are absolute price-level states, not quantity differences; replace the local level, or delete it when `quantitySteps=0`.

Robust client flow:

1. Subscribe to `depth` and buffer events for the symbol.
2. Load `GET /api/v1/gateway/trading-market/orderbook?symbol=BTC-USDT&depth=50`.
3. Initialize the local book from the REST snapshot and its `sequence`.
4. Apply buffered/new deltas only when `previousSequence` equals the local last sequence.
5. On reconnect or sequence gap, discard the local book, reload the REST snapshot, and resubscribe.

## Position Push Flow

Positions are pushed only after account settlement:

```text
matching trade
  -> account consumes match trade
  -> account updates balances, margin, PnL, fees, and positions in one DB transaction
  -> account writes account_outbox_events row for POSITION_UPDATED
  -> account outbox publisher sends surprising.account.position.events.v1
  -> websocket node consumes event and sends it to matching private subscriptions
```

This prevents the frontend from seeing a position derived from raw matching output before account state is authoritative.

The account outbox is at-least-once. Clients should treat `eventId` and `tradeId` as dedupe/version hints when updating local state.
Private position messages also carry the original trading `traceId` when available, so support and operations can correlate a WebSocket push with the order, matching trade, and account settlement rows.

`positions` is an account-state channel, not a realtime PnL calculator. It carries signed quantity, entry price, realized PnL, and margin mode after account settlement. For backend-authoritative unrealized PnL, equity, maintenance margin, and margin ratio, subscribe to:

- `positionRisk`: per-position risk snapshot including `markPriceTicks`, `notionalUnits`, `unrealizedPnlUnits`, `maintenanceMarginUnits`, `positionMarginUnits`, `marginRatioPpm`, and `status`.
- `accountRisk`: per `userId + settleAsset` account risk snapshot including wallet balance, total unrealized PnL, equity, maintenance margin, margin ratio, and status.

Frontend clients may locally combine `positions` and public `mark` updates for high-frequency visual interpolation, but risk-provider `positionRisk`/`accountRisk` events are the values that match liquidation decisions.
Risk events triggered by account position updates carry the original trading `traceId` when available; fallback scheduled scans may not have one.

## User State And Node Selection

WebSocket user state is local and transient:

- `ClientConnection` keeps the current socket, authenticated `userId`, and bounded outbound queue.
- `SubscriptionRegistry` keeps `sessionId -> subscriptions` and `subscription -> local sessions`.
- No account balance, position, margin, or order authority is stored in WebSocket memory.

User-related events are delivered by local filtering, not by routing the event to one chosen node:

```text
position event on Kafka
  -> every WebSocket node consumes it with its own consumer group
  -> each node checks local subscriptions for userId + channel + symbol
  -> only nodes that currently host matching sessions push to clients
  -> other nodes drop the event locally
```

If the same user is connected on two devices through two different nodes, both nodes can push the same account update to their own local session. If a node dies, the client reconnects to any healthy node and resubscribes.

## Horizontal Scaling

- Deploy at least two WebSocket nodes.
- Every WebSocket node must use a unique Kafka consumer group id, for example the default `surprising-websocket-${HOSTNAME:${random.uuid}}`.
- Do not share one group id across all WebSocket pods. A shared group would deliver each Kafka record to only one pod, and clients connected to other pods would miss public market updates.
- Prefer an explicit stable pod/node value such as `surprising-websocket-${POD_NAME}` in production. A pure random group id on every restart leaves stale consumer groups in Kafka and makes lag dashboards noisy.
- No cross-node session state is required. Each node keeps only local WebSocket sessions and local subscription maps.
- A load balancer can distribute new connections across nodes. Existing WebSocket TCP connections are naturally tied to one node; on reconnect the client must resubscribe.
- Public market events are consumed by every node and filtered by local subscriptions.
- Private events are consumed by every node and filtered by authenticated `userId` before sending.
- Each connection has a bounded outbound queue. Slow clients are closed instead of letting one client exhaust memory or block fanout.
- Open candle updates are coalesced by `surprising.websocket.fanout.candle-partial-coalesce-window`; closed candles are pushed immediately.

## Configuration

```yaml
surprising:
  websocket:
    kafka:
      bootstrap-servers: localhost:9092
      group-id: surprising-websocket-${HOSTNAME:${random.uuid}}
      concurrency: 2
      max-poll-records: 1000
      order-book-depth-topic: surprising.perp.orderbook.depth.v1
      position-events-topic: surprising.account.position.events.v1
      account-risk-events-topic: surprising.risk.account.events.v1
      position-risk-events-topic: surprising.risk.position.events.v1
    session:
      max-subscriptions: 200
      outbound-queue-capacity: 1000
      send-timeout: 5s
    security:
      user-id-header: X-User-Id
      allowed-origins:
        - "https://app.example.com"
        - "https://m.example.com"
    fanout:
      candle-partial-coalesce-window: 250ms
```

The default `allowed-origins: ["*"]` is convenient for local development. Production should use exact HTTPS origins.

For very large public fanout, add an internal pub/sub layer such as Redis, NATS, or a dedicated market-data fanout tier later. The current design is intentionally simple and correct for horizontal WebSocket pods because Kafka already carries the authoritative event stream.

## Operations

- Admin metrics endpoint: `GET /api/v1/admin/websocket/metrics`. It requires the gateway-injected `X-Admin-User-Id` header and returns node-local connections, authenticated/anonymous connections, subscriptions, unique topics, and channel distribution.
- The service also exposes Micrometer gauges for `/actuator/prometheus`: `surprising.websocket.connections.active`, `surprising.websocket.connections.authenticated`, `surprising.websocket.subscriptions.active`, and `surprising.websocket.topics.active`.
- Monitor WebSocket connection count, outbound queue pressure, Kafka consumer lag, and send timeout closes.
- Alert on lag for active WebSocket node groups only. Old random groups from retired nodes should be deleted or filtered out of dashboards.
- Keep Kafka topics partitioned by `symbol`.
- Frontend clients should call REST first for a snapshot, then subscribe to WebSocket deltas.
- Clients should send periodic `ping` messages and reconnect with exponential backoff.
- Reconnect logic must resubscribe all channels after the socket opens.

## Build And Test

```bash
mvn -pl :surprising-websocket-provider -am test
mvn -pl :surprising-websocket-provider -am spring-boot:run
```
