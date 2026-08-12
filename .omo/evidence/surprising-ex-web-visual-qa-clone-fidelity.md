# Clone / Design-System Fidelity Review — surprising-ex-web visual QA

**Verdict:** FAIL  
**Recommendation:** REQUEST_CHANGES  
**Review mode:** read-only, live app at `http://127.0.0.1:5173`  
**Revision reviewed:** `b37924f7e435aa20464a020d02b9976d567c0f87` plus the present uncommitted/untracked frontend work.

## Scope and success criteria

Reviewed the requested asset center (overview and spot account), light/dark stability, shared auth topbar, funding-ledger page, top search, trade ticket, and account bottom tabs. The five supplied `1440×900` screenshots are the visual baseline; fresh captures were taken from the live Vite server at the same size. The implementation must be live DOM, use reusable primitives and tokens, match the provided visual baseline, and preserve the requested states.

## Findings

### CRITICAL

None. The result is not a pasted image: browser accessibility snapshots expose real headings, buttons, text fields, selects, tabs, and routes. The components render live state and event handlers. No screenshot/background-image substitution was found.

### HIGH

1. **The new asset/ledger UI is not token-driven for spacing, typography, radii, shadows, or the light palette.** `DESIGN.md` defines the `--sx-*` token contract, including its spacing, radius, typography, and semantic-color scales, but the new rules create a parallel `--asset-*` palette and then rely on a dense set of one-off literal values. Examples include raw hex/RGBA values at [styles.css:3498](/Users/atomex/Desktop/surprising/surprising-ex-web/src/styles.css:3498)-[3531](/Users/atomex/Desktop/surprising/surprising-ex-web/src/styles.css:3531); individual geometry/type/elevation values such as `48px`, `72px`, `80px`, `24px`, `1320px`, `26px`, `30px`, `48px`, and `18px` at [styles.css:3551](/Users/atomex/Desktop/surprising/surprising-ex-web/src/styles.css:3551)-[3605](/Users/atomex/Desktop/surprising/surprising-ex-web/src/styles.css:3605); and further one-off table/card sizing at [styles.css:3613](/Users/atomex/Desktop/surprising/surprising-ex-web/src/styles.css:3613)-[3656](/Users/atomex/Desktop/surprising/surprising-ex-web/src/styles.css:3656). This violates the stated design-system criterion rather than extending it.

2. **Core visual primitives are still page-specific compositions, not reusable implementation primitives.** The new asset center constructs its heading, cards, product navigation, ledger preview, and action controls directly with unique CSS classes at [AssetCenter.tsx:107](/Users/atomex/Desktop/surprising/surprising-ex-web/src/components/AssetCenter.tsx:107)-[137](/Users/atomex/Desktop/surprising/surprising-ex-web/src/components/AssetCenter.tsx:137); the ledger duplicates the same bespoke page/card/button pattern at [FundingLedgerPage.tsx:53](/Users/atomex/Desktop/surprising/surprising-ex-web/src/components/FundingLedgerPage.tsx:53)-[62](/Users/atomex/Desktop/surprising/surprising-ex-web/src/components/FundingLedgerPage.tsx:62). `AssetIcon` and `SupportBubble` are genuinely reused ([AssetPrimitives.tsx:13](/Users/atomex/Desktop/surprising/surprising-ex-web/src/components/AssetPrimitives.tsx:13)-[19](/Users/atomex/Desktop/surprising/surprising-ex-web/src/components/AssetPrimitives.tsx:19)), but there is no reusable surface, field, action-button, or table primitive for the dominant UI anatomy. The code therefore does not meet the required reusable design-system standard.

### MEDIUM

1. **The live trade integration is unavailable, preventing verification of populated search results and a live-market ticket state.** The fresh trade capture displays `交易对服务暂不可用，请稍后重试` and no U-margined markets. The top search accepts `BTC`, the ticket renders, and bottom tabs switch panes, but no actual result row can be selected in this server state. Evidence: [/tmp/surprising-qa-trade-search-live.png](/tmp/surprising-qa-trade-search-live.png). Treat this as an environment/integration gap unless a supported live market response is supplied.

2. **The new bottom tabs lack selected-state semantics.** The container declares `role="tablist"`, but its child buttons do not declare `role="tab"`, `aria-selected`, or an `aria-controls` relationship at [App.tsx:2553](/Users/atomex/Desktop/surprising/surprising-ex-web/src/App.tsx:2553)-[2559](/Users/atomex/Desktop/surprising/surprising-ex-web/src/App.tsx:2559). Visually the active pane is clear and the click works; keyboard/screen-reader tab semantics are incomplete.

### LOW

None.

## Visual and interaction evidence

