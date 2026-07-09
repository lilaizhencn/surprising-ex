# surprising-edge

Frontend access layer modules.

- `surprising-gateway`: standalone REST gateway provider.
- `surprising-websocket`: standalone WebSocket fanout API/provider.
- `surprising-edge-provider`: combined REST gateway and WebSocket fanout deployment for development and small installations.

`surprising-edge-provider` exposes REST gateway routes on `/api/v1/gateway/**` and `/api/v1/admin/**`, WebSocket fanout on `/ws/v1`, and uses port `9094` by default.

For production deployments with many long-lived WebSocket connections, keep `surprising-gateway-provider` and `surprising-websocket-provider` as separate deployables under this module so WebSocket fanout can scale independently.

Run locally:

```bash
mvn -pl :surprising-edge-provider -am spring-boot:run
```
