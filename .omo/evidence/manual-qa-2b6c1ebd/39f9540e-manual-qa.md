# manualQa

- Commit under test: `39f9540e82042afb7bdcf3995b8569238cf3640e`
- Served UI repository: `/Users/atomex/Desktop/surprising/surprising-ex-web`
- Surfaces: live `http://127.0.0.1:5173` and fallback `http://127.0.0.1:5174`
- Browser: Browser plugin unavailable; `agent-browser` Chromium fallback session `qa-39f9540e`
- All browser error artifacts report `errors: []`. Console artifacts contain only Vite/React development notices, not app errors.
- No product files were edited. The backend checkout’s unrelated working tree was preserved.

## surfaceEvidence

| scenario id | criterion reference | surface | exact invocation | verdict | artifactRefs |
|---|---|---|---|---|---|
| MKT-5173-1440 | C1, C2, C7 | `http://127.0.0.1:5173/markets`, real-backend unavailable state, 1440×900 | `curl -i --max-time 10 http://127.0.0.1:5173/markets`; `agent-browser --session qa-39f9540e set viewport 1440 900`; `open http://127.0.0.1:5173/markets`; wait 2200ms; full screenshot, snapshot, DOM geometry, console/errors JSON | PASS | A02, A03, A04, A05, A06 |
| MKT-5173-375 | C1, C3, C7 | `http://127.0.0.1:5173/markets`, real-backend unavailable state, 375×812 | `agent-browser --session qa-39f9540e set viewport 375 812`; `open http://127.0.0.1:5173/markets`; wait 2200ms; full screenshot, snapshot, DOM geometry, console/errors JSON | PASS | A07, A08, A09, A10, A11 |
| MKT-5174-1440 | C1, C2, C7 | `http://127.0.0.1:5174/markets`, labeled fallback state, 1440×900 | `curl -i --max-time 10 http://127.0.0.1:5174/markets`; `agent-browser --session qa-39f9540e set viewport 1440 900`; `open http://127.0.0.1:5174/markets`; wait 2200ms; full screenshot, snapshot, DOM geometry, console/errors JSON | PASS | A12, A13, A14, A15, A16 |
| MKT-5174-375 | C1, C3, C7 | `http://127.0.0.1:5174/markets`, labeled fallback state, 375×812 | `agent-browser --session qa-39f9540e set viewport 375 812`; `open http://127.0.0.1:5174/markets`; wait 2200ms; full screenshot, snapshot, DOM geometry, console/errors JSON | PASS | A17, A18, A19, A20, A21 |
| MENU-5173-375 | C4, C7 | Mobile product-line menu on `/markets`, 375×812 | From `MKT-5173-375`, click `button "产品线"` with `aria-expanded=false`; wait 350ms; screenshot, snapshot, DOM geometry | PASS | A22, A23, A24, A25 |
| THEME-5173-375 | C5, C7 | Theme toggle on `/markets`, 375×812 | Close menu; capture dark geometry; click `button[aria-label="切换明暗主题"]`; wait 400ms; capture light screenshot/snapshot/geometry/errors | PASS | A26, A27, A28, A29, A30 |
| TRADE-5173-1440 | C6, C7 | `http://127.0.0.1:5173/trade/spot`, real-backend unavailable, 1440×900 | `curl -i --max-time 10 http://127.0.0.1:5173/trade/spot`; `agent-browser --session qa-39f9540e set viewport 1440 900`; `open http://127.0.0.1:5173/trade/spot`; wait 2400ms; full screenshot, snapshot, DOM geometry, console/errors JSON | PASS | A31, A32, A33, A34, A35 |
| TRADE-5173-375 | C6, C7 | `http://127.0.0.1:5173/trade/spot`, real-backend unavailable, 375×812 | `agent-browser --session qa-39f9540e set viewport 375 812`; `open http://127.0.0.1:5173/trade/spot`; wait 2400ms; full screenshot, snapshot, DOM geometry, console/errors JSON | PASS | A36, A37, A38, A39, A40 |

## adversarialCases

| scenario id | criterion reference | adversarial class | expected behavior | verdict | artifactRefs |
|---|---|---|---|---|---|
| ADV-BACKEND-EMPTY | C1 | Real backend returns no market data | Show explicit unavailable/retry state, zero market rows, and no fabricated/demo rows | PASS | A02, A03, A04, A05, A07, A08, A09 |
| ADV-FALLBACK-TRUTH | C1 | Dev fallback data path | Populate fallback markets only on 5174 and visibly label the state `数据降级`; do not imply live data | PASS | A12, A13, A14, A15, A17, A18, A19 |
| ADV-MOBILE-REFLOW | C3 | 375px responsive layout | Reflow controls/content into 375px with usable text and no horizontal overflow | PASS | A07, A09, A17, A19, A36, A38 |
| ADV-MENU-COMPLETE | C4 | Mobile menu open state | `aria-expanded=true`; show market, funding/assets/security, all six product destinations, and rules destination | PASS | A22, A23, A24 |
| ADV-THEME-GEOMETRY | C5 | Dark-to-light transition | Theme changes while toggle geometry and page width remain stable | PASS | A26, A27, A28, A29 |
| ADV-SPOT-CONTEXT | C6 | Wrong product/account context | `/trade/spot` explicitly shows `现货账户`, account type `SPOT`, and `SPOT` order ticket with spot order controls | PASS | A31, A32, A33, A36, A37, A38 |
| ADV-HORIZONTAL-OVERFLOW | C7 | Page overflow at requested surfaces/states | `documentElement` and `body` scroll widths equal viewport widths in markets, menu, theme, fallback, and trade states | PASS | A09, A19, A24, A27, A29, A33, A38 |
| ADV-RUNTIME-ERRORS | C1, C6 | Uncaught browser runtime errors | No page errors during captured market, menu, theme, and trade flows | PASS | A06, A11, A16, A21, A25, A30, A35, A40 |

