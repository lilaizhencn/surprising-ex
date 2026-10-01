# Surprising EX

## Project overview

Surprising EX is a Java and Maven based exchange backend under active development. It provides order matching, accounts, risk management, market data, and APIs for six separate product lines:

| Product line | Main functions |
| --- | --- |
| Spot | Orders, matching, asset holds, and settlement |
| USDT-margined perpetuals | Positions, margin, funding, and liquidation |
| Coin-margined perpetuals | Inverse contract accounting, funding, and risk |
| USDT-margined delivery futures | Positions and expiry settlement |
| Coin-margined delivery futures | Inverse contracts and expiry settlement |
| Options | Premiums, positions, exercise, and expiry |

Product line identity controls routing and keeps instruments, accounts, trading state, events, and risk rules separate. The single-node deployment below starts **USDT-margined perpetuals only** (`LINEAR_PERPETUAL`).

## Architecture

Backend snapshot field evolution and historical command recovery: [恢复兼容设计](docs/core-snapshot-evolution.md).

The single-node stack has six Java processes:

| Process | Responsibility |
| --- | --- |
| Aeron Core | Ordered commands, matching, authoritative balances and positions, snapshots, and recovery |
| Gateway | Authentication, instrument, account and order APIs, WebSocket access, and the shared application Aeron MediaDriver |
| Price | Index and mark prices |
| Realtime | Market data, candles, trade export, and realtime routing |
| Derivatives lifecycle | Funding, risk scans, and liquidation workflows |
| Maker | Reference-market-based quotes and trading |

Gateway sends trading commands to the single-member Aeron Cluster. Core owns trading state and persists its log and snapshots through Aeron Archive. Realtime exports committed trades to Kafka; consumers and query services use PostgreSQL for configuration and durable business records and Valkey for rebuildable views. Gateway delivers public market data and private account updates over WebSocket. The maker submits orders through the same trading interfaces as other clients.

The Maven modules follow these boundaries: [`surprising-aeron-core`](surprising-aeron-core/) contains the cluster and client; [`surprising-gateway`](surprising-gateway/) contains the consolidated identity, instrument, trading, and account application; [`surprising-price`](surprising-price/), [`surprising-realtime`](surprising-realtime/), [`surprising-derivatives-lifecycle`](surprising-derivatives-lifecycle/), and [`surprising-maker`](surprising-maker/) contain the other four processes. Shared API modules define product-line and business contracts.

## Single-node deployment

Run these steps on the target Linux host. The deployment uses existing PostgreSQL, Kafka, and Redis/Valkey services. Install **HotSpot JDK 27**, Maven, `psql`, and the build's normal dependencies first. Configure PostgreSQL, Kafka, and Valkey at the addresses in the environment file, or change those values to match the host.

1. Check the toolchain and build all six application JARs from the release checkout on the server:

   ```bash
   java -version
   mvn -version
   mvn -pl \
     surprising-aeron-core/surprising-aeron-service,\
     surprising-aeron-core/surprising-aeron-tools,\
     surprising-price/surprising-price-provider,\
     surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider,\
     surprising-realtime/surprising-realtime-provider,\
     surprising-gateway,\
     surprising-maker \
     -am package -DskipTests
   ```

2. Make the checkout available at `/opt/surprising-ex`. Prepare the runtime directory and a private environment file:

   ```bash
   install -d -m 0750 /var/lib/surprising/linear-perpetual/runtime /etc/surprising
   cp deployment/test-single-node/linear-perpetual.env.example \
     /etc/surprising/linear-perpetual.env
   chmod 600 /etc/surprising/linear-perpetual.env
   ```

   Set the PostgreSQL password and a unique `GATEWAY_JWT_SECRET`. Review the database and broker addresses, heap limits, and `MM_INSTRUMENT_ID` in the environment file. The launcher applies [`init.sql`](init.sql) **only when the `instruments` table is absent**. Populate the desired instrument catalog and any test users and funds through their normal application flows before relying on maker trading.

3. Load the environment for a preflight check, then install and start the systemd unit:

   ```bash
   cd /opt/surprising-ex
   set -a; . /etc/surprising/linear-perpetual.env; set +a
   ./scripts/linear-perpetual-single-node.sh dry-run
   cp deployment/test-single-node/surprising-linear-perpetual.service \
     /etc/systemd/system/surprising-linear-perpetual.service
   systemctl daemon-reload
   systemctl enable --now surprising-linear-perpetual
   ```

   The launcher starts Core first, waits for the single-node leader, then starts Gateway, Price, Realtime, Derivatives lifecycle, and Maker. Check completion and health:

   ```bash
   systemctl status surprising-linear-perpetual --no-pager
   ./scripts/linear-perpetual-single-node.sh status
   curl -fsS http://127.0.0.1:9094/actuator/health/readiness
   ```

4. For later releases, build the new checkout on the server, stop the unit, point `/opt/surprising-ex` at the new checkout, and start the unit. **Keep** the runtime directory and Aeron Archive across restarts so Core recovers from its snapshot and log. Do not rerun `init.sql` against an existing database. Use `systemctl stop surprising-linear-perpetual` to stop the stack.

The unit and example configuration are in [`deployment/test-single-node`](deployment/test-single-node/). This deployment has one Aeron member and therefore no failover quorum.
