# Market Maker

An internal quoting and controlled-load application. `MarketMakerTask` schedules `MarketMakerService`; strategy inputs combine instrument rules, account inventory, reference depth, and configured quoting policy. Commands enter the same Core trading path as user orders.

## Runtime safety and ownership

The default engine is disabled and strategies are empty. Administrators configure maker accounts, funds, reference books, levels, and quantities before enabling a strategy. Do not expose internal control APIs to public users. Administration goes through Gateway's authenticated admin routes and audit requirements.

Live orders are identified by strategy-owned client-order identities. Cancel expired, off-target, and no-longer-required quotes. Respect product trading status, minimum amounts, integer precision, order limits, inventory limits, and available close/open capacity. A fill simulator or load strategy is an explicit test feature, not a prerequisite for ordinary market making.

## Configuration and quoting

Deployment defaults and initial strategies come from YAML. Administrative hot updates are process-local and disappear on restart unless explicitly saved into a startup YAML file. Historical maker configuration tables are not the startup authority. Use `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/absolute/path/maker.yml` where the deployment launcher supports it, or reapply administrative settings after restart.

Keep desired depth and actual live coverage separate. Reference pressure and inventory skew affect quote prices/size; minimum tick spreads and fee constraints still apply. External reference sources require freshness checks; never keep quoting from expired external depth. Consult the current strategy configuration/source for the configured local reference behavior when external inputs are unavailable.

## Interfaces

Provider port defaults to 9096. Internal routes include `/api/v1/market-maker/strategies`, strategy pause/resume, and `/run-once`. Administrative routes live under `/api/v1/admin/market-maker` and require trusted Gateway-injected administrator identity. Public clients must not supply those identity headers as proof of authorization.

Metrics distinguish inventory use, owned live orders, desired coverage, missing/stale/off-target quotes, spread, trading status, and collection failure. Operational metrics are not evidence of stable matching capacity.

## Verification

```bash
mvn -pl surprising-maker -am test
```

Use isolated accounts and explicit funds for live tests. Verify quote ownership, integer quantities, precision, risk limits, price freshness, cancellation, configuration restart behavior, and final funds/holds. Historical throughput experiments do not establish current production capacity.

## Architecture reference

See the [project architecture and class-level trading flows](../README.md). Historical validation notes describe their recorded versions, not guarantees for the current implementation.
