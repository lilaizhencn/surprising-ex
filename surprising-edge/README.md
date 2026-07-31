# surprising-edge

面向前端的接入层模块。

- `surprising-gateway`：独立 REST gateway provider。
- `surprising-websocket`：独立 WebSocket fanout API/provider。
- `surprising-edge-provider`：开发环境和小规模部署用的 REST gateway + WebSocket fanout 合并 provider。

`surprising-edge-provider` 默认端口是 `9094`，REST gateway 路径是 `/api/v1/gateway/**` 和 `/api/v1/admin/**`，WebSocket 路径是 `/ws/v1`。

生产环境如果 WebSocket 长连接很多，继续使用本模块下的 `surprising-gateway-provider` 和 `surprising-websocket-provider` 独立部署，让 WebSocket fanout 单独扩容。

本地启动：

```bash
mvn -pl :surprising-edge-provider -am spring-boot:run
```
