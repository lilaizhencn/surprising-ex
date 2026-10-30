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

构建基线：HotSpot JDK 27、Spring Boot 4.1.2、Spring Cloud 2025.1.4；框架版本统一由 `surprising-parent/pom.xml` 管理。

撮合库使用 fork 的 `exchange.core2:exchange-core:0.5.19-emporia`，与 Aeron 1.54.0 对齐 Agrona 2.7.0、HdrHistogram 2.2.2，
与 Spring Boot 4.1.2 BOM 对齐 Jackson 和日志等共享依赖，并统一使用 `at.yawk.lz4:lz4-java:1.11.4`。
首次构建前，在相邻的 `exchange-core-lilaizhencn` 仓库干净的 `master` 上执行 `mvn install` 安装该版本。
依赖版本与验证范围见[升级记录](docs/validation/2026-10-30-exchange-core-dependency-alignment.md)。

Core startup, snapshots and command recovery: [Aeron architecture](surprising-aeron-core/README.md).

The single-node stack has six Java processes:

| Process | Responsibility |
| --- | --- |
| Aeron Core | Ordered commands, matching, authoritative balances and positions, snapshots, and recovery |
| Gateway | Authentication, instrument, account and order APIs, WebSocket access, and the shared application Aeron MediaDriver |
| Price | Index and mark prices |
| Realtime | Market data, candles, trade export, and realtime routing |
| Derivatives lifecycle | Funding, risk scans, and liquidation workflows |
| Maker | Reference-market-based quotes and trading |
