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


## 公告、媒体与协议

公告支持富文本、图片、音频、视频及十二种语言。公开媒体复用 social 的 app-public / cdn.surprising.top，限定交易目录 public/exchange/content/；C2C、身份认证等私有附件保持原访问控制。后台上传检查格式、文件头和体积；保存草稿同事务保留引用，超过二十四小时的未引用上传由独立线程有界回收。配置示例见 [content-r2.env.example](deployment/content-r2.env.example)。

管理后台提供十类协议的草稿、各语种预览、审批发布、历史修订与审计。用户协议和隐私政策发布后，普通用户须主动确认具体修订；后续发布新修订会在再次登录或刷新时要求确认。发布历史和阅读语言保留，不覆盖旧记录。运营主体与邮箱按用户要求复用 social，交易域名保持 https://ex.tokdou.com。

首次上线须先执行三份 20261031-content-media / legal-documents / content-media-references 迁移，配置公共 R2，导入并复核协议草稿，再审批发布必需协议。步骤、地区限制和正文复核要求见 [协议内容与上线顺序](deployment/legal/README.md)。接口见 [公告媒体与协议 OpenAPI](docs/content-api.openapi.json)，验证范围见 [本轮验证](docs/validation/announcement-legal-i18n-20261030/PROGRESS.md)。
