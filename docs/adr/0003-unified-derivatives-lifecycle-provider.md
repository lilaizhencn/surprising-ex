# ADR 0003: Unified derivatives lifecycle provider

## Status

Accepted — 2026-08-18

## Decision

Risk, Liquidation, Insurance and ADL source code is owned by one `surprising-derivatives-lifecycle` project and runs in one JVM per ProductLine on port `9087`. Their existing API contracts, routes and Kafka consumer group semantics remain compatible. The project contains one `surprising-derivatives-lifecycle-api` contract artifact and one `surprising-derivatives-lifecycle-provider` implementation artifact; the four legacy modules are deleted.

## Boundaries

- Aeron Core remains the only authority for risk, liquidation, insurance, ADL and funds state.
- PostgreSQL is used only by Instrument administration and asynchronous historical/projection repositories; it is not on the Core command decision path.
- Kafka remains only for the existing insurance/ADL event consumers and exporter boundaries.
- ProductLine isolation and all public API paths are unchanged.
- Funding remains a separate Provider because it has a distinct lifecycle and lease model.

## Operations

Build and start `surprising-derivatives-lifecycle-provider` instead of the four old processes. The lifecycle project owns one `DerivativesAeronClient`, one shared Instrument snapshot cache/initializer and one Spring health/database configuration. Use the JDK 25 Aeron flags already emitted by `runtime/w3-w5/run.sh` (`--add-exports java.base/jdk.internal.misc=ALL-UNNAMED`).
