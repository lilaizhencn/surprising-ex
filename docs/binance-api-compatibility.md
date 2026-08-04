# Binance 兼容 API

网关提供 `/api/v3` 与 `/sapi/v1` 入口，业务请求可以使用登录 Bearer Token，也可以使用交易所 API Key。

## API Key

- `GET /api/v1/security/api-keys`：查看当前用户的 Key 元数据。
- `POST /api/v1/security/api-keys`：创建 Key。请求需要 `label`、权限列表 `READ`/`TRADE`/`WITHDRAW`；Secret 只在创建响应中返回一次。
- `DELETE /api/v1/security/api-keys`：撤销 Key。
- 创建和撤销都走 `SECURITY_SETTINGS` 场景验证。
- API 请求使用 `X-MBX-APIKEY`，签名参数遵循 `timestamp`、可选 `recvWindow`、`signature`，签名内容是去掉 `signature` 后的原始查询字符串，算法为 HMAC-SHA256 十六进制小写。

## 已接入入口

`/api/v3/ping`、`/api/v3/time`、`/api/v3/exchangeInfo`、`/api/v3/depth`、`/api/v3/account`、`/api/v3/order`、`/api/v3/openOrders`、`/sapi/v1/asset/transfer`、`/sapi/v1/capital/deposit/address`、`/sapi/v1/capital/deposit/hisrec`、`/sapi/v1/capital/withdraw/apply`、`/sapi/v1/capital/withdraw/history`。

下单和转账都使用服务端最小单位命令；提现要求 API Key 具有 `WITHDRAW` 权限、账户 KYC 已验证，并且为对应网络配置托管钱包资金源地址。资产精度和交易对别名必须配置在 `GATEWAY_CUSTODY_WALLET_ASSET_SCALES`、`GATEWAY_BINANCE_SYMBOL_SCALES` 与 `GATEWAY_BINANCE_SYMBOL_ALIASES` 中，未配置时拒绝请求。

历史成交、完整历史订单和行情 ticker 仍需补齐对应后端查询投影后再开放，当前接口会明确返回兼容错误，不会伪造空数据。
