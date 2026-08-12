# Gate review: commit 2b6c1ebd2712565133fa48bd8983e7a946c4f745

## recommendation

REJECT

## originalIntent

Review the exact commit for truthful handling of absent backend market data, strict DEV/environment gating of mock fallback, isolation of product/account/instrument state, and protection against stale asynchronous work overwriting newer market state.

## desiredOutcome

- Missing backend ticker values are never presented as genuine price, 24-hour change, or volume.
- Mock values are reachable only under an explicit development-only environment gate and are visibly degraded rather than live.
- Identical symbols across products cannot cross product, account, instrument, request, or presentation boundaries.
- Older asynchronous responses cannot replace newer market state.

## userOutcomeReview

The commit improves production presentation of absent ticker fields: `tickerReady` is false unless all three backend ticker fields are present, and the market UI renders em dashes and excludes such rows from rankings/aggregates. The fallback switch is explicitly gated by both Vite DEV mode and `VITE_ENABLE_MOCK_FALLBACK=true`. Product filtering and favorite identity are product-qualified, and account requests remain keyed by each product's account type and product line.

However, the artifact fails the explicit race criterion. `marketsRequestRef` orders only concurrent calls to `refreshMarketsFromGateway`; successful list responses still perform whole-array replacement and can erase a newer WebSocket patch, instrument-config merge, or valuation update that completed after the list request started. In addition, under enabled DEV mock mode, a successful real instrument-list response with missing ticker fields is silently populated from mock values, marked `tickerReady`, and reported as `ready`/`Live data`, not degraded.

## blockers

1. **violatedCriterion:** `RACE-1 — race conditions cannot overwrite newer market state`
   - **severity:** HIGH
   - **observation:** A market-list request that began before a realtime/config/valuation update can finish afterward and replace the entire array with its older snapshot. The request ID only detects a newer market-list request; none of the other market writers increments or participates in that generation.
   - **evidencePointer:** `src/App.tsx:226-244` versus functional newer-state writers at `src/App.tsx:398`, `src/App.tsx:503-515`, and `src/App.tsx:647-653`.

2. **violatedCriterion:** `TRUTH-1 — missing backend market data cannot be mistaken for real prices/volume`
   - **severity:** MEDIUM (DEV-only, explicit opt-in)
   - **observation:** With mock fallback enabled, `loadMarkets(false)` still lets `toMarket` select fallback by symbol; fallback price/change/volume take precedence over backend values, and `tickerReady` is forced true. Since the list call succeeds, App labels state `ready`, and the markets page can label it `Live data`.
   - **evidencePointer:** `src/config.ts:12`; `src/api/surprising.ts:1185-1201,1257-1267`; `src/App.tsx:231-235`; `src/components/MarketsPage.tsx:90`.

## criterion results

- `TRUTH-1`: FAIL in explicit DEV mock mode; PASS in production/non-mock mode for absent ticker fields.
- `GATE-1 — fallback is DEV/env gated`: PASS. `src/config.ts:12` requires both `import.meta.env.DEV` and exact string `true`.
- `BOUNDARY-1 — product/account/instrument boundaries remain isolated`: PASS for changed behavior. Product-qualified favorite keys are at `src/marketPresentation.ts:14-16`; market filtering is at `src/components/MarketsPage.tsx:30-39`; market requests derive product line from selected product mode at `src/App.tsx:742-754`; product account loading remains account-type/product-line qualified. No changed account mutation path was found.
- `RACE-1`: FAIL as described above.

## direct remove-ai-slops / programming pass

- The two added tests are helper-level tests. They are not tautological, but they provide false confidence for the requested outcome because they do not execute `toMarket`, `loadMarkets`, UI state labeling, or async interleavings. There is no regression that would fail for either blocker.
- `marketPresentation.ts` is a small, focused presentation-identity module; no unnecessary parsing/normalization, oversized module, dead code, deletion-only test, or removal-verification test was introduced there.
- The changed mapping has a data-provenance flaw in DEV mode and the state writer has a last-writer-wins race across independent async sources. These are criterion failures, not architecture-style notes.
- No supplied code-review report exists to confirm an independent skill-perspective/overfit pass. Direct inspection was used; absence of that report is not itself a blocker.

## checked artifact paths

- Exact Git object and diff: `2b6c1ebd2712565133fa48bd8983e7a946c4f745^..2b6c1ebd2712565133fa48bd8983e7a946c4f745`; checkout HEAD matched exactly.
- `DESIGN.md`
- `src/App.tsx`
- `src/api/surprising.ts`
- `src/components/MarketsPage.tsx`
- `src/config.ts`
- `src/marketPresentation.ts`
- `src/styles.css`
- `src/types.ts`
- `test/market-presentation.test.ts`
- `package.json`
- ULW status: no plan (`ULW_LOOP_PLAN_MISSING`), so this report uses `.omo/evidence/` fallback.

## reproduced evidence

- `npm test`: PASS, 10/10 tests.
- `npm run build`: NOT REPRODUCIBLE; `tsc: command not found` because dependencies/tooling are not installed.
- Working tree was clean before creation of this required review artifact.

## exact evidence gaps

- No test for absent/partial ticker fields under production mode.
- No test for absent/partial ticker fields under enabled DEV mock mode, including displayed state.
- No test interleaving an in-flight market-list refresh with a newer WebSocket, instrument-config, or valuation patch.
- No build artifact or installed dependency tree was available, so TypeScript compilation and Vite bundling could not be reproduced.
- No executor evidence, code-review report, manual-QA matrix, or notepad path was supplied or found under `.omo`.
