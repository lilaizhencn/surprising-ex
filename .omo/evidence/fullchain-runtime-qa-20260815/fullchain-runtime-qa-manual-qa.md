# manualQa — full transaction-chain runtime audit

Attempt directory: `.omo/evidence/fullchain-runtime-qa-20260815` (no ulw-loop plan was present; caller evidence directory used).

## surfaceEvidence

| scenario id | criterion reference | surface | exact invocation | verdict | artifactRefs |
|---|---|---|---|---|---|
| ENV-CORE-STATUS | startup/runtime availability | Docker/Core compose | `PRODUCT_LINE=SPOT scripts/aeron-core-local.sh status` and `docker compose ls --all` | FAIL — no Aeron Core compose project or node is running | `A-ENV` |
| CORE-PROBE | Aeron client command/query | `ClusterProbeMain` through compose tool wrapper | `PRODUCT_LINE=SPOT PROBE_MODE=query PROBE_SOURCE_ID=910001 scripts/aeron-core-tool.sh probe` | FAIL — real client timed out; node0/node1/node2 unresolved | `A-ENV` |
| CORE-UNIT | Core reducer/risk and owner-state logic | JDK 25 Maven reactor | `mvn -q -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreRiskStateTest,TradingCoreReducerTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS — 31/31, zero failures/errors/skips | `A-TEST` |
| AERON-CLIENT-UNIT | Aeron client pool backpressure/close/config | JDK 25 Maven reactor | `mvn -q -pl surprising-aeron-core/surprising-aeron-client -am -Dtest=AeronClientPoolTest,SurprisingAeronClientTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS — existing AeronClientPoolTest 5/5; requested absent class did not run | `A-TEST` |
| EXPORT-UNIT | exporter batch/ack and bridge boundary | JDK 25 Maven reactor | `mvn -q -pl surprising-aeron-core/surprising-aeron-exporter -am -Dtest=ReliableCoreExporterTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS — 3/3, zero failures/errors/skips | `A-TEST` |
| TRIGGER-UNIT | trigger scan/pagination and liquidation-facing gateway | JDK 25 Maven reactor | `mvn -q -pl surprising-trading/surprising-trigger-provider -am -Dtest=TriggerOrderServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS — 37/37, zero failures/errors/skips | `A-TEST` |
| MARK-UNIT | mark-price calculation/encoding/service/audit | JDK 25 Maven reactor | `mvn -q -pl surprising-price/surprising-price-provider -am -Dtest=MarkPriceCalculatorTest,MarkPriceEncodingServiceTest,MarkPriceServiceTest,MarkPriceAuditConsumerTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS — 14/14, zero failures/errors/skips | `A-TEST` |
| WS-UNIT | Gateway WebSocket handler/fanout/registry/config | JDK 25 Maven reactor | `mvn -q -pl surprising-gateway -am -Dtest=ClientWebSocketHandlerTest,CoreEventFanoutConsumerTest,SubscriptionRegistryTest,WebSocketServerConfigurationTest,WebSocketApplicationYamlTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS — 12/12, zero failures/errors/skips | `A-TEST` |
| PROVIDER-HTTP | HTTP API and WebSocket runtime | configured ports 9080–9096, Gateway 9094 | `curl -i --max-time 1 http://127.0.0.1:<configured-port>/actuator/health`; WebSocket Upgrade to `http://127.0.0.1:9094/ws/v1` | FAIL — every provider port refused connection; Gateway/WebSocket absent | `A-ENV` |
| HIST-CAPACITY | Core throughput/funds invariant | historical local capacity manifests | read `.local-logs/capacity-*/manifest.env` and `capacity.log` | PASS — six 20-second product lines, zero failures, zero fundsDiff/bookLevels; historical, not fresh | `A-HIST` |
| HIST-RECOVERY | leader rejoin/cold restart/export failure | historical local recovery manifests | read `.local-logs/recovery-*/manifest.env`, roles, exporter-failure and post-restart status | PASS — six complete product lines preserve stateHash, funds difference 0, export failure pass, role evidence pass; historical, not fresh | `A-HIST` |

## adversarialCases