## artifactRefs

| id | kind | description | path |
|---|---|---|---|
| A01 | provenance | Served repository, exact commit, HTTP invocation record | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-served-commit.txt` |
| A02 | http | `curl -i` response for live 5173 markets | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-http-5173-markets.txt` |
| A03 | screenshot | 5173 markets unavailable state, 1440×900 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-markets-5173-1440.png` |
| A04 | snapshot | 5173 markets accessibility snapshot, 1440 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-markets-5173-1440.snapshot.txt` |
| A05 | geometry | 5173 markets: no rows/demo symbols, unavailable copy, width checks, 1440 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-markets-5173-1440.geometry.json` |
| A06 | browser-log | 5173 markets console/errors JSON, 1440 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-markets-5173-1440.errors.json` |
| A07 | screenshot | 5173 markets unavailable state, 375×812 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-markets-5173-375.png` |
| A08 | snapshot | 5173 markets accessibility snapshot, 375 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-markets-5173-375.snapshot.txt` |
| A09 | geometry | 5173 markets: no rows/demo symbols, unavailable copy, width checks, 375 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-markets-5173-375.geometry.json` |
| A10 | browser-log | 5173 markets console JSON, 375 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-markets-5173-375.console.json` |
| A11 | browser-log | 5173 markets page-error JSON, 375 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-markets-5173-375.errors.json` |
| A12 | http | `curl -i` response for fallback 5174 markets | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-http-5174-markets.txt` |
| A13 | screenshot | 5174 fallback markets with populated rows, 1440×1365 full page | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-markets-5174-1440.png` |
| A14 | snapshot | 5174 fallback markets accessibility snapshot, 1440 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-markets-5174-1440.snapshot.txt` |
| A15 | geometry | 5174 fallback: six markets, `数据降级`, width checks, 1440 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-markets-5174-1440.geometry.json` |
| A16 | browser-log | 5174 fallback page-error JSON, 1440 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-markets-5174-1440.errors.json` |
| A17 | screenshot | 5174 fallback markets with populated rows, 375×1991 full page | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-markets-5174-375.png` |
| A18 | snapshot | 5174 fallback markets accessibility snapshot, 375 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-markets-5174-375.snapshot.txt` |
| A19 | geometry | 5174 fallback: six markets, `数据降级`, width checks, 375 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-markets-5174-375.geometry.json` |
| A20 | browser-log | 5174 fallback console JSON, 375 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-markets-5174-375.console.json` |
| A21 | browser-log | 5174 fallback page-error JSON, 375 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-markets-5174-375.errors.json` |
| A22 | screenshot | Mobile product-line menu expanded, 375×812 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-mobile-menu-5173-375.png` |
| A23 | snapshot | Menu expanded with `aria-expanded=true` and complete destination list | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-mobile-menu-5173-375.snapshot.txt` |
| A24 | geometry | Menu expanded: required labels, `aria-expanded`, width checks | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-mobile-menu-5173-375.geometry.json` |
| A25 | browser-log | Menu page-error JSON | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-mobile-menu-5173-375.errors.json` |
| A26 | screenshot | Dark theme markets state, 375×812 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-theme-dark-5173-375.png` |
| A27 | geometry | Dark theme toggle geometry and width | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-theme-dark-5173-375.geometry.json` |
| A28 | screenshot | Settled light theme markets state, 375×812 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-theme-light-5173-375.png` |
| A29 | geometry | Light theme toggle geometry and width | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-theme-light-5173-375.geometry.json` |
| A30 | browser-log | Light theme page-error JSON | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-theme-light-5173-375.errors.json` |
| A31 | http | `curl -i` response for live 5173 spot trade | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-http-5173-trade-spot.txt` |
| A32 | screenshot | Spot trade context, 1440×1051 full page | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-trade-spot-5173-1440.png` |
| A33 | snapshot | Spot trade accessibility snapshot with account/order context, 1440 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-trade-spot-5173-1440.snapshot.txt` |
| A34 | geometry | Spot trade context strings and width checks, 1440 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-trade-spot-5173-1440.geometry.json` |
| A35 | browser-log | Spot trade page-error JSON, 1440 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-trade-spot-5173-1440.errors.json` |
| A36 | screenshot | Spot trade context, 375×2240 full page | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-trade-spot-5173-375.png` |
| A37 | snapshot | Spot trade accessibility snapshot with account/order context, 375 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-trade-spot-5173-375.snapshot.txt` |
| A38 | geometry | Spot trade context strings and width checks, 375 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-trade-spot-5173-375.geometry.json` |
| A39 | browser-log | Spot trade console JSON, 375 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-trade-spot-5173-375.console.json` |
| A40 | browser-log | Spot trade page-error JSON, 375 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-2b6c1ebd/39f9540e-trade-spot-5173-375.errors.json` |

## verdict

All requested live scenarios PASS against the exact served commit. The real-backend path is truthful and empty, the fallback is visibly degraded, the mobile menu is complete, theme geometry is stable, spot context is explicit, and no horizontal overflow or uncaught page errors were observed. Independent visual-oracle subagents were not available in this harness; direct browser screenshots, snapshots, geometry, and logs are the recorded evidence.
