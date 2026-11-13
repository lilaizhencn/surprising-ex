# Local Perpetual Environment

Prerequisites: HotSpot JDK 27, Maven, Node, PostgreSQL 18, Kafka, and Redis/Valkey. Install the adjacent `surprising-ex-web` frontend dependencies with `npm ci`.

```bash
./scripts/local-perpetual.sh up
./scripts/local-perpetual.sh status
./scripts/local-perpetual.sh down
```

The launcher runs linear-perpetual Core, Gateway, Price, Realtime, Derivatives Lifecycle, Maker, and the user Web frontend. Official local start uses one Aeron Core node, one account lane thread, and one matcher. Wallet is not started. macOS uses launchd. `build` builds the backend; the launcher prepares the running frontend copy.

## Persistent data and configuration

On first use, the launcher creates `~/.local/share/surprising-ex/perpetual-pmm-20/local.env`. Use it for deployment parameters such as database, messaging, ports, and secrets. Keep the existing run-directory identity. Set `MANAGE_POSTGRES=false` for an externally managed database.

A new managed database uses `init.sql` and `catalog.sql` for BTC, ETH, and SOL contracts. Never rerun initialization against an existing database. Apply `deployment/migrations/` and retain funds, Core Archive, PostgreSQL, Kafka, and exporter checkpoints.

Administer instruments, index sources, and risk brackets through persisted administration. `application-local.yml` is not the business catalog. `render-local-market-ids.py` reads the database to generate health-probe parameters, not JSON instrument configuration. Instrument edits synchronize through snapshots/events and Core configuration commands without a restart.

## Maker and risk

Maker settings, strategies, and reference books initialize from YAML. Administrative hot updates last only for the current process; old database maker tables are not read. Persist required settings in a separate startup YAML and load it through `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/absolute/path/maker.yml`, or reconfigure after restart.

The default engine is disabled and strategies are empty. Configure accounts, funds, reference books, size, and levels explicitly before enabling. The launcher does not inject funds, hardcode maker accounts, enable simulated fills, or override risk budgets. Risk work budgets account for the active scan workload and configured base budget.

After maker activation, run `python3 scripts/check-local-perpetual.py` to check current catalog instruments and two-sided books. An empty book is not permission to inject funds or enable strategies automatically.

## Upgrade and diagnostics

New JAR deployment requires down/build/up. Ordinary catalog changes do not. Shutdown preserves durable data. Do not delete a live runtime directory. Logs are under the run directory and `runtime/local-perpetual/logs`; require at least 5 GiB free before startup.

New managed test Kafka topics default to one partition, including initial internal topic configuration. Candle services follow broker defaults. Existing partitions/history are retained; changing defaults does not shrink topics. Historical fixture settings are evidence for their run, not the current business configuration source.

## Architecture reference

See the [project architecture and class-level trading flows](../../README.md). Historical validation notes describe their recorded versions, not guarantees for the current implementation.
