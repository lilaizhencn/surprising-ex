# Manual visual QA execution log

Date: 2026-08-12
Surface: `http://127.0.0.1:5173`, agent-browser session `surprising-qa`, viewport 1440x900.

## Invocations and observations

- VQA-01 Asset center: `agent-browser --session surprising-qa open http://127.0.0.1:5173/assets`; then `agent-browser --session surprising-qa click @e7` on the snapshot's `切换明暗主题` button; screenshots `live-assets-dark-route.png` and `theme-toggle-result.png` are the verified dark/light pair, with `live-assets-light.png` retained as an additional settled capture. Both themes keep the same asset-center geometry; six product-account cards, total-equity card, and ledger preview remain present.
- VQA-02 Spot asset detail: `agent-browser --session surprising-qa open http://127.0.0.1:5173/assets/spot`; screenshot `live-spot-assets.png`. The SPOT account header, deposit/withdraw/transfer actions, holdings search, columns, and empty state are visible without clipping.
- VQA-03 Auth shared topbar: `agent-browser --session surprising-qa click @e9` from the asset/ledger surface; screenshots `live-login-dark.png` and `login-after-toggle.png` are the verified dark/light pair, with `live-login-light.png` retained as an additional settled capture. The shared brand, product navigation, reconnect status, global search, funding/security/theme/language controls, and auth actions remain present above the login card in both themes.
- VQA-04 Ledger page: `agent-browser --session surprising-qa open http://127.0.0.1:5173/ledger`; screenshot `live-ledger.png`; then `agent-browser --session surprising-qa click @e7`; screenshot `live-ledger-light.png`. Light mode is readable. In dark mode the `资金流水` H1 is rendered near-black on the dark page background and is effectively invisible.
- VQA-05 Trade search: `agent-browser --session surprising-qa open http://127.0.0.1:5173/trade/usdt-perpetual`; then `agent-browser --session surprising-qa fill @e40 BTC`; screenshot `live-trade-search.png`. The topbar search and market-rail search both show `BTC`, while the unavailable-market state remains contained.
- VQA-06 Trading ticket and bottom tabs: from VQA-05, `agent-browser --session surprising-qa click @e29` changes the side to sell and the CTA to `确认卖出`; then `agent-browser --session surprising-qa click @e44` selects `持仓` and renders `持仓 / 风险`. Screenshots: `trade-sell-toggle.png`, `trade-holdings-tab.png`.

## Fresh visual-diff evidence

The bundled image-diff tool reported matching 1440x900 dimensions and intact alpha for all pairs. Similarity was 100/100 for assets dark, assets light, spot assets, login, and trade search. The only non-zero hotspots were small capture-tool overlays or topbar raster differences; no page-layout drift was found in those pairs.

The diff command used was:

`node /Users/atomex/.codex/plugins/cache/sisyphuslabs/omo/4.19.4/skills/visual-qa/scripts/visual-qa.mjs image-diff <reference.png> <fresh.png>`