| scenario id | criterion reference | adversarial class | expected behavior | verdict | artifactRefs |
|---|---|---|---|---|---|
| ADV-AERON-DOWN | CORE-PROBE | Aeron cluster unavailable / DNS failure | Client should fail with an explicit bounded timeout and endpoint causes, not report a successful default response. | PASS — timeout plus UnknownHostException for all three endpoints observed | `A-ENV` |
| ADV-KAFKA-OOM | EXPORT-UNIT, PROVIDER-HTTP | Kafka broker unavailable after OOM kill | Live exporter/API fanout should be exercised and report durable backlog/failure semantics. | FAIL — Kafka is `Exited (137)` with `oom=true`; live exporter/WebSocket prerequisite missing | `A-ENV`, `A-HIST` |
| ADV-MARK-BACKPRESSURE | MARK-UNIT | Aeron slow/unavailable while mark events continue | Publisher must remain bounded and preserve/latest-sequence delivery under pressure. | FAIL — no outage/backpressure runtime test; source shows unbounded queue and three tight retries | `A-SRC` |
| ADV-CORE-BATCH-FAIRNESS | CORE-UNIT | large exchange/liquidation/settlement batch | Unrelated commands and mark/risk processing should remain within a bounded latency budget. | FAIL — no live Core and no adapter/load fairness test; source uses all-futures join on owner path | `A-SRC`, `A-ENV` |
| ADV-TRIGGER-PAGE | TRIGGER-UNIT | eligible trigger beyond first query page | Trigger scan should advance cursor and execute eligible orders without starvation. | PASS — TriggerOrderServiceTest 37/37; live Core trigger path remains unrun | `A-TEST`, `A-ENV` |
| ADV-WS-OVERFLOW | WS-UNIT, PROVIDER-HTTP | slow WebSocket consumer / outbound queue pressure | Backpressure must be observable as bounded queue/rejection/close, without hanging the fanout thread. | FAIL — unit tests pass but no live Gateway/Kafka connection or slow-consumer scenario was possible | `A-TEST`, `A-ENV`, `A-SRC` |
| ADV-RECOVERY-FUNDS | HIST-RECOVERY | leader stop/rejoin and cold restart across product lines | State hash and funds must remain equal; exporter failure must leave pending events rather than silently lose them. | PASS — six historical manifests meet all invariants; not a current run | `A-HIST` |
| ADV-PRODUCT-ISOLATION | HIST-CAPACITY, HIST-RECOVERY | product-line cross-contamination | Each product line should complete independently with zero funds difference and isolated state hash. | PASS — six historical product-line runs independently report zero fundsDiff and distinct/consistent hashes | `A-HIST` |

## artifactRefs

| id | kind | description | path |
|---|---|---|---|
| `A-ENV` | runtime transcript | Docker state, Core probe failure, configured HTTP health refusals, WebSocket handshake refusal, cleanup | `.omo/evidence/fullchain-runtime-qa-20260815/runtime-availability.txt` |
| `A-TEST` | Maven transcript/report summary | JDK 25 targeted client/Core/exporter/trigger/mark/WebSocket tests; 102 verdict-bearing tests pass | `.omo/evidence/fullchain-runtime-qa-20260815/targeted-tests.txt` |
| `A-HIST` | historical runtime evidence | Six product-line capacity manifests and six complete recovery manifests independently read and cross-checked | `.omo/evidence/fullchain-runtime-qa-20260815/previous-artifacts-verification.txt` |
| `A-SRC` | source audit | CodeGraph/source findings for mark queue, exchange batch joins, owner-thread path, and WebSocket backpressure gaps | `.omo/evidence/fullchain-runtime-qa-20260815/source-audit.txt` |

## ranked findings and coverage boundary

1. High: current live transaction chain is unavailable. Kafka is OOM-killed (`exit=137`, `oom=true`), no Aeron Core cluster is running, and all configured provider/Gateway ports refuse connections. No current HTTP, Kafka, exporter, market-maker, wallet, or WebSocket verdict can be promoted.
2. High, source-level/unverified: mark-price Core publication uses an unbounded FIFO with one synchronous Aeron drain and three tight retries. A sustained Aeron outage can accumulate stale events and sequence gaps; an outage/backpressure test is missing.
3. High, source-level/unverified: exchange-core batch place/cancel waits on all futures from the Core owner-thread command path. Large liquidation/settlement fairness and latency-tail behavior are not covered by a real load test.
4. Medium, source-level/unverified: WebSocket write watchdog/backpressure behavior lacks a live slow-consumer test; unit/config tests do not establish runtime queue/close behavior.
5. Confirmed bounded evidence: 102 isolated JDK-25 tests pass; historical six-line capacity/recovery artifacts preserve zero failures/funds difference and state hashes, but those artifacts are not a current live chain.