| Surface | Result | Evidence |
| --- | --- | --- |
| Asset overview, dark, 1440×900 | PASS visual baseline: 0 differing pixels; valid PNG dimensions and alpha. | Reference [/tmp/surprising-assets-dark.png](/tmp/surprising-assets-dark.png); fresh [/tmp/surprising-qa-assets-live.png](/tmp/surprising-qa-assets-live.png) |
| Asset overview, light, 1440×900 | PASS visual baseline: 6 differing pixels out of 1,296,000; no meaningful hotspot. | Reference [/tmp/surprising-assets-light.png](/tmp/surprising-assets-light.png); fresh [/tmp/surprising-qa-assets-light-reload.png](/tmp/surprising-qa-assets-light-reload.png) |
| Spot assets, 1440×900 | PASS visual baseline: 1,197 differing pixels (0.09%); differences are confined to the empty-state timing/antialiasing region. | Reference [/tmp/surprising-spot-assets.png](/tmp/surprising-spot-assets.png); fresh [/tmp/surprising-qa-spot-assets-live.png](/tmp/surprising-qa-spot-assets-live.png) |
| Auth shared topbar, 1440×900 | PASS visual baseline: 0 differing pixels. The live auth route exposes the shared product nav, search, theme/language, and account controls above the sign-in form. | Reference [/tmp/surprising-login.png](/tmp/surprising-login.png); fresh [/tmp/surprising-qa-login-live.png](/tmp/surprising-qa-login-live.png) |
| Ledger page | PASS structural visual check: real filter control, refresh button, responsive full-width table card, and a truthful signed-out empty state. | [/tmp/surprising-qa-ledger-live.png](/tmp/surprising-qa-ledger-live.png) |
| Search and desktop ticket | PARTIAL: the search field accepts `BTC`; ticket fields, order-side controls, and tabs render. The supplied baseline differs by 1,513 pixels (0.12%), limited to top-search focus/text regions. Live market results could not be exercised because the market service is unavailable. | Reference [/tmp/surprising-trade-search.png](/tmp/surprising-trade-search.png); fresh [/tmp/surprising-qa-trade-search-live.png](/tmp/surprising-qa-trade-search-live.png) |
| Bottom tabs | PASS functional visual check: clicking `持仓` changes the rendered content from `产品资产` to `持仓 / 风险`. | [/tmp/surprising-qa-trade-positions-tab.png](/tmp/surprising-qa-trade-positions-tab.png) |
| Asset center, 375×812, light and dark | PASS responsive stability: both themes have `documentElement.scrollWidth === 375`; no horizontal overflow or clipped header/card content. | [/tmp/surprising-qa-assets-mobile-light.png](/tmp/surprising-qa-assets-mobile-light.png), [/tmp/surprising-qa-assets-mobile-dark.png](/tmp/surprising-qa-assets-mobile-dark.png) |
| Trading shell, 375×812, dark | PASS narrow-layout containment: `scrollWidth === 375`; market, chart, book, ticket, and bottom deck stack without horizontal overflow. | [/tmp/surprising-qa-trade-mobile-dark.png](/tmp/surprising-qa-trade-mobile-dark.png) |

## Good, preserve

- The asset center and per-product route are live React components, not a raster surrogate. The browser tree exposes six distinct product-account controls and the spot page exposes deposit, withdrawal, transfer, currency, and asset-search controls.
- The new light and dark desktop captures reproduce the supplied compositions almost exactly, and the mobile asset center retains hierarchy without horizontal overflow.
- The topbar is actually shared with the auth route, rather than being visually copied into the login form.
- The ticket and bottom deck are real interactive controls. The `持仓` tab was exercised and changed the content pane.

## Blockers before approval

1. Move asset/ledger color, spacing, typography, radius, and elevation values onto the documented `--sx-*` design-token contract; remove the parallel one-off literal system.
2. Extract/reuse the core surface, action, field, and table primitives for the asset center and ledger rather than retaining page-specific CSS anatomy.
3. Add correct ARIA tab semantics to the new bottom-tab control.
4. Re-run the search result/market selection visual QA with a reachable market service or an approved controlled fixture.

## Evidence inspected

- Supplied screenshots: `/tmp/surprising-assets-dark.png`, `/tmp/surprising-assets-light.png`, `/tmp/surprising-spot-assets.png`, `/tmp/surprising-login.png`, `/tmp/surprising-trade-search.png`.
- Fresh live screenshots listed in the table above, all captured at the declared viewport with agent-browser.
- Image-diff JSON produced by `visual-qa.mjs` for each supplied/fresh desktop pair; all image files were verified as `1440×900` PNGs before comparison.
- Complete present Git diff for tracked files: `.env.example`, `src/App.tsx`, `src/api/surprising.ts`, `src/components/ProductTransferDialog.tsx`, `src/config.ts`, `src/styles.css`, `src/types.ts`; current on-disk source for untracked `src/components/AssetCenter.tsx` and `src/components/FundingLedgerPage.tsx`; `DESIGN.md`; and `src/components/AssetPrimitives.tsx`.

```yaml
recommendation: REQUEST_CHANGES
reportPath: .omo/evidence/surprising-ex-web-visual-qa-clone-fidelity.md
blockers:
  - New asset/ledger styling does not use the documented token system.
  - Core surface/action/field/table primitives are not reused.
  - Bottom tabs lack complete ARIA tab semantics.
  - Populated search result selection cannot be verified while the market service is unavailable.
```
