# surprising-edge

Combined frontend edge deployment for development and small installations.

- REST gateway: `/api/v1/gateway/**` and `/api/v1/admin/**`
- WebSocket fanout: `/ws/v1`
- Default port: `9094`

The module does not replace `surprising-gateway-provider` or `surprising-websocket-provider`. For production deployments with many long-lived WebSocket connections, keep REST gateway and WebSocket as separate providers so WebSocket fanout can scale independently.

Run locally:

```bash
mvn -pl :surprising-edge-provider -am spring-boot:run
```
