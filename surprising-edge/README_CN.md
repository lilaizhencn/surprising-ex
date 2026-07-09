# surprising-edge

面向前端接入层的合并部署模块，适合开发环境和小规模部署。

- REST gateway：`/api/v1/gateway/**` 和 `/api/v1/admin/**`
- WebSocket fanout：`/ws/v1`
- 默认端口：`9094`

该模块不替代 `surprising-gateway-provider` 或 `surprising-websocket-provider`。生产环境如果 WebSocket 长连接很多，建议继续拆分 REST gateway 和 WebSocket provider，让 WebSocket fanout 单独扩容。

本地启动：

```bash
mvn -pl :surprising-edge-provider -am spring-boot:run
```
