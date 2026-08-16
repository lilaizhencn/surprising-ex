# Task 5 gateway batch evidence

Date: 2026-08-17 (Asia/Shanghai)
Branch: `codex/w3w5-t05-gateway-batch`
Base: `f256a35b5cbebb70d7b242a992d3ca4efedc08a2`
JDK: IBM Semeru OpenJ9 25.0.2

## Failing-first

The exact acceptance command was run after adding the Task 5 tests and before
the implementation. It failed at test compilation with exit 1 and 39 missing
symbol/method errors for `OrderCommandReceipt`, typed gateway forwarding,
native batch methods, receipts, and command-result plumbing.

## Green counts

| Scope | Command/method | Result |
| --- | --- | --- |
| Acceptance | `mvn -pl :surprising-order-provider -am -Dtest=OrderControllerTest,OrderAeronGatewayTest,AeronOrderCommandServiceTest,OrderBatchServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS, 19 tests, 0 failures/errors |
| QA | `OrderBatchServiceTest#usesOneRoundTripAndReplaysOriginalAggregate` | PASS, 1 test |
| QA | `OrderControllerTest#mapsConflictBackpressureAndUnknownSeparately` | PASS, 1 test |
| Affected reactor | `mvn -pl :surprising-order-provider -am test` | PASS, 257 tests across the reactor, 0 failures/errors |
| Static | `git diff --check` | PASS |

## HTTP/status matrix

| Receipt code/outcome | HTTP | Evidence |
| --- | ---: | --- |
| terminal business, including `NONE` | 200 | controller receipt mapping test |
| `IDEMPOTENCY_CONFLICT` | 409 | `mapsConflictBackpressureAndUnknownSeparately`; terminal receipt preserves command ID/URL |
| `CLIENT_BACKPRESSURED` | 429 | typed `NotAccepted` service test; no command-result URL claim |
| `NOT_CONNECTED`, `ADMIN_ACTION`, `CLOSED`, `MAX_POSITION_EXCEEDED`, `UNKNOWN` raw admission | 503 | parameterized controller test |
| `RESULT_UNKNOWN` after typed admitted outcome | 202 | controller and service tests; original command-result URL retained |
| known command result | 200 | command-result controller test |
| pending/unknown command result | 202 | command-result controller test |
| `RESULT_UNKNOWN_OUTSIDE_RETENTION` | 410 | command-result controller test |

## Batch/identity/sequence checks

- Place batch maximum: 20 items, one native `PLACE_ORDER_BATCH` gateway call per
  invocation; same batch key reproduces the stable outer command ID and the
  decoded aggregate in input order.
- Amend batch maximum: 20 items, one native `AMEND_ORDER_BATCH` gateway call.
- Cancel batch maximum: 50 items, one native `CANCEL_ORDER_BATCH` gateway call.
- Stable Task 2 order/replacement/command identities are generated before the
  typed gateway call; no random mutation identity or retry/fallback read is used.
- The replay test uses `appliedCommandCount=1` and
  `requiredExportSequence=9`, asserts the receipt contains 9, and asserts it is
  not 1. The production mapping reads only `CoreResponse.requiredExportSequence`
  and treats zero as unknown; it never substitutes the applied-command count.
- `GET /api/v1/trading/orders/commands/{commandId}` delegates through the
  gateway's reserved control command-result query. Ordinary order reads remain
  projection-repository reads.

## Scope checks

- Changed files are limited to the Task 5 trading API/order-provider surface and
  affected order-provider tests, plus this evidence report.
- No wallet module, `.factorypath`, `.idea`, `.local-logs`, or `data` artifact
  was changed or added.
- No runtime wallet service or end-to-end external environment was started;
  the required module/reactor tests and named QA methods are the executed scope.
