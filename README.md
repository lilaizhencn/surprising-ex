# surprising-ex

English | [简体中文](README_CN.md)

Surprising-EX is a multi-product exchange backend built with Java 21, PostgreSQL, Kafka, and Redis/Valkey.
The repository covers spot, USDT perpetuals, USDT delivery futures, and European cash-settled options.
Production processes run one product line each with isolated topics, consumer groups, order books, and account types.

## Core boundaries

- PostgreSQL is authoritative for orders, balances, positions, collateral, ledgers, and risk state.
- Account-provider is the sole writer of funds, positions, collateral, and deficits. Other modules submit
  user-partitioned account commands.
- Cross-service consistency uses local transactions, transactional outbox, at-least-once Kafka delivery,
  and idempotent consumers. It does not use XA.
- Trading order and matching outboxes claim a capped contiguous prefix per `topic + eventKey` with one
  pending-row window scan and MVCC compare-and-set, pipeline independent streams concurrently, and batch-mark Kafka acknowledgements.
- User positions are read only from a Redis Hash projection. Open-order reads prefer a Redis ZSET/Hash
  projection. Redis never authorizes fills, cancellations, funds movements, or liquidation.
- Account commands use `<PRODUCT_LINE>:<userId>` as their Kafka key and are serialized across 32 partitions.
- Matching commands, trades, depth, and prices use `symbol` as their key. Commands for one symbol must stay ordered.
- Internal market-maker self-matches still produce public trades, depth, candles, and WebSocket updates, but
  skip economic trades and settlement. Trades with real users use the full settlement path.

## Product lines

| Product | `ProductLine` | Account type | Topic prefix |
|---|---|---|---|
| Spot | `SPOT` | `SPOT` | `surprising.spot` |
| USDT perpetual | `LINEAR_PERPETUAL` | `USDT_PERPETUAL` | `surprising.linear-perp` |
| USDT delivery | `LINEAR_DELIVERY` | `USDT_DELIVERY` | `surprising.linear-delivery` |
| European option | `OPTION` | `OPTION` | `surprising.option` |

`INVERSE_PERPETUAL` and `INVERSE_DELIVERY` have shared enums and topic mappings, while process-level
acceptance currently focuses on the four lines above.

## Modules

| Module | Responsibility |
|---|---|
| `surprising-product-api` | Product lines, account types, and topic naming |
| `surprising-instrument` | Symbols, contract rules, risk tiers, and lifecycle |
| `surprising-price` | Index price, mark price, and FX |
| `surprising-trading` | Orders, triggers, algo orders, and exchange-core matching |
| `surprising-account` | Balances, ledgers, commands, settlement, positions, and collateral |
| `surprising-margin-ops` | Risk, liquidation, funding, insurance, and ADL |
| `surprising-candlestick` | Kafka Streams and RocksDB candles |
| `surprising-edge` | REST gateway and WebSocket fanout |
| `surprising-market-maker` | Internal quoting and trading-path load generation |

## Build and local run

JDK 21 is required. Start PostgreSQL, Kafka, and Redis, then initialize the schema and topics:

```bash
mvn -DskipTests package
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
PRODUCT_LINES=LINEAR_PERPETUAL PARTITIONS=32 ACCOUNT_COMMAND_PARTITIONS=32 ./scripts/create-topics.sh
PRODUCT_LINE=LINEAR_PERPETUAL BUILD_SERVICES=false ./scripts/start-product-line-providers.sh
```

Matching uses exchange-core/OpenHFT and requires the JVM module flags listed in
[Deployment](docs/deployment.md). Default combined processes use ports `9080` instrument, `9081` candlestick,
`9082` price, `9084` trading-entry, `9085` matching, `9086` account, `9088` margin-ops, `9094` edge,
and `9096` market-maker.

## Test

```bash
mvn test
./scripts/integration-smoke.sh

PRODUCT_LINES=LINEAR_PERPETUAL \
BUILD_SERVICES=auto \
CREATE_KAFKA_TOPICS=true \
RECONCILE_FUNDS=true \
./scripts/product-line-api-flow-smoke.sh
```

The product-line smoke runs real API order entry, market making, matching, account settlement, self-close,
liquidation, and applicable funding/delivery/exercise flows, then performs funds-conservation reconciliation.
Stress reports default to a temporary directory; only stable, reproducible guidance belongs in the repository.

## Production

Before deployment:

- disable Kafka auto topic creation and use [create-topics.sh](scripts/create-topics.sh);
- for the initial perpetual deployment, create all regular and account-command topics with exactly 32
  partitions, RF=3, and `min.insync.replicas=2`;
- do not add partitions in place to existing symbol-keyed topics; use a versioned-topic migration and state rebuild;
- isolate topics, groups, client ids, coordinator node ids, and gateway routes by product line;
- run the order-provider account-command-result listener at the 32-partition ceiling; records remain ordered by
  `productLine:userId`, while each poll applies order transitions and ACCEPTED/PLACE Outbox writes in batches;
- use persistent `noeviction` Redis/Valkey compatible with same-hash-tag Lua operations;
- retain PostgreSQL durability and monitor Kafka lag, Outbox backlog/age, database locks and slow SQL,
  Redis readiness, JVM GC, failover behavior, and zero-difference funds reconciliation.

See [Deployment](docs/deployment.md) for the exact topic inventory and verification commands. The concrete
single-line AWS baseline is documented in
[LINEAR_PERPETUAL AWS production baseline](docs/linear-perpetual-aws-production-deployment_CN.md).

## Documentation

- [Documentation index](docs/README.md)
- [Deployment and topic planning](docs/deployment.md)
- [Database design](docs/database.md)
- [Product-line architecture](docs/product-line-architecture.md)
- [Account single-writer lane](docs/account-single-writer-command-lane_CN.md)
- [Position Redis read model](docs/position-redis-cache.md)
- [Open-order Redis projection](docs/open-order-redis-cache.md)
- [Testing and funds conservation](docs/product-line-testing-and-funds-reconciliation.md)
