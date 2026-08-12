# Browser error audit

The following fresh `agent-browser` `errors` invocations returned empty output (0 bytes), which is the expected no-uncaught-page-error result:

- `http://127.0.0.1:5173/markets` at 1440×900: `markets-5173-1440.errors.txt`
- `http://127.0.0.1:5173/markets` at 375×812: `markets-5173-375.errors.txt`
- `http://127.0.0.1:5173/trade/spot` at 1440×900: `trade-spot-5173-1440.errors.txt`
- `http://127.0.0.1:5174/markets` at 1440×900: `markets-5174-1440.errors.txt`

The corresponding non-empty console captures are retained as `markets-5173-1440.console.txt`, `markets-5173-375.console.txt`, `trade-spot-5173-1440.console.txt`, and `markets-5174-1440.console.txt`.
