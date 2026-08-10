# Code quality review — JVM / lock-free trading-system understanding report

## Verdict

- codeQualityStatus: CLEAR
- recommendation: APPROVE
- blockers: none

## Scope and evidence checked

The reviewed artifact is the supplied understanding-report notepad at
`/var/folders/hx/xjj38_ln7kq9t86kzh9jz_9h0000gn/T/ulw-20260803-234137.XXXXXX.md.nu9uuaXs4O`.
This review was read-only: no services or tests were run and no production file was edited.

## Findings

### CRITICAL

None.

### HIGH

None.

### MEDIUM

None.

### LOW

None.

## Criterion review

1. The report separates implementation evidence from design intent. It identifies implemented `ProductLine` mappings (`surprising-product-api/src/main/java/com/surprising/product/api/ProductLine.java:6-70`), WAL/replay boundaries (`surprising-event-store/.../UserPartitionWal.java:70-228`), and database/JDBC persistence, while qualifying migration-plan and architecture-document statements as targets or remaining work (`docs/linear-perpetual-jvm-migration-plan.md:59-69,165-182`; `docs/database.md:246-252,388-389`).
2. It covers all four requested product lines: spot, perpetual, delivery, and options. The mapping and their distinct settlement/risk paths are concretely supported by `ProductLine.java:6-70` and `docs/product-line-architecture.md:12-17,57-84,144-157`.
3. It does not claim database-free or lock-free completion. `MatchingOrderRepository` still injects `JdbcTemplate` (`surprising-trading/.../MatchingOrderRepository.java:20-31`), and the risk projection uses `ReentrantLock` (`surprising-margin-ops/.../RiskLocalProjectionStore.java:48-86`); the report calls both boundaries out.
4. Funds and recovery boundaries use concrete paths: account reducer invariants (`surprising-account/.../AccountUserStateReducer.java:372-376,1284-1295,1557-1567`), WAL sequencing/idempotency/replay (`UserPartitionWal.java:70-228`), persistence/single-writer gates (`scripts/check-persistence-boundaries.sh:12-59`, `scripts/check-account-single-writer.sh:12-38`), funds reconciliation (`scripts/product-line-funds-reconcile.sh:31-195`), and the explicitly opt-in restart recovery scenario (`scripts/product-line-api-flow-smoke.sh:81-82,152-172`).
5. The report records the dirty worktree as pre-existing and declares zero worktree edits, no services, and no wallet execution. Current `git status --short` confirms the worktree is dirty; this review found no report claim that absorbs those changes into the read-only task.

## Required skill-perspective check

Ran. The `remove-ai-slops` and `programming` perspectives were loaded before judging test relevance and maintainability. There is no report-authored production or test diff to assess; the report does not introduce deletion-only, tautological, implementation-mirroring, or brittle prompt tests, nor needless production parsing/normalization, abstraction, or untyped escape hatches. Neither skill perspective is violated by the reviewed report.
