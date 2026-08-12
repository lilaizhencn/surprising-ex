# Browser error audit

Session: `agent-browser --session bb722fe5`

Fresh page-error invocations and results:

- `agent-browser --session bb722fe5 errors` after `http://127.0.0.1:5173/markets` at 1440x900: empty output.
- `agent-browser --session bb722fe5 errors` after `http://127.0.0.1:5173/markets` at 375x812: empty output.
- `agent-browser --session bb722fe5 errors` after `http://127.0.0.1:5174/markets` at 1440x900: empty output.
- `agent-browser --session bb722fe5 errors` after `http://127.0.0.1:5174/markets` at 375x812: empty output.
- `agent-browser --session bb722fe5 errors` after `http://127.0.0.1:5173/trade/spot` at 1440x900: empty output.
- `agent-browser --session bb722fe5 errors` after `http://127.0.0.1:5173/trade/spot` at 375x812: empty output.

The corresponding non-empty console captures contain Vite/React development messages only; no uncaught application error was observed.
