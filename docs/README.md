# Surprising-EX Docs

This directory keeps long-lived exchange backend documentation. One-off local run reports should
stay under `/tmp` unless they are promoted into the current consolidated report.

## Current Docs

- [Deployment](deployment.md): runtime topology, Kafka topics, provider startup, failover, and operations.
- [LINEAR_PERPETUAL AWS Production Baseline](linear-perpetual-aws-production-deployment_CN.md): concise EC2, JVM, RDS, MSK, Valkey, sizing, and load-test baseline for the first perpetual product line.
- [Database Design](database.md): PostgreSQL schema responsibilities and idempotency boundaries.
- [Product-Line Split and Delivery/Options Implementation Plan](product-line-split-plan.md) / [简体中文](product-line-split-plan_CN.md): four product-line isolation model.
- [Product-Line Testing and Funds Conservation Reconciliation](product-line-testing-and-funds-reconciliation.md) / [简体中文](product-line-testing-and-funds-reconciliation_CN.md): real API smoke and reconciliation rules.
- [Full-Chain Test Checklist](full-chain-test-plan_CN.md): current product-line acceptance checklist.
- [Four Product-Line Funds and Performance Report](full-chain-funds-performance-report.md): consolidated latest high-concurrency report.
- [Matching Symbol Sharding and Capacity Notes](matching-symbol-sharding-and-capacity_CN.md): exchange-core Disruptor and symbol shard behavior.
- [Perpetual Contract Tutorial and Implementation Notes](perpetual-contract-tutorial_CN.md): user-facing perpetual concepts mapped to this codebase.
- [Local Homebrew Middleware](local-homebrew-infra.md): local PostgreSQL/Kafka setup.
- [Trading System Architecture Diagram](trading-system-architecture-fullscreen_CN.html): fullscreen architecture walkthrough.

## Current Runtime Shape

- `surprising-price-provider` is the development/small-deployment combined jar for index price and mark price. The mark-price logic still consumes index-price events through Kafka/PostgreSQL boundaries and does not read index-provider in-memory state.
- `surprising-trading-entry-provider` is the development/small-deployment combined jar for order entry and trigger orders. `surprising-matching-provider` remains separate.
- `surprising-margin-ops-provider` is the combined jar for risk, liquidation, funding, insurance, and ADL. The modules still coordinate through PostgreSQL, Kafka, outbox rows, idempotency keys, leases, and sequences.
- `surprising-edge-provider` combines REST gateway and WebSocket fanout for development/small deployments. Gateway and WebSocket providers remain independently deployable for production scaling.
- Exchange backend tests do not start wallet services. Test funds are injected through account fixtures/admin adjustments and reconciled as adjustment units.

## Cleanup Policy

- Historical order, market-maker, and stress reports are consolidated into [full-chain-funds-performance-report.md](full-chain-funds-performance-report.md).
- If a test run becomes the new evidence baseline, update the consolidated report and this index instead of adding many timestamped report files.
- If a module is merged for deployment but remains split by package/business logic, document the deployable jar and the durable boundary. Do not describe it as shared in-memory state.
