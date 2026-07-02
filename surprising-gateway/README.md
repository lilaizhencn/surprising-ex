# surprising-gateway

[简体中文](README_CN.md)

Stateless public REST gateway for frontend and BFF traffic.

The mainstream exchange layout is a unified public gateway or BFF at the edge, while each business module still owns its internal API and configuration. This module follows that pattern: frontend clients call one gateway prefix, and the gateway proxies only allowlisted routes to internal services.

## Module

- `surprising-gateway-provider`: Spring Boot allowlisted proxy.

## Endpoint

- HTTP port: `9094`
- Gateway prefix: `/api/v1/gateway/{service}`

Examples:

```bash
curl 'http://localhost:9094/api/v1/gateway/candlestick/candles/latest?symbol=BTC-USDT&period=1m'
curl 'http://localhost:9094/api/v1/gateway/trading-market/orderbook?symbol=BTC-USDT&depth=50'
curl 'http://localhost:9094/api/v1/gateway/trading-trigger/open?userId=1001&symbol=BTC-USDT' -H 'X-User-Id: 1001'
curl 'http://localhost:9094/api/v1/gateway/account/1001/positions' -H 'X-User-Id: 1001'
curl 'http://localhost:9094/api/v1/gateway/market-maker/strategies' -H 'X-User-Id: ops'
```

## Routes

| Gateway service | Internal target | Private |
| --- | --- | --- |
| `instrument` | `http://localhost:9080/api/v1/instruments` | no |
| `candlestick` | `http://localhost:9081/api/v1/candlestick` | no |
| `price-index` | `http://localhost:9082/api/v1/price/index` | no |
| `price-fx` | `http://localhost:9082/api/v1/price/fx` | no |
| `price-mark` | `http://localhost:9083/api/v1/price/mark` | no |
| `trading` | `http://localhost:9084/api/v1/trading/orders` | yes |
| `trading-market` | `http://localhost:9085/api/v1/trading/market` | no |
| `trading-trigger` | `http://localhost:9095/api/v1/trading/trigger-orders` | yes |
| `account` | `http://localhost:9086/api/v1/accounts` | yes |
| `risk` | `http://localhost:9087/api/v1/risk` | yes |
| `liquidation` | `http://localhost:9088/api/v1/liquidations` | yes |
| `funding` | `http://localhost:9089/api/v1/funding` | no |
| `insurance` | `http://localhost:9090/api/v1/insurance` | yes |
| `adl` | `http://localhost:9091/api/v1/adl` | yes |
| `market-maker` | `http://localhost:9096/api/v1/market-maker` | yes |

The gateway rejects unknown service names. It does not concatenate arbitrary backend hostnames or table names from user input.

## Security Model

The current implementation requires either `X-User-Id` or `Authorization` on private routes. In production, put authentication before this gateway and inject a trusted `X-User-Id` only after token/session validation.
`X-Trace-Id` is accepted on every route, normalized, returned to the caller, and forwarded to backend providers. It is for observability only and must never be used as an authentication or authorization input.

Do not expose internal provider ports directly to the internet. Public clients should use:

- REST: `surprising-gateway`
- Realtime: `surprising-websocket` or an ingress route to `/ws/v1`

## Horizontal Scaling

- The gateway is stateless and can run behind any L4/L7 load balancer.
- Deploy at least two gateway instances.
- Use Kubernetes Services, service discovery, or config management to set backend `base-url` values per environment.
- Gateway nodes do not need sticky sessions for REST.
- Keep timeouts and retry policy conservative for order and account routes; duplicate POST retries should be driven by client idempotency keys, not blind gateway retries.
- The built-in HTTP client has explicit connect/read timeouts so a down backend cannot hang gateway worker threads indefinitely.

## Configuration

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

## Build And Test

```bash
mvn -pl :surprising-gateway-provider -am test
mvn -pl :surprising-gateway-provider -am spring-boot:run
```
