# Code quality review — production test plan

## Verdict

- `codeQualityStatus`: **BLOCK**
- `recommendation`: **REQUEST_CHANGES**
- Skill-perspective check: **ran**. I loaded and applied `omo:remove-ai-slops` and `omo:programming` before judging test relevance and maintainability. The diff violates both perspectives: key production gates are brittle text/format parsers and new shell modules exceed the 250 pure-LOC ceiling without a stated exception or split. More importantly, several gates assert command completion instead of the business/security invariant they claim to prove.

## CRITICAL

None.

## HIGH

1. **Performance success criteria are not actually gated: observed throughput, GC pauses and OOM are never fail conditions.** `STRESS_TARGET_TPS` is passed to the load generator and written to the manifest, but `gate_output` parses throughput and then never calls `assert_metric_at_least` for any parsed TPS (`scripts/production-performance-gate.sh:137-144`, `scripts/production-performance-gate.sh:214-245`). The resource monitor records GC statistics, but the gate only checks process CPU and (optionally) whether one Tomcat sample exists (`scripts/production-resource-monitor.sh:49-54`, `scripts/production-resource-monitor.sh:69-76`, `scripts/production-performance-gate.sh:233-244`). There is no OOM/`ExitOnOutOfMemoryError`, Full-GC, GC-pause, heap, or resource-sample completeness gate. A run below target TPS or with long GC/OOM evidence can therefore print `PERFORMANCE_GATE PASS` at `scripts/production-performance-gate.sh:287`. **Hits success standard 3.**

2. **The security suite can report PASS after a Nuclei scan finds high/critical findings.** `run_nuclei` records PASS solely when the Nuclei process exits zero; it neither parses `nuclei.jsonl` nor rejects any finding (`scripts/run-security-test-suite.sh:153-160`). Nuclei normally uses zero exit status for a completed scan even when templates match. This contradicts the documented zero high/critical-vulnerability gate (`docs/production-security-test-plan.md:164-166`) and produces a false security conclusion. **Hits success standards 1 and 4.**

3. **Kafka “security” PASS establishes only that the current credentials can list metadata, not ACL, unauthorized access, key validation, or product-line isolation.** The implementation executes `--list` for topics and consumer groups and marks both successful commands PASS (`scripts/run-security-test-suite.sh:191-201`). It performs none of the rejection, key, producer/consumer, or cross-product-line assertions required by the plan (`docs/production-security-test-plan.md:96-101`). Thus a deployed suite can print `SECURITY SUITE PASS` (`scripts/run-security-test-suite.sh:267-271`) while the claimed Kafka security requirements are untested. **Hits success standards 1 and 4.**

4. **The concurrency helper has neither a valid idempotency oracle nor a cancel-race oracle.** It requires exactly one 2xx response for duplicated idempotent requests (`scripts/security-concurrency-race.sh:102-107`), even though a correct idempotent API may return the same successful result to every duplicate; this can falsely fail a correct implementation. Conversely, cancel-race accepts every HTTP outcome, emits only counts and a prose reminder, then exits zero (`scripts/security-concurrency-race.sh:109-118`). `run-security-test-suite.sh` treats that zero exit as concurrency PASS (`scripts/run-security-test-suite.sh:215-224`); a funds reconciliation, when separately enabled, does not verify the required single terminal transition/order fact. **Hits success standards 1 and 4.**

5. **Recovery matrix PASS is only the smoke script’s exit code; it does not verify the documented recovery gate.** The matrix records PASS immediately after `product-line-api-flow-smoke.sh` exits zero (`scripts/run-product-line-recovery-matrix.sh:58-72`). It does not record or check recovery duration, RPO, outbox status, Kafka final lag, duplicate facts, consumer rebalance, WAL replay, or fault-state equivalence, despite the required conditions in `docs/production-performance-test-plan.md:229-242`. The underlying restart is injected at one point in the normal order flow (`scripts/product-line-api-flow-smoke.sh:4584-4590`, `scripts/product-line-api-flow-smoke.sh:4860-4868`), not at the failure windows the matrix claims to cover. **Hits success standards 1 and 5.**

6. **JFR paths containing whitespace are broken by unquoted JVM-option expansion in both provider launchers.** The profile builds one option string containing `filename=${TEST_JFR_DIR}/…` (`scripts/test-environment-profile.sh:216-225`) and both launchers expand it unquoted (`scripts/product-line-api-flow-smoke.sh:1846`, `scripts/product-line-api-flow-smoke.sh:1883-1890`; `scripts/start-product-line-providers.sh:421-432`). Static invocation with `TEST_JFR_DIR='/tmp/a b'` yielded `filename=/tmp/a b/matching.jfr`, which becomes separate Java arguments rather than one JFR option. This invalidates JFR evidence or can prevent provider startup. **Hits success standard 5.**

## MEDIUM

1. **The resource profile is advisory rather than a total-resource limit for the standalone provider launcher.** `test-environment-profile.sh` defines `TEST_MAX_PROVIDER_PROCESSES` and service heaps (`scripts/test-environment-profile.sh:64-82`, `scripts/test-environment-profile.sh:168-225`), but `start-product-line-providers.sh` never enforces a process count or aggregate heap budget (`scripts/start-product-line-providers.sh:441-459`). `SERVICES` and all profile ceilings can be overridden without validation; a static override demonstration retained `TEST_JVM_HEAP_MB=9999`, `TEST_MAX_PROVIDER_PROCESSES=99`, and `SERVICES=edge`. The API-flow script has a provider-count guard, but that does not protect the documented standalone entry point. **Hits success standard 2.**

