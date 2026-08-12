# manualQa

- Commit under test: `2b6c1ebd2712565133fa48bd8983e7a946c4f745`
- Source checkout: `/tmp/surprising-ex-web-review-2b6c1eb`
- Attempt directory: `.omo/evidence/manual-qa-2b6c1ebd` (no ulw-loop plan was present)
- Browser: agent-browser Chromium session `qa-2b6c1eb`
- Visual oracle note: no subagent/spawn tool was exposed in this harness, so the required independent oracle pass could not be dispatched; all listed verdicts are based on fresh, directly inspected browser artifacts and source evidence.

## surfaceEvidence

| scenario id | criterion reference | surface | exact invocation | verdict | artifactRefs |
|---|---|---|---|---|---|
| MKT-5173-1440 | C1, C2, C7 | `http://127.0.0.1:5173/markets`, real-backend failure, 1440×900 | `agent-browser --session qa-2b6c1eb set viewport 1440 900`; `open http://127.0.0.1:5173/markets`; `wait 1500`; screenshot, snapshot, DOM geometry, console/errors | PASS | A04, A05, A06, A07 |
| MKT-5173-375 | C1, C3, C7 | `http://127.0.0.1:5173/markets`, real-backend failure, 375×812 | `set viewport 375 812`; `open http://127.0.0.1:5173/markets`; `wait 1000`; screenshot, snapshot, DOM geometry, console/errors | PASS | A09, A10, A11 |
| MENU-5173-375 | C4, C7 | Mobile product-line menu on `/markets`, 375×812 | From MKT-5173-375, `click @e5` (`产品线`); `wait 250`; screenshot, snapshot, DOM geometry | PASS | A13, A14, A15 |
| THEME-5173-375 | C5, C7 | Theme toggle on `/markets`, 375×812 | From the mobile route, close menu with `click @e5`; record theme button geometry; `click @e3` (`切换明暗主题`); `wait 250`; capture settled light state and geometry | PASS | A16, A17, A18 |
| TRADE-5173-1440 | C6, C7 | `http://127.0.0.1:5173/trade/spot`, real-backend failure, 1440×900 | `set viewport 1440 900`; `open http://127.0.0.1:5173/trade/spot`; `wait 1500`; screenshot, snapshot, DOM geometry, console/errors | PASS | A19, A20, A21, A23 |
| TRADE-5173-375 | C6, C7 | `http://127.0.0.1:5173/trade/spot`, real-backend failure, 375×812 | `set viewport 375 812`; `open http://127.0.0.1:5173/trade/spot`; `wait 1200`; full-page screenshot, snapshot, DOM geometry | PASS | A24, A25, A26 |
| MKT-5174-1440 | C1, C2, C7 | `http://127.0.0.1:5174/markets`, dev fallback, 1440×900 | `set viewport 1440 900`; `open http://127.0.0.1:5174/markets`; `wait 1200`; clean full-page screenshot, snapshot, DOM geometry, console/errors | PASS | A27, A28, A29, A31 |
| MKT-5174-375 | C1, C3, C7 | `http://127.0.0.1:5174/markets`, dev fallback, 375×812 | `set viewport 375 812`; `open http://127.0.0.1:5174/markets`; `wait 1000`; clean full-page screenshot, snapshot, DOM geometry | PASS | A32, A33, A34 |

## adversarialCases

| scenario id | criterion reference | adversarial class | expected behavior | verdict | artifactRefs |
|---|---|---|---|---|---|
| ADV-BACKEND-EMPTY | C1 | real backend returns no market data | Show reconnecting/unavailable state and explicit no-demo-data copy; do not render fabricated market rows | PASS | A04, A05, A06 |
| ADV-FALLBACK-TRUTH | C1 | dev fallback data path | Render populated fallback values only on 5174 and mark the snapshot `数据降级`; do not claim live data | PASS | A27, A28, A29 |
| ADV-MOBILE-REFLOW | C3 | 375px responsive layout | Reflow content into the viewport; controls remain usable and the page does not exceed viewport width | PASS | A09, A11, A32, A34 |
| ADV-MENU-STATE | C4 | mobile menu open/close state | `产品线` exposes an expanded menu containing the market, six product lines, and rules destinations; menu state is represented by `aria-expanded` | PASS | A13, A14, A15 |
| ADV-THEME-GEOMETRY | C5 | dark-to-light theme transition | Theme changes to light without changing the toggle’s position/size or introducing width overflow | PASS | A16, A17, A18 |
| ADV-SPOT-CONTEXT | C6 | wrong product/account context | `/trade/spot` explicitly identifies `现货账户`, `SPOT` account type, and `SPOT` order ticket; no other product line is substituted | PASS | A19, A20, A21, A24, A26 |
| ADV-HORIZONTAL-OVERFLOW | C7 | page overflow at requested surfaces | `documentElement` and `body` scroll width equal client width at 1440 and 375 across failure, fallback, menu, theme, and trade states | PASS | A06, A11, A15, A17, A21, A26, A29, A34 |
| ADV-RUNTIME-ERRORS | C1, C6 | uncaught browser runtime errors | No browser page errors during the captured markets/trade flows | PASS | A35, A07, A23, A31 |

