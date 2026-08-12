# manualQa

Overall verdict: **FAIL**. The requested asset, spot-asset, auth, search, and trade-ticket surfaces pass at 1440x900. The dark ledger page fails visual QA because its primary heading has insufficient contrast and is effectively unreadable.

## surfaceEvidence

| scenario id | criterion reference | surface | exact invocation | verdict | artifactRefs |
|---|---|---|---|---|---|
| VQA-01 | Asset center; light/dark layout stability | `/assets` at 1440x900 | `agent-browser --session surprising-qa open http://127.0.0.1:5173/assets`; `agent-browser --session surprising-qa click @e7` where the snapshot labels `切换明暗主题`; capture each settled state | PASS | A1, A2, A3 |
| VQA-02 | User-requested spot assets | `/assets/spot` at 1440x900 | `agent-browser --session surprising-qa open http://127.0.0.1:5173/assets/spot`; wait 700ms; `agent-browser --session surprising-qa screenshot .../live-spot-assets.png` | PASS | A4 |
| VQA-03 | Auth shared topbar | `/login` at 1440x900, light and dark | `agent-browser --session surprising-qa click @e9` from the asset/ledger snapshot; capture login; `agent-browser --session surprising-qa click @e7`; capture the settled alternate theme | PASS | A5, A6, A7 |
| VQA-04 | Ledger page; light/dark layout stability | `/ledger` at 1440x900, light and dark | `agent-browser --session surprising-qa open http://127.0.0.1:5173/ledger`; capture; `agent-browser --session surprising-qa click @e7`; capture | FAIL | A8, A9 |
| VQA-05 | Search UI | `/trade/usdt-perpetual` at 1440x900 | `agent-browser --session surprising-qa open http://127.0.0.1:5173/trade/usdt-perpetual`; `agent-browser --session surprising-qa fill @e40 BTC`; capture; snapshot confirms topbar and rail both contain `BTC` | PASS | A10 |
| VQA-06 | Trading ticket and bottom tabs | `/trade/usdt-perpetual` at 1440x900 | From VQA-05, `agent-browser --session surprising-qa click @e29`; then `agent-browser --session surprising-qa click @e44`; capture each settled state | PASS | A11, A12 |

Finding for VQA-04: in `live-ledger.png`, the `资金流水` heading around the upper-left content region is near-black against the dark page background. The page is otherwise laid out, but the primary page title is not legible. Fix the ledger heading's dark-theme text token/style and re-capture the dark ledger page.

## adversarialCases

| scenario id | criterion reference | adversarial class | expected behavior | verdict | artifactRefs |
|---|---|---|---|---|---|
| ADV-01 | Light/dark stability; ledger | dark-theme contrast | Primary headings, labels, controls, and empty-state copy remain readable in dark mode | FAIL | A8 |
| ADV-02 | Asset center, ledger, trade | empty/unavailable data | Empty balances, no ledger entries, and unavailable market data use contained, readable empty/error states without broken geometry | PASS | A1, A4, A8, A10 |
| ADV-03 | Auth, ledger, trade | CJK precision and clipping | Chinese headings, labels, tab names, and helper copy do not orphan, clip, or overflow at the tested viewport | PASS | A4, A5, A9, A12 |
| ADV-04 | Search UI | search with no matching/live market data | Entering `BTC` updates the search UI and leaves a clear no-market state when the backend reports no markets | PASS | A10 |
| ADV-05 | Trade ticket and bottom tabs | interactive state transition | Sell selection changes the visual side and CTA; holdings selection changes the account-deck content | PASS | A11, A12 |

## artifactRefs

| id | kind | description | path |
|---|---|---|---|
| A1 | screenshot | Fresh dark asset-center route | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-d988ecaecadd1e55692f2cf630d5f5d72dd8a4c2/live-assets-dark-route.png` |
| A2 | screenshot | Fresh light asset-center route | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-d988ecaecadd1e55692f2cf630d5f5d72dd8a4c2/theme-toggle-result.png` |
| A3 | screenshot | Fresh asset-center capture before theme toggle | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-d988ecaecadd1e55692f2cf630d5f5d72dd8a4c2/live-assets-light.png` |
| A4 | screenshot | Fresh spot asset-detail page | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-d988ecaecadd1e55692f2cf630d5f5d72dd8a4c2/live-spot-assets.png` |
| A5 | screenshot | Fresh login page in light theme | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-d988ecaecadd1e55692f2cf630d5f5d72dd8a4c2/login-after-toggle.png` |
| A6 | screenshot | Fresh login page in dark theme | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-d988ecaecadd1e55692f2cf630d5f5d72dd8a4c2/live-login-dark.png` |
| A7 | screenshot | Fresh light ledger-to-login navigation check | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-d988ecaecadd1e55692f2cf630d5f5d72dd8a4c2/login-after-ledger-nav.png` |
| A8 | screenshot | Fresh dark ledger page showing heading contrast failure | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-d988ecaecadd1e55692f2cf630d5f5d72dd8a4c2/live-ledger.png` |
| A9 | screenshot | Fresh light ledger page | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-d988ecaecadd1e55692f2cf630d5f5d72dd8a4c2/live-ledger-light.png` |
| A10 | screenshot | Fresh BTC search state on trade page | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-d988ecaecadd1e55692f2cf630d5f5d72dd8a4c2/live-trade-search.png` |
| A11 | screenshot | Fresh sell-side order-ticket state | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-d988ecaecadd1e55692f2cf630d5f5d72dd8a4c2/trade-sell-toggle.png` |
| A12 | screenshot | Fresh holdings bottom-tab state | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-d988ecaecadd1e55692f2cf630d5f5d72dd8a4c2/trade-holdings-tab.png` |
| A13 | log | Commands, states, and visual-diff summary | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-d988ecaecadd1e55692f2cf630d5f5d72dd8a4c2/manual-qa-execution-log.md` |