2. **The new gate/profile scripts are oversized, multi-responsibility shell modules.** `production-performance-gate.sh` has 262 pure lines and combines argument validation, report parsing in embedded Python, process lifecycle, workload orchestration, and policy (`scripts/production-performance-gate.sh:40-287`); `test-environment-profile.sh` has 295 pure lines and combines host detection, policy, JVM building, service selection, and evidence emission (`scripts/test-environment-profile.sh:4-308`). This violates the consulted `programming` and `remove-ai-slops` size/scope perspective and makes the fragile parser/gate logic harder to test independently. **Does not independently hit a success standard; it materially raises regression risk for standards 2 and 3.**

3. **Dry-run output uses the word PASS for plan generation.** The gate prints `DRY_RUN PASS` without executing any business flow (`scripts/production-performance-gate.sh:250-256`), and the security/concurrency scripts do the same (`scripts/run-security-test-suite.sh:101-103`, `scripts/security-concurrency-race.sh:54-66`). Existing manifests label their matrix rows `DRY_RUN`, so the available evidence is not itself falsely presented as traffic evidence; nevertheless, a standalone command result is easy to misquote as a test pass. **Hits success standard 6’s constraint against treating dry-run as real proof.**

## LOW

1. **Tomcat matrix does not prove that the configured service is Tomcat or that the requested runtime values were bound.** It forwards the four settings (`scripts/run-container-threadpool-matrix.sh:64-69`), while the gate requires only one Prometheus metric sample when requested (`scripts/production-performance-gate.sh:240-244`). No configuration snapshot or metric-vs-requested-value comparison exists. **Partially hits success standard 5.**

2. **The documents are strong as execution plans, not execution evidence.** They cover product-line separation, dynamic profiles, GC/Tomcat/thread pools, funds, recovery, external testing and concurrency (`docs/production-performance-test-plan.md:5-17`, `docs/production-performance-test-plan.md:189-242`, `docs/production-security-test-plan.md:46-56`, `docs/production-security-test-plan.md:103-130`). No real traffic, Kafka/PostgreSQL connection, recovery, external scan, or funds reconciliation was run in this review, and the referenced artifacts explicitly contain only dry-run/static results. **Hits all runtime success standards as unexecuted follow-up, not as a defect in the static plan.**

## Evidence inspected

- `bash -n` passed for all eleven scoped shell scripts; `git diff --check` passed. This establishes syntax/whitespace only.
- `.omo/evidence/production-test-plan/final-static-dry-run-20260804/validation-and-dry-run.log` records static checks and dry-run matrix creation only.
- `.omo/evidence/production-test-plan/final-security-gate-20260804/unauthorized.log` and `missing-allowlist.log` correctly demonstrate rejection before active execution. They do not validate an authorized security run.
- `.omo/ultrawork-notepad-20260804.md` was treated as untrusted context and corroborated only where the current scripts/evidence above support it.

## Required blockers before approval

1. Make performance PASS enforce measured TPS, GC/OOM/heap/resource completeness, and the documented p99/lag/outbox/funds requirements from machine-readable evidence.
2. Make security PASS parse scanner findings and implement actual Kafka ACL/key/isolation assertions.
3. Replace the concurrency HTTP-status heuristic with order/ledger/freeze/terminal-state assertions, and make cancel-race fail until those assertions plus reconciliation pass.
4. Make recovery PASS verify timing, lag/outbox, replay/idempotency and funds/state equivalence for each advertised case.
5. Pass JVM options as arrays (including JFR filenames) so paths cannot be split; then add static/contract coverage for spaces and explicit profile overrides.

## Residual risk after fixes

Even after the static blockers are fixed, production approval remains conditional on separately authorized, one-product-line-at-a-time real API flow, Kafka/PostgreSQL-backed reconciliation, recovery, and external security evidence. None occurred in this review.

---

## Delta re-review — 2026-08-05

Skill-perspective check reran: `omo:remove-ai-slops` and `omo:programming` were consulted. The original performance, Nuclei, Kafka, concurrency, recovery, JFR, resource-budget, and dry-run blockers have static implementation coverage in the reviewed delta. The referenced evidence corroborates syntax, whitespace-safe JFR array construction, unsafe override rejection, plan-only labelling, and unauthorized security rejection; it is not counted as runtime proof.

### Remaining HIGH blocker

- **Tomcat matrix defaults to bypassing its own binding gate.** `THREADPOOL_REQUIRE_TOMCAT` defaults to `false` (`scripts/run-container-threadpool-matrix.sh:11`) and that value is passed unchanged to every performance-gate invocation (`scripts/run-container-threadpool-matrix.sh:65-71`). The result-file/bound-value checks run only when the flag is `true` (`scripts/production-performance-gate.sh:305-314`). Therefore `MATRIX_EXECUTE=true ./scripts/run-container-threadpool-matrix.sh` can report PASS without `THREADPOOL_CONFIG_RESULT_FILE` or any proof that Tomcat is in use and bound the four requested settings. This still hits success standard 5 (Tomcat/线程池参数传递与矩阵证据), so approval is not unconditional.