## artifactRefs

| id | kind | description | path |
|---|---|---|---|
| A01 | source | Exact commit identity, changed-file list, relevant source references, responsive CSS, and menu CSS | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/source-commit-validation.txt` |
| A02 | test-log | Exact commit `npm test`, 10 tests passed | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/exact-commit-npm-test.log` |
| A03 | build-log | Exact commit lockfile-matched pnpm build passed | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/exact-commit-pnpm-build.log` |
| A04 | screenshot | Real backend failure markets, 1440×900, clean UI capture | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/markets-5173-1440-clean.png` |
| A05 | snapshot | Real backend failure markets accessibility snapshot, 1440 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/markets-5173-1440.snapshot.txt` |
| A06 | geometry | Real backend failure markets viewport/scroll-width/text evidence, 1440 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/markets-5173-1440.geometry.json` |
| A07 | console-log | Real backend failure markets browser console capture, 1440 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/markets-5173-1440.console.txt` |
| A09 | screenshot | Real backend failure markets, 375×812 full-page capture | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/markets-5173-375.png` |
| A10 | snapshot | Real backend failure markets accessibility snapshot, 375 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/markets-5173-375.snapshot.txt` |
| A11 | geometry | Real backend failure markets viewport/scroll-width/text evidence, 375 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/markets-5173-375.geometry.json` |
| A13 | screenshot | Mobile product-line menu open, 375×812 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/mobile-menu-open-5173-375.png` |
| A14 | snapshot | Mobile product-line menu open accessibility snapshot with `expanded=true` | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/mobile-menu-open-5173-375.snapshot.txt` |
| A15 | geometry | Mobile product-line menu open viewport/scroll-width/text evidence | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/mobile-menu-open-5173-375.geometry.json` |
| A16 | geometry | Dark theme toggle button geometry and page width | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/theme-dark-5173-375.geometry.json` |
| A17 | geometry | Light theme toggle button geometry and page width | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/theme-light-5173-375.geometry.json` |
| A18 | screenshot | Settled light-theme markets capture, 375×812 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/theme-light-5173-375.png` |
| A19 | screenshot | Real backend failure spot trade, 1440×1051 full-page capture | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/trade-spot-5173-1440.png` |
| A20 | snapshot | Spot trade accessibility snapshot, 1440 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/trade-spot-5173-1440.snapshot.txt` |
| A21 | geometry | Spot trade viewport/scroll-width/text evidence, 1440 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/trade-spot-5173-1440.geometry.json` |
| A23 | console-log | Spot trade browser console capture, 1440 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/trade-spot-5173-1440.console.txt` |
| A24 | screenshot | Real backend failure spot trade, 375×2240 full-page capture | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/trade-spot-5173-375.png` |
| A25 | snapshot | Spot trade accessibility snapshot, 375 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/trade-spot-5173-375.snapshot.txt` |
| A26 | geometry | Spot trade viewport/scroll-width/text evidence, 375 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/trade-spot-5173-375.geometry.json` |
| A27 | screenshot | Dev fallback markets, 1440×1365 clean full-page capture | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/markets-5174-1440-clean.png` |
| A28 | snapshot | Dev fallback markets accessibility snapshot, 1440 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/markets-5174-1440.snapshot.txt` |
| A29 | geometry | Dev fallback markets viewport/scroll-width/text evidence, 1440 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/markets-5174-1440.geometry.json` |
| A31 | console-log | Dev fallback browser console capture, 1440 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/markets-5174-1440.console.txt` |
| A32 | screenshot | Dev fallback markets, 375×1991 clean full-page capture | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/markets-5174-375-clean.png` |
| A33 | snapshot | Dev fallback markets accessibility snapshot, 375 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/markets-5174-375.snapshot.txt` |
| A34 | geometry | Dev fallback markets viewport/scroll-width/text evidence, 375 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/markets-5174-375.geometry.json` |
| A35 | execution-log | Non-empty audit tying the empty page-error captures to their exact browser invocations | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/browser-error-audit.md` |

## verdict summary

All executed requested surfaces PASS against the exact commit. The 5173 real-backend state is explicitly unavailable and does not fabricate market rows; 5174 is available as a labeled degraded fallback with populated data. All captured page/body width checks equal the requested viewport width, and `/trade/spot` preserves explicit SPOT context at both viewports.
