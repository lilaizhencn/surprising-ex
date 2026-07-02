# surprising-gateway

[English](README.md)

面向前端和 BFF 的无状态 REST API 网关。

主流交易系统通常在边缘放一个统一 public gateway 或 BFF，各业务模块仍维护自己的内部 API 和配置。这个模块采用同样方式：前端只访问一个 gateway 前缀，gateway 只代理白名单里的内部服务。

## 模块

- `surprising-gateway-provider`：Spring Boot 白名单代理。

## 入口

- HTTP 端口：`9094`
- Gateway 前缀：`/api/v1/gateway/{service}`

示例：

```bash
curl 'http://localhost:9094/api/v1/gateway/candlestick/candles/latest?symbol=BTC-USDT&period=1m'
curl 'http://localhost:9094/api/v1/gateway/trading-market/orderbook?symbol=BTC-USDT&depth=50'
curl 'http://localhost:9094/api/v1/gateway/trading-trigger/open?userId=1001&symbol=BTC-USDT' -H 'X-User-Id: 1001'
curl 'http://localhost:9094/api/v1/gateway/account/1001/positions' -H 'X-User-Id: 1001'
curl 'http://localhost:9094/api/v1/gateway/market-maker/strategies' -H 'X-User-Id: ops'
```

## 路由

| Gateway service | 内部目标 | 私有 |
| --- | --- | --- |
| `instrument` | `http://localhost:9080/api/v1/instruments` | 否 |
| `candlestick` | `http://localhost:9081/api/v1/candlestick` | 否 |
| `price-index` | `http://localhost:9082/api/v1/price/index` | 否 |
| `price-fx` | `http://localhost:9082/api/v1/price/fx` | 否 |
| `price-mark` | `http://localhost:9083/api/v1/price/mark` | 否 |
| `trading` | `http://localhost:9084/api/v1/trading/orders` | 是 |
| `trading-market` | `http://localhost:9085/api/v1/trading/market` | 否 |
| `trading-trigger` | `http://localhost:9095/api/v1/trading/trigger-orders` | 是 |
| `account` | `http://localhost:9086/api/v1/accounts` | 是 |
| `risk` | `http://localhost:9087/api/v1/risk` | 是 |
| `liquidation` | `http://localhost:9088/api/v1/liquidations` | 是 |
| `funding` | `http://localhost:9089/api/v1/funding` | 否 |
| `insurance` | `http://localhost:9090/api/v1/insurance` | 是 |
| `adl` | `http://localhost:9091/api/v1/adl` | 是 |
| `market-maker` | `http://localhost:9096/api/v1/market-maker` | 是 |

Gateway 会拒绝未知 service 名称。它不会把用户输入拼成任意后端主机名，也不会处理任何动态表名。

## 安全模型

当前实现要求私有路由携带 `X-User-Id` 或 `Authorization`。生产环境应在 gateway 前完成认证，只有 token/session 验证通过后才注入可信 `X-User-Id`。
`X-Trace-Id` 所有路由都可以带；gateway 会清洗、回写给客户端并转发给后端 provider。它只用于可观测性和排障，不能参与认证或鉴权判断。

不要把内部 provider 端口直接暴露到公网。公共客户端应使用：

- REST：`surprising-gateway`
- 实时：`surprising-websocket`，或 ingress 把 `/ws/v1` 转到 WebSocket 服务

## 水平扩展

- Gateway 是无状态服务，可以挂在任意 L4/L7 负载均衡器后面。
- Gateway 至少部署 2 个实例。
- 不同环境通过 Kubernetes Service、服务发现或配置中心设置后端 `base-url`。
- REST 不需要 sticky session。
- 订单和账户路由的超时与重试策略要保守；重复 POST 应依赖客户端幂等键，而不是 gateway 盲目重试。
- 内置 HTTP client 有明确的连接/读取超时，避免后端故障时无限占用 gateway 工作线程。

## 配置

```yaml
surprising:
  gateway:
    security:
      user-id-header: X-User-Id
      require-identity-for-private-routes: true
    http-client:
      connect-timeout: 1s
      read-timeout: 30s
    routes:
      candlestick:
        base-url: http://surprising-candlestick:9081
        target-prefix: /api/v1/candlestick
        private-route: false
      account:
        base-url: http://surprising-account:9086
        target-prefix: /api/v1/accounts
        private-route: true
      trading-trigger:
        base-url: http://surprising-trigger:9095
        target-prefix: /api/v1/trading/trigger-orders
        private-route: true
```

## 构建和测试

```bash
mvn -pl :surprising-gateway-provider -am test
mvn -pl :surprising-gateway-provider -am spring-boot:run
```
