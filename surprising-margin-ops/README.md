# surprising-margin-ops

[English](README.md) | [简体中文](README_CN.md)

Margin-operation APIs and providers for risk, liquidation, funding, insurance, and ADL.

## Modules

- `surprising-risk-api` / `surprising-risk-provider`: risk snapshot query contracts, margin scanning, and liquidation candidate generation.
- `surprising-liquidation-api` / `surprising-liquidation-provider`: liquidation order query contracts and reduce-only liquidation execution.
- `surprising-funding-api` / `surprising-funding-provider`: perpetual funding-rate query contracts, publishing, settlement, and account-ledger integration.
- `surprising-insurance-api` / `surprising-insurance-provider`: insurance fund query/adjustment contracts, liquidation-fee collection, and bankruptcy deficit coverage.
- `surprising-adl-api` / `surprising-adl-provider`: ADL queue and event contracts, residual deficit allocation, and deleveraging execution.
- `surprising-margin-ops-provider`: combined deployable jar for the five providers above.

## Combined Provider Deployment

`surprising-margin-ops-provider` starts the existing risk, liquidation, funding, insurance, and ADL provider components in one JVM. This is only a packaging merge:

- Business packages remain isolated under `com.surprising.risk`, `com.surprising.liquidation`, `com.surprising.funding`, `com.surprising.insurance`, and `com.surprising.adl`.
- The five services still coordinate through their existing PostgreSQL tables, Kafka topics, outbox rows, idempotency keys, leases, and sequences.
- No module reads another module's in-memory state directly.
- The original standalone provider jars remain available for split deployment.

The combined jar defaults to port `9088`, serving the existing API paths:

```text
/api/v1/risk
/api/v1/admin/risk
/api/v1/liquidations
/api/v1/funding
/api/v1/insurance
/api/v1/adl
```

When using the combined provider behind gateway, point all margin-operation routes to the same base URL:

```bash
export GATEWAY_ROUTE_RISK_BASE_URL=http://localhost:9088
export GATEWAY_ROUTE_LIQUIDATION_BASE_URL=http://localhost:9088
export GATEWAY_ROUTE_FUNDING_BASE_URL=http://localhost:9088
export GATEWAY_ROUTE_INSURANCE_BASE_URL=http://localhost:9088
export GATEWAY_ROUTE_ADL_BASE_URL=http://localhost:9088
```

## Local Run

Combined process:

```bash
mvn -pl :surprising-margin-ops-provider -am spring-boot:run
```

Standalone processes remain available:

```bash
mvn -pl :surprising-risk-provider -am spring-boot:run
mvn -pl :surprising-liquidation-provider -am spring-boot:run
mvn -pl :surprising-funding-provider -am spring-boot:run
mvn -pl :surprising-insurance-provider -am spring-boot:run
mvn -pl :surprising-adl-provider -am spring-boot:run
```

## Verification

```bash
mvn -pl :surprising-margin-ops-provider -am test
mvn -pl :surprising-margin-ops-provider -am -DskipTests package
```
